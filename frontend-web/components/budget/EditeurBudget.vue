<script setup lang="ts">
/**
 * Éditeur d'un budget prévisionnel (brouillon) : en-tête, puis lignes par section (produits, charges,
 * investissements). Chaque ligne porte un compte SYSCOHADA et ses douze montants mensuels ; le montant
 * annuel se répartit à parts égales ou selon la saisonnalité réelle de l'année précédente, et chaque
 * mois reste modifiable (l'annuel suit). Trimestres, semestres et année sont toujours des totaux exacts.
 */
interface CompteBudgetable { numero: string, libelle: string, section: string, imputable?: boolean }
interface LigneEdition {
  cle: number
  compteNumero: string | null
  compteLibelle: string
  section: string
  annuel: number | null
  mensuel: number[]
  mode: 'UNIFORME' | 'SAISONNALITE' | 'MANUEL'
  commentaire: string
  ouverte: boolean
  justificationIa?: string
  realiseReference?: number | null
}

const props = defineProps<{ budgetId?: number | null }>()
const emit = defineEmits<{ (e: 'enregistre', id: number): void, (e: 'annule'): void }>()

const api = useApi()
const chargement = ref(false)
const enregistrement = ref(false)
const erreur = ref('')
const avertissementRepartition = ref('')

const entete = reactive({ intitule: '', exercice: new Date().getFullYear() + (new Date().getMonth() >= 9 ? 1 : 0), observation: '' })
const lignes = ref<LigneEdition[]>([])
let compteur = 0

function nouvelleLigne(section = 'CHARGES'): LigneEdition {
  return { cle: ++compteur, compteNumero: null, compteLibelle: '', section, annuel: null, mensuel: Array(12).fill(0), mode: 'UNIFORME', commentaire: '', ouverte: false }
}

