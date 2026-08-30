<script setup lang="ts">
definePageMeta({ module: 'DRH_PAIE' })

interface Employe {
  id: number
  matricule: string
  nomComplet: string
  salaireBaseUsd: number
  nombreEnfants: number
}

interface Bulletin {
  id: number
  employeId: number
  employeMatricule: string
  employeNomComplet: string
  mois: number
  annee: number
  salaireNet: number
  netFc: number
  statut: 'BROUILLON' | 'VALIDE' | 'ANNULE'
  pieceReference: string | null
  pieceStatut: string | null
}

/** Écarts au droit congolais — consultatif, n'influence aucun montant. */
interface Conformite {
  avertissements: string[]
  smigMensuelUsd: number
  allocationFamilialeMinimumUsd: number
  plafondRetenuesUsd: number
  tauxHoraireUsd: number
  heureSup30Usd: number
  heureSup60Usd: number
  heureSup100Usd: number
}

interface Resultat {
  salaireBrut: number
  indemniteLogement: number
  indemniteTransport: number
  cnssOuvriere: number
  cnssPatronale: number
  onem: number
  inpp: number
  ipr: number
  salaireNet: number
  tauxChangeApplique: number
  netFc: number
  conformite: Conformite | null
}

const MOIS = [
  'Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin',
  'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre',
]

const api = useApi()
const auth = useAuthStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())

const loading = ref(false)
const saving = ref(false)
const clôturant = ref(false)
const exportEnCours = ref(false)
const erreur = ref('')
const succes = ref('')
const bulletins = ref<Bulletin[]>([])
const employes = ref<Employe[]>([])
const dialog = ref(false)
const resultat = ref<Resultat | null>(null)
const simulating = ref(false)
// L'IPR et le net en FC dependent du taux du jour (ConversionDeviseService,
// reutilise tel quel — voir plan DRH) : sans taux, calculerIpr renvoie 0 par
// securite plutot que de planter, ce qui sous-evaluerait silencieusement
// l'impot si on laissait creer un bulletin dans cet etat.
const tauxChange = ref(0)