// ---------------------------------------------------------------------------------------------
// Chargement d'un budget existant
// ---------------------------------------------------------------------------------------------
async function charger() {
  if (!props.budgetId) {
    lignes.value = [nouvelleLigne('PRODUITS'), nouvelleLigne('CHARGES')]
    return
  }
  chargement.value = true
  try {
    const b = await api<any>(`/budgets/${props.budgetId}`)
    entete.intitule = b.intitule
    entete.exercice = b.exercice
    entete.observation = b.observation || ''
    lignes.value = (b.lignes || []).map((l: any) => ({
      cle: ++compteur,
      compteNumero: l.compteNumero,
      compteLibelle: l.compteLibelle,
      section: l.section,
      annuel: Number(l.montantPrevu),
      mensuel: (l.mensuel || []).map((v: any) => Number(v)),
      mode: 'MANUEL',
      commentaire: l.commentaire || '',
      ouverte: false,
    }))
    lignes.value.forEach(l => memoriserCompte({ numero: l.compteNumero!, libelle: l.compteLibelle, section: l.section }))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le budget.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

// ---------------------------------------------------------------------------------------------
// Recherche de comptes (plan comptable SYSCOHADA, classes 2, 6, 7, 8)
// ---------------------------------------------------------------------------------------------
const comptesConnus = reactive<Record<string, CompteBudgetable>>({})
const resultats = ref<CompteBudgetable[]>([])
const rechercheEnCours = ref(false)
let minuterie: ReturnType<typeof setTimeout> | null = null

function memoriserCompte(c: CompteBudgetable) {
  if (c?.numero) comptesConnus[c.numero] = c
}

function chercherComptes(q: string | null) {
  if (minuterie) clearTimeout(minuterie)
  minuterie = setTimeout(async () => {
    rechercheEnCours.value = true
    try {
      const liste = await api<CompteBudgetable[]>('/budgets/comptes', { query: { q: q || '' } })
      liste.forEach(memoriserCompte)
      resultats.value = liste
    } catch {
      resultats.value = []
    } finally {
      rechercheEnCours.value = false
    }
  }, 250)
}
onMounted(() => chercherComptes(''))

function itemsPour(l: LigneEdition): CompteBudgetable[] {
  const courant = l.compteNumero && comptesConnus[l.compteNumero]
  return courant && !resultats.value.some(r => r.numero === courant.numero) ? [courant, ...resultats.value] : resultats.value
}

function choisirCompte(l: LigneEdition, numero: string | null) {
  l.compteNumero = numero
  const c = numero ? comptesConnus[numero] : null
  if (c) {
    l.compteLibelle = c.libelle
    l.section = c.section
  }
  if (l.annuel && l.mode === 'SAISONNALITE') appliquerMode(l)
}

// ---------------------------------------------------------------------------------------------
// Montants : annuel <-> mois
// ---------------------------------------------------------------------------------------------
async function appliquerMode(l: LigneEdition) {
  avertissementRepartition.value = ''
  const annuel = Number(l.annuel) || 0
  if (l.mode === 'UNIFORME') {
    l.mensuel = repartirUniformement(annuel)
  } else if (l.mode === 'SAISONNALITE') {
    if (!l.compteNumero) {
      l.mensuel = repartirUniformement(annuel)
      return
    }
    try {
      const r = await api<{ mensuel: number[], mode: string, explication: string }>('/budgets/repartition', {
        method: 'POST', body: { montantAnnuel: annuel, mode: 'SAISONNALITE', compteNumero: l.compteNumero, exercice: entete.exercice },
      })
      l.mensuel = r.mensuel.map(Number)
      if (r.mode !== 'SAISONNALITE') avertissementRepartition.value = `${l.compteNumero} : ${r.explication}`
    } catch (e) {
      l.mensuel = repartirUniformement(annuel)
      avertissementRepartition.value = messageErreurApi(e, 'Saisonnalité indisponible : parts égales appliquées.')
    }
  }
}

function changerAnnuel(l: LigneEdition) {
  if (l.mode === 'MANUEL') l.mode = 'UNIFORME'
  appliquerMode(l)
}

function changerMois(l: LigneEdition, i: number, v: string | number) {
  l.mensuel[i] = Math.max(0, Number(v) || 0)
  l.mode = 'MANUEL'
  l.annuel = total(l.mensuel)
}

const MODES = [
  { value: 'UNIFORME', title: 'Parts égales' },
  { value: 'SAISONNALITE', title: 'Saisonnalité N-1' },
  { value: 'MANUEL', title: 'Mois par mois' },
]

// ---------------------------------------------------------------------------------------------
// Sections et totaux
// ---------------------------------------------------------------------------------------------
const ORDRE = ['PRODUITS', 'CHARGES', 'INVESTISSEMENTS']
const lignesPar = (s: string) => lignes.value.filter(l => l.section === s)
const totalSection = (s: string) => total(lignesPar(s).map(l => total(l.mensuel)))
const mensuelSection = (s: string) => Array.from({ length: 12 }, (_, i) => centimes(lignesPar(s).reduce((t, l) => t + (Number(l.mensuel[i]) || 0), 0)))
const resultat = computed(() => centimes(totalSection('PRODUITS') - totalSection('CHARGES')))

function ajouterLigne(section: string) {
  const l = nouvelleLigne(section)
  lignes.value.push(l)
}
function retirerLigne(l: LigneEdition) {
  lignes.value = lignes.value.filter(x => x.cle !== l.cle)
}

/** Deux lignes dont l'une couvre l'autre (même compte ou sous-compte) : refusé par le serveur, signalé ici. */
const chevauchements = computed(() => {
  const numeros = lignes.value.map(l => l.compteNumero).filter(Boolean) as string[]
  const conflits: string[] = []
  for (let i = 0; i < numeros.length; i++) {
    for (let j = i + 1; j < numeros.length; j++) {
      const [a, b] = numeros[i].length <= numeros[j].length ? [numeros[i], numeros[j]] : [numeros[j], numeros[i]]
      if (b.startsWith(a)) conflits.push(a === b ? `${a} en double` : `${a} couvre déjà ${b}`)
    }
  }
  return conflits
})

// ---------------------------------------------------------------------------------------------
// Proposition par l'IA
// ---------------------------------------------------------------------------------------------
const dialogueIa = ref(false)
const hypotheses = ref('')
const propositionEnCours = ref(false)
const proposition = ref<any | null>(null)

async function proposer() {
  propositionEnCours.value = true
  proposition.value = null
  erreur.value = ''
  try {
    proposition.value = await api<any>('/budgets/proposition', {
      method: 'POST', body: { exercice: entete.exercice, hypotheses: hypotheses.value || null },
    })
  } catch (e) {
    erreur.value = messageErreurApi(e, 'La proposition n’a pas pu être établie.')
  } finally {
    propositionEnCours.value = false
  }
}

function utiliserProposition() {
  if (!proposition.value) return
  lignes.value = proposition.value.lignes.map((l: any) => {
    memoriserCompte({ numero: l.compteNumero, libelle: l.compteLibelle, section: l.section })
    return {
      cle: ++compteur,
      compteNumero: l.compteNumero,
      compteLibelle: l.compteLibelle,
      section: l.section,
      annuel: Number(l.montantAnnuel),
      mensuel: l.mensuel.map(Number),
      mode: 'MANUEL',
      commentaire: l.justification || '',
      ouverte: false,
      justificationIa: l.justification,
      realiseReference: l.realiseReference,
    } as LigneEdition
  })
  if (!entete.intitule) entete.intitule = `Budget ${entete.exercice}`
  if (!entete.observation && proposition.value.synthese) {
    entete.observation = (proposition.value.source === 'IA' ? 'Proposition de l’IA : ' : 'Proposition calculée : ') + proposition.value.synthese
  }
  dialogueIa.value = false
}

// ---------------------------------------------------------------------------------------------
// Enregistrement
// ---------------------------------------------------------------------------------------------
async function enregistrer() {
  erreur.value = ''
  const valides = lignes.value.filter(l => l.compteNumero)
  if (!entete.intitule.trim()) { erreur.value = 'Indiquez l’intitulé du budget.'; return }
  if (!valides.length) { erreur.value = 'Ajoutez au moins une ligne avec un compte.'; return }
  if (chevauchements.value.length) { erreur.value = `Lignes en recouvrement : ${chevauchements.value.join(', ')}.`; return }
  enregistrement.value = true
  try {
    const corps = {
      intitule: entete.intitule.trim(),
      exercice: Number(entete.exercice),
      observation: entete.observation || null,
      lignes: valides.map(l => ({ compteNumero: l.compteNumero, mensuel: l.mensuel.map(v => centimes(Number(v) || 0)), commentaire: l.commentaire || null })),
    }
    const b = props.budgetId
      ? await api<any>(`/budgets/${props.budgetId}`, { method: 'PUT', body: corps })
      : await api<any>('/budgets', { method: 'POST', body: corps })
    emit('enregistre', b.id)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'L’enregistrement a échoué.')
  } finally {
    enregistrement.value = false
  }
}
</script>

<template>
  <div class="eb">
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="chargement" type="card, table" />

    <template v-else>
      <!-- En-tête -->
      <v-card class="classroom-card pa-4 mb-4">
        <div class="eb-entete">
          <v-text-field v-model="entete.intitule" label="Intitulé du budget *" placeholder="Ex. Budget 2027 – Exploitation" hide-details="auto" class="eb-entete__titre" />
          <v-text-field v-model.number="entete.exercice" label="Exercice *" type="number" hide-details="auto" class="eb-entete__exercice" />
          <v-btn color="purple" variant="tonal" prepend-icon="mdi-robot-outline" height="48" @click="dialogueIa = true">
            Proposer avec l’IA
          </v-btn>
        </div>
        <v-textarea v-model="entete.observation" label="Observation, hypothèses générales" rows="2" auto-grow hide-details class="mt-3" />
      </v-card>

      <v-alert v-if="avertissementRepartition" type="info" variant="tonal" density="compact" class="mb-3" closable @click:close="avertissementRepartition = ''">
        {{ avertissementRepartition }}
      </v-alert>
      <v-alert v-if="chevauchements.length" type="warning" variant="tonal" density="compact" class="mb-3">
        Lignes en recouvrement (une dépense y serait comptée deux fois) : {{ chevauchements.join(', ') }}.
        Gardez soit le compte général, soit ses sous-comptes.
      </v-alert>

      <!-- Sections -->
      <v-card v-for="s in ORDRE" :key="s" class="classroom-card mb-4">
        <div class="eb-section-tete" :style="{ borderLeftColor: SECTIONS[s].color }">
          <div>
            <span class="eb-section-tete__titre">{{ SECTIONS[s].label }}</span>
            <span class="eb-section-tete__sens">{{ SECTIONS[s].sens }}</span>
          </div>
          <div class="eb-section-tete__total">
            {{ fmtMontant(totalSection(s)) }} <small>USD</small>
          </div>
        </div>

        <div class="eb-table-wrap">
          <table class="eb-table">
            <thead>
              <tr>
                <th class="eb-col-compte">Compte SYSCOHADA</th>
                <th class="eb-col-num">Montant annuel</th>
                <th class="eb-col-mode">Répartition</th>
                <th v-for="t in ['T1', 'T2', 'T3', 'T4']" :key="t" class="eb-col-num">{{ t }}</th>
                <th class="eb-col-act" />
              </tr>
            </thead>
            <tbody>
              <template v-for="l in lignesPar(s)" :key="l.cle">
                <tr>
                  <td class="eb-col-compte">
                    <v-autocomplete
                      :model-value="l.compteNumero"
                      :items="itemsPour(l)"
                      item-value="numero"
                      :item-title="(c: any) => `${c.numero} — ${c.libelle}`"
                      placeholder="Numéro ou libellé du compte"
                      density="compact"
                      hide-details
                      no-filter
                      :loading="rechercheEnCours"
                      @update:search="chercherComptes"
                      @update:model-value="(v: any) => choisirCompte(l, v)"
                    />
                    <div v-if="l.justificationIa" class="eb-ia-note">
                      <v-icon icon="mdi-robot-outline" size="12" /> {{ l.justificationIa }}
                    </div>
                  </td>
                  <td class="eb-col-num">
                    <v-text-field v-model.number="l.annuel" type="number" min="0" density="compact" hide-details suffix="USD" @change="changerAnnuel(l)" />
                  </td>
                  <td class="eb-col-mode">
                    <v-select v-model="l.mode" :items="MODES" density="compact" hide-details @update:model-value="appliquerMode(l)" />
                  </td>
                  <td v-for="(v, i) in regrouper(l.mensuel, 'TRIMESTRIEL')" :key="i" class="eb-col-num eb-montant">{{ fmtMontant(v) }}</td>
                  <td class="eb-col-act">
                    <v-btn :icon="l.ouverte ? 'mdi-chevron-up' : 'mdi-calendar-month-outline'" size="small" variant="text"
                      :title="l.ouverte ? 'Masquer les mois' : 'Voir et modifier les douze mois'" @click="l.ouverte = !l.ouverte" />
                    <v-btn icon="mdi-delete-outline" size="small" variant="text" color="error" title="Retirer la ligne" @click="retirerLigne(l)" />
                  </td>
                </tr>
                <tr v-if="l.ouverte" class="eb-detail">
                  <td colspan="8">
                    <div class="eb-mois">
                      <div v-for="(m, i) in MOIS_COURTS" :key="m" class="eb-mois__case">
                        <label>{{ m }}</label>
                        <input type="number" min="0" step="0.01" :value="l.mensuel[i]" @change="(e: any) => changerMois(l, i, e.target.value)">
                      </div>
                    </div>
                    <div class="eb-detail__bas">
                      <span>S1 : <strong>{{ fmtMontant(regrouper(l.mensuel, 'SEMESTRIEL')[0]) }}</strong></span>
                      <span>S2 : <strong>{{ fmtMontant(regrouper(l.mensuel, 'SEMESTRIEL')[1]) }}</strong></span>
                      <span>Année : <strong>{{ fmtMontant(total(l.mensuel)) }}</strong></span>
                      <span v-if="l.realiseReference">Réalisé de référence : <strong>{{ fmtMontant(l.realiseReference) }}</strong></span>
                    </div>
                    <v-text-field v-model="l.commentaire" label="Hypothèse / base de calcul" placeholder="Ex. 2 véhicules × 120 l/mois × 1,6 USD" density="compact" hide-details class="mt-2" />
                  </td>
                </tr>
              </template>
              <tr v-if="!lignesPar(s).length">
                <td colspan="8" class="eb-vide">Aucune ligne de {{ SECTIONS[s].label.toLowerCase() }}.</td>
              </tr>
            </tbody>
            <tfoot v-if="lignesPar(s).length">
              <tr>
                <td>Total {{ SECTIONS[s].label.toLowerCase() }}</td>
                <td class="eb-col-num eb-montant">{{ fmtMontant(totalSection(s)) }}</td>
                <td />
                <td v-for="(v, i) in regrouper(mensuelSection(s), 'TRIMESTRIEL')" :key="i" class="eb-col-num eb-montant">{{ fmtMontant(v) }}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
        <div class="pa-3">
          <v-btn variant="text" color="primary" prepend-icon="mdi-plus" @click="ajouterLigne(s)">Ajouter une ligne</v-btn>
        </div>
      </v-card>

      <!-- Résultat et actions -->
      <v-card class="classroom-card pa-4 mb-4 eb-resultat">
        <div>
          <span class="eb-resultat__label">Résultat prévisionnel (produits − charges)</span>
          <span class="eb-resultat__valeur" :class="resultat >= 0 ? 'text-success' : 'text-error'">{{ fmtMontant(resultat) }} USD</span>
        </div>
        <div>
          <span class="eb-resultat__label">Investissements prévus</span>
          <span class="eb-resultat__valeur">{{ fmtMontant(totalSection('INVESTISSEMENTS')) }} USD</span>
        </div>
        <div class="eb-resultat__actions">
          <v-btn variant="text" @click="emit('annule')">Annuler</v-btn>
          <v-btn color="primary" :loading="enregistrement" prepend-icon="mdi-content-save-outline" @click="enregistrer">Enregistrer le brouillon</v-btn>
        </div>
      </v-card>
    </template>

    <!-- Dialogue IA -->
    <v-dialog v-model="dialogueIa" max-width="860" scrollable>
      <v-card>
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-robot-outline" color="purple" class="mr-2" /> Proposition de budget {{ entete.exercice }}
        </v-card-title>
        <v-card-text>
          <p class="text-body-2 mb-3">
            L’IA étudie le réalisé du grand livre (exercice précédent, ou à défaut l’exercice en cours annualisé) et propose
            un budget ligne par ligne, ventilé par mois et justifié. <strong>Rien n’est enregistré</strong> : vous relisez et
            corrigez avant d’enregistrer.
          </p>
          <v-textarea v-model="hypotheses" label="Hypothèses de la direction (facultatif)" placeholder="Ex. +10 % de ventes, recrutement d’un comptable en mars, achat d’un véhicule au 2e semestre" rows="2" auto-grow />
          <v-btn color="purple" :loading="propositionEnCours" prepend-icon="mdi-creation" @click="proposer">Établir la proposition</v-btn>

          <template v-if="proposition">
            <v-divider class="my-4" />
            <div class="d-flex align-center flex-wrap ga-2 mb-2">
              <v-chip :color="proposition.source === 'IA' ? 'purple' : 'blue-grey'" size="small" variant="tonal">
                {{ proposition.source === 'IA' ? 'Proposée par l’IA' : 'Calculée localement' }}
              </v-chip>
              <span class="text-caption text-medium-emphasis">Référence : {{ proposition.periodeReference }}</span>
            </div>
            <p class="text-body-2">{{ proposition.synthese }}</p>
            <v-alert v-for="(a, i) in proposition.avertissements" :key="i" type="info" variant="tonal" density="compact" class="mb-2">{{ a }}</v-alert>
            <v-table v-if="proposition.lignes.length" density="compact" class="mt-2">
              <thead>
                <tr><th>Compte</th><th>Section</th><th class="text-end">Réalisé de référence</th><th class="text-end">Proposé</th><th>Justification</th></tr>
              </thead>
              <tbody>
                <tr v-for="l in proposition.lignes" :key="l.compteNumero">
                  <td>{{ l.compteNumero }} — {{ l.compteLibelle }}</td>
                  <td>{{ libelleSection(l.section) }}</td>
                  <td class="text-end">{{ fmtMontant(l.realiseReference) }}</td>
                  <td class="text-end font-weight-medium">{{ fmtMontant(l.montantAnnuel) }}</td>
                  <td class="text-caption">{{ l.justification }}</td>
                </tr>
              </tbody>
            </v-table>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="dialogueIa = false">Fermer</v-btn>
          <v-btn color="primary" :disabled="!proposition?.lignes?.length" prepend-icon="mdi-check" @click="utiliserProposition">
            Utiliser cette proposition
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.eb-entete { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
.eb-entete__titre { flex: 1 1 320px; }
.eb-entete__exercice { flex: 0 0 140px; }
.eb-section-tete { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; border-left: 4px solid; border-bottom: 1px solid #eef0f3; gap: 12px; flex-wrap: wrap; }
.eb-section-tete__titre { font-weight: 700; font-size: 1rem; margin-right: 10px; }
.eb-section-tete__sens { font-size: 0.78rem; color: #6b7280; }
.eb-section-tete__total { font-weight: 700; font-size: 1.05rem; white-space: nowrap; }
.eb-table-wrap { overflow-x: auto; }
.eb-table { width: 100%; border-collapse: collapse; min-width: 900px; }
.eb-table th { font-size: 0.72rem; text-transform: uppercase; color: #6b7280; font-weight: 600; text-align: left; padding: 8px 10px; background: #fafbfc; }
.eb-table td { padding: 6px 10px; border-top: 1px solid #f1f2f4; vertical-align: top; }
.eb-table tfoot td { font-weight: 700; background: #fafbfc; }
.eb-col-compte { min-width: 300px; }
.eb-col-num { text-align: right; white-space: nowrap; width: 110px; }
.eb-col-mode { width: 170px; }
.eb-col-act { width: 92px; white-space: nowrap; text-align: right; }
.eb-montant { font-variant-numeric: tabular-nums; padding-top: 14px !important; }
.eb-vide { color: #9ca3af; font-size: 0.85rem; text-align: center; padding: 14px !important; }
.eb-ia-note { font-size: 0.72rem; color: #7c3aed; margin-top: 4px; }
.eb-detail td { background: #fbfbff; }
.eb-mois { display: grid; grid-template-columns: repeat(12, minmax(70px, 1fr)); gap: 6px; }
.eb-mois__case { display: flex; flex-direction: column; }
.eb-mois__case label { font-size: 0.7rem; color: #6b7280; }
.eb-mois__case input { border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 6px; font-size: 0.82rem; text-align: right; width: 100%; background: #fff; }
.eb-detail__bas { display: flex; gap: 18px; flex-wrap: wrap; font-size: 0.8rem; color: #4b5563; margin-top: 8px; }
.eb-resultat { display: flex; gap: 24px; align-items: center; flex-wrap: wrap; }
.eb-resultat__label { display: block; font-size: 0.75rem; color: #6b7280; }
.eb-resultat__valeur { font-size: 1.15rem; font-weight: 700; }
.eb-resultat__actions { margin-left: auto; display: flex; gap: 8px; }
@media (max-width: 900px) {
  .eb-mois { grid-template-columns: repeat(4, minmax(70px, 1fr)); }
}
</style>