const formVide = () => ({
  employeId: null as number | null,
  mois: mois.value,
  annee: annee.value,
  salaireBaseUsd: null as number | null,
  presencePct: 100,
  conge: 0, heuresSupplementaires: 0, allocationFamiliale: 0,
  primeDiplome: 0, primeAnciennete: 0, primeRendement: 0,
  avanceSalaire: 0, pret: 0,
  datePaiement: new Date().toISOString().slice(0, 10),
})
const form = reactive(formVide())

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    // La liste des employés relève d'un autre module (DRH_PERSONNEL) et ne
    // sert qu'au formulaire de création : la demander en consultation seule
    // renvoyait un 403 à un utilisateur pourtant légitimement sur la page,
    // qui voyait « Accès refusé » sur un écran qu'il a le droit de lire.
    const [b, e, taux] = await Promise.all([
      api<Bulletin[]>('/drh/bulletins', { params: { mois: mois.value, annee: annee.value } }),
      !canWrite.value || employes.value.length
        ? Promise.resolve(employes.value)
        : api<Employe[]>('/drh/employes'),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    bulletins.value = b
    employes.value = e
    tauxChange.value = taux.taux || 0
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les bulletins.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)
watch([mois, annee], charger)

function onEmployeChange() {
  const emp = employes.value.find(e => e.id === form.employeId)
  if (emp) form.salaireBaseUsd = emp.salaireBaseUsd
  resultat.value = null
}

function ouvrirAjout() {
  Object.assign(form, formVide())
  resultat.value = null
  erreur.value = ''
  dialog.value = true
}

function corpsRequete() {
  return {
    employeId: form.employeId,
    mois: form.mois,
    annee: form.annee,
    salaireBaseUsd: form.salaireBaseUsd,
    presencePct: form.presencePct,
    conge: form.conge, heuresSupplementaires: form.heuresSupplementaires,
    allocationFamiliale: form.allocationFamiliale, primeDiplome: form.primeDiplome,
    primeAnciennete: form.primeAnciennete, primeRendement: form.primeRendement,
    avanceSalaire: form.avanceSalaire, pret: form.pret,
    datePaiement: form.datePaiement,
  }
}

async function simuler() {
  if (!form.employeId || form.salaireBaseUsd === null) {
    erreur.value = 'Sélectionnez un employé.'
    return
  }
  simulating.value = true
  erreur.value = ''
  try {
    resultat.value = await api<Resultat>('/drh/bulletins/simuler', { method: 'POST', body: corpsRequete() })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la simulation.')
  } finally {
    simulating.value = false
  }
}

async function enregistrer() {
  if (!form.employeId || form.salaireBaseUsd === null) {
    erreur.value = 'Sélectionnez un employé.'
    return
  }
  if (!(tauxChange.value > 0)) {
    erreur.value = "Aucun taux de change n'est défini : l'IPR et le net en francs congolais seraient "
      + "calculés à zéro. Demandez à un administrateur d'enregistrer le taux du jour avant de créer ce bulletin."
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/drh/bulletins', { method: 'POST', body: corpsRequete() })
    succes.value = 'Bulletin créé en brouillon.'
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function action(b: Bulletin, verbe: 'valider' | 'devalider' | 'annuler') {
  saving.value = true
  erreur.value = ''
  try {
    await api(`/drh/bulletins/${b.id}/${verbe}`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'opération.")
  } finally {
    saving.value = false
  }
}

async function cloturer() {
  clôturant.value = true
  erreur.value = ''
  try {
    const res = await api<Bulletin[]>('/drh/bulletins/cloturer', { method: 'POST', params: { mois: mois.value, annee: annee.value } })
    succes.value = `${res.length} bulletin(s) clôturé(s) — pièces BROUILLON générées, à comptabiliser dans Pièces comptables.`
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la clôture.')
  } finally {
    clôturant.value = false
  }
}

async function exporterExcel() {
  exportEnCours.value = true
  erreur.value = ''
  try {
    await telechargerFichier(api, `/drh/bulletins/export?mois=${mois.value}&annee=${annee.value}`,
      `DEBOURS_MBSC_${String(mois.value).padStart(2, '0')}-${annee.value}.xlsx`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le classeur.')
  } finally {
    exportEnCours.value = false
  }
}

const statutMeta: Record<string, { label: string; bg: string; color: string }> = {
  BROUILLON: { label: 'Brouillon', bg: '#f3f4f6', color: '#6b7280' },
  VALIDE: { label: 'Validé', bg: '#dbeafe', color: '#1d4ed8' },
  ANNULE: { label: 'Annulé', bg: '#fee2e2', color: '#b91c1c' },
}

const fmtUsd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v || 0)
const fmtFc = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v || 0) + ' FC'

const validables = computed(() => bulletins.value.filter(b => b.statut === 'VALIDE').length)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Bulletins de paie</h1>
        <p class="page-sub">Calcul du salaire net, cotisations et IPR — clôture génère une pièce comptable par employé</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn color="success" variant="tonal" rounded="lg" prepend-icon="mdi-file-excel-outline"
          :loading="exportEnCours" :disabled="!bulletins.length" @click="exporterExcel">
          DEBOURS MBSC
        </v-btn>
        <v-btn v-if="canWrite" color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-lock-check-outline"
          :loading="clôturant" :disabled="validables === 0" @click="cloturer">
          Clôturer la période ({{ validables }})
        </v-btn>
        <v-btn v-if="canWrite" color="success" variant="flat" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirAjout">
          Nouveau bulletin
        </v-btn>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="!loading && !(tauxChange > 0)" type="warning" variant="tonal" class="mb-4">
      Aucun taux de change n'est défini : l'IPR et le net en FC seraient calculés à zéro. Demandez à un
      administrateur d'enregistrer le taux du jour avant de créer un bulletin.
    </v-alert>

    <div class="d-flex ga-3 mb-4">
      <v-select v-model="mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined"
        density="comfortable" rounded="lg" hide-details style="max-width: 200px" />
      <v-text-field v-model.number="annee" type="number" label="Année" variant="outlined" density="comfortable"
        rounded="lg" hide-details style="max-width: 140px" />
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Matricule', key: 'employeMatricule' },
          { title: 'Employé', key: 'employeNomComplet' },
          { title: 'Salaire net (USD)', key: 'salaireNet', align: 'end' },
          { title: 'Salaire net (FC)', key: 'netFc', align: 'end' },
          { title: 'Statut', key: 'statut' },
          { title: 'Pièce', key: 'pieceReference' },
          { title: 'Actions', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="bulletins"
        :loading="loading"
        items-per-page="25"
      >
        <template #item.salaireNet="{ item }">{{ fmtUsd(item.salaireNet) }}</template>
        <template #item.netFc="{ item }">{{ fmtFc(item.netFc) }}</template>
        <template #item.statut="{ item }">
          <span class="chip-soft" :style="{ background: statutMeta[item.statut]?.bg, color: statutMeta[item.statut]?.color }">
            {{ statutMeta[item.statut]?.label ?? item.statut }}
          </span>
        </template>
        <template #item.pieceReference="{ item }">{{ item.pieceReference ?? '—' }}</template>
        <template #item.actions="{ item }">
          <v-btn size="small" variant="text" icon="mdi-eye-outline" title="Détail" :to="`/drh/bulletins/${item.id}`" />
          <template v-if="canWrite && !item.pieceReference">
            <v-btn v-if="item.statut === 'BROUILLON'" size="small" variant="text" color="success"
              icon="mdi-check-circle-outline" title="Valider" @click="action(item, 'valider')" />
            <v-btn v-if="item.statut === 'VALIDE'" size="small" variant="text" icon="mdi-undo"
              title="Repasser en brouillon" @click="action(item, 'devalider')" />
            <v-btn v-if="item.statut !== 'ANNULE'" size="small" variant="text" color="error"
              icon="mdi-close-circle-outline" title="Annuler" @click="action(item, 'annuler')" />
          </template>
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">Aucun bulletin pour cette période.</div>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialog" max-width="820" scrollable>
      <v-card>
        <v-card-title>Nouveau bulletin de paie</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <v-row>
            <v-col cols="12" md="6">
              <v-select v-model="form.employeId" :items="employes.map(e => ({ title: `${e.matricule} — ${e.nomComplet}`, value: e.id }))"
                label="Employé *" variant="outlined" density="comfortable" @update:model-value="onEmployeChange" />
            </v-col>
            <v-col cols="12" md="3"><v-text-field v-model="form.datePaiement" type="date" label="Date de paiement" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="3"><v-text-field v-model.number="form.presencePct" type="number" suffix="%" label="Présence *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.salaireBaseUsd" type="number" label="Salaire de base (USD) *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.conge" type="number" label="Congé" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.heuresSupplementaires" type="number" label="Heures supplémentaires" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4">
              <v-text-field v-model.number="form.allocationFamiliale" type="number" label="Allocation familiale"
                variant="outlined" density="comfortable"
                :hint="resultat?.conformite && resultat.conformite.allocationFamilialeMinimumUsd > 0
                  ? `Minimum légal : ${fmtUsd(resultat.conformite.allocationFamilialeMinimumUsd)}`
                  : undefined"
                :persistent-hint="!!resultat?.conformite && resultat.conformite.allocationFamilialeMinimumUsd > 0" />
            </v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.primeDiplome" type="number" label="Prime diplôme" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.primeAnciennete" type="number" label="Prime ancienneté" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="4"><v-text-field v-model.number="form.primeRendement" type="number" label="Prime rendement" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model.number="form.avanceSalaire" type="number" label="Avance sur salaire à déduire" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" md="6"><v-text-field v-model.number="form.pret" type="number" label="Prêt à déduire" variant="outlined" density="comfortable" /></v-col>
          </v-row>

          <v-btn variant="tonal" color="primary" prepend-icon="mdi-calculator-variant-outline" :loading="simulating" class="mb-3" @click="simuler">
            Simuler le calcul
          </v-btn>

          <v-card v-if="resultat" variant="tonal" color="teal" class="pa-4 mb-2">
            <v-row dense>
              <v-col cols="6" md="4"><span class="calc-label">Salaire brut</span><br><strong>{{ fmtUsd(resultat.salaireBrut) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">Indemnité logement</span><br><strong>{{ fmtUsd(resultat.indemniteLogement) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">Indemnité transport</span><br><strong>{{ fmtUsd(resultat.indemniteTransport) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">CNSS ouvrière</span><br><strong>{{ fmtUsd(resultat.cnssOuvriere) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">CNSS patronale</span><br><strong>{{ fmtUsd(resultat.cnssPatronale) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">ONEM</span><br><strong>{{ fmtUsd(resultat.onem) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">INPP</span><br><strong>{{ fmtUsd(resultat.inpp) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">IPR</span><br><strong>{{ fmtUsd(resultat.ipr) }}</strong></v-col>
              <v-col cols="6" md="4"><span class="calc-label">Taux de change appliqué</span><br><strong>{{ resultat.tauxChangeApplique }}</strong></v-col>
              <v-col cols="12" md="6"><span class="calc-label">Salaire net (USD)</span><br><strong class="text-h6">{{ fmtUsd(resultat.salaireNet) }}</strong></v-col>
              <v-col cols="12" md="6"><span class="calc-label">Salaire net (FC)</span><br><strong class="text-h6">{{ fmtFc(resultat.netFc) }}</strong></v-col>
            </v-row>
          </v-card>

          <!-- Conformité RDC : purement consultatif, n'empêche jamais
               l'enregistrement et ne corrige aucun montant saisi. -->
          <template v-if="resultat?.conformite">
            <v-alert v-if="resultat.conformite.avertissements.length" type="warning" variant="tonal"
              rounded="lg" density="comfortable" class="mb-3">
              <div class="text-subtitle-2 mb-1">Écarts au droit du travail congolais</div>
              <ul class="conformite-liste">
                <li v-for="(a, i) in resultat.conformite.avertissements" :key="i">{{ a }}</li>
              </ul>
              <div class="text-caption mt-2">
                Signalement indicatif : vous pouvez enregistrer ce bulletin tel quel.
              </div>
            </v-alert>

            <v-card variant="tonal" class="pa-3 mb-2">
              <div class="text-caption font-weight-bold mb-2">Références légales (indicatif)</div>
              <v-row dense>
                <v-col cols="6" md="3"><span class="calc-label">SMIG mensuel</span><br>{{ fmtUsd(resultat.conformite.smigMensuelUsd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Plafond retenues</span><br>{{ fmtUsd(resultat.conformite.plafondRetenuesUsd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Alloc. familiale min.</span><br>{{ fmtUsd(resultat.conformite.allocationFamilialeMinimumUsd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Taux horaire</span><br>{{ fmtUsd(resultat.conformite.tauxHoraireUsd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Heure sup +30 %</span><br>{{ fmtUsd(resultat.conformite.heureSup30Usd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Heure sup +60 %</span><br>{{ fmtUsd(resultat.conformite.heureSup60Usd) }}</v-col>
                <v-col cols="6" md="3"><span class="calc-label">Repos / férié +100 %</span><br>{{ fmtUsd(resultat.conformite.heureSup100Usd) }}</v-col>
              </v-row>
              <div class="text-caption text-medium-emphasis mt-2">
                Le montant des heures supplémentaires reste saisi manuellement : ces taux servent à le vérifier.
              </div>
            </v-card>
          </template>
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.calc-label { font-size: 0.7rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.4px; }
.conformite-liste { margin: 0; padding-left: 18px; font-size: 0.82rem; }
.conformite-liste li + li { margin-top: 4px; }
</style>
