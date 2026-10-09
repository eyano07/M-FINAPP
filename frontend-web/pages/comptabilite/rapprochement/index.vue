<script setup lang="ts">
/**
 * Rapprochement bancaire : comptes de banque et de mobile money × mois de l'année. Un relevé se crée à
 * partir d'un fichier lu par l'IA (PDF, photo, Excel, CSV), relu avant enregistrement, ou se saisit à la
 * main. Voir RapprochementService côté serveur.
 */
definePageMeta({ module: 'RAPPROCHEMENT', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'CAISSIER'] })
useHead({ title: 'Rapprochement bancaire' })

interface Etablissement { id: number, nom: string, type: string, compteNumero: string, compteLibelle: string, devise: string }
interface Resume { id: number, etablissementId: number, mois: number, statut: 'EN_COURS' | 'VALIDE', soldeCloture: number, nombreLignes: number, nombreLignesNonPointees: number }
interface LigneSaisie { dateOperation: string, libelle: string, reference: string | null, entree: number, sortie: number }

const api = useApi()
const auth = useAuthStore()
const peutEcrire = computed(() => auth.hasAnyRole(['ADMIN', 'DFIN', 'COMPTABLE', 'CAISSIER']))

const annee = ref(new Date().getFullYear())
const etablissements = ref<Etablissement[]>([])
const releves = ref<Resume[]>([])
const chargement = ref(false)
const erreur = ref('')

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    const r = await api<{ etablissements: Etablissement[], releves: Resume[] }>('/rapprochements', { params: { annee: annee.value } })
    etablissements.value = r.etablissements
    releves.value = r.releves
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les rapprochements.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)
watch(annee, charger)

const MOIS = ['Janv.', 'Févr.', 'Mars', 'Avr.', 'Mai', 'Juin', 'Juil.', 'Août', 'Sept.', 'Oct.', 'Nov.', 'Déc.']
const releve = (etabId: number, mois: number) => releves.value.find(r => r.etablissementId === etabId && r.mois === mois)
const moisCourant = computed(() => (annee.value === new Date().getFullYear() ? new Date().getMonth() + 1 : 12))

// ---------------------------------------------------------------------------------------------------
// Nouveau relevé
// ---------------------------------------------------------------------------------------------------
const dialogue = ref(false)
const etape = ref<'choix' | 'relecture'>('choix')
const form = reactive({
  etablissementId: null as number | null,
  mois: new Date().getMonth() + 1,
  annee: new Date().getFullYear(),
  soldeOuverture: null as number | null,
  soldeCloture: null as number | null,
  source: '',
})
const lignes = ref<LigneSaisie[]>([])
const avertissements = ref<string[]>([])
const fichier = ref<File | null>(null)
const extraction = ref(false)
const enregistrement = ref(false)
const erreurDialogue = ref('')

const etabChoisi = computed(() => etablissements.value.find(e => e.id === form.etablissementId) || null)

function ouvrir(etabId?: number, mois?: number) {
  Object.assign(form, { etablissementId: etabId ?? null, mois: mois ?? moisCourant.value, annee: annee.value, soldeOuverture: null, soldeCloture: null, source: '' })
  lignes.value = []
  avertissements.value = []
  fichier.value = null
  erreurDialogue.value = ''
  etape.value = 'choix'
  dialogue.value = true
}

async function lireAvecIa() {
  if (!fichier.value || !etabChoisi.value) return
  extraction.value = true
  erreurDialogue.value = ''
  try {
    const donnees = new FormData()
    donnees.append('fichier', fichier.value)
    const r = await api<any>('/rapprochements/extraction', { method: 'POST', body: donnees, params: { devise: etabChoisi.value.devise } })
    lignes.value = r.lignes.map((l: any) => ({ ...l, entree: Number(l.entree), sortie: Number(l.sortie) }))
    form.soldeOuverture = r.soldeOuverture
    form.soldeCloture = r.soldeCloture
    form.source = r.source
    avertissements.value = r.avertissements || []
    if (r.periodeFin) {
      const fin = new Date(r.periodeFin)
      form.mois = fin.getMonth() + 1
      form.annee = fin.getFullYear()
    }
    etape.value = 'relecture'
  } catch (e) {
    erreurDialogue.value = messageErreurApi(e, "L'IA n'a pas pu lire ce relevé.")
  } finally {
    extraction.value = false
  }
}

function saisieManuelle() {
  lignes.value = [ligneVide()]
  form.source = 'Saisie manuelle'
  avertissements.value = []
  etape.value = 'relecture'
}

function ligneVide(): LigneSaisie {
  const j = `${form.annee}-${String(form.mois).padStart(2, '0')}-01`
  return { dateOperation: j, libelle: '', reference: null, entree: 0, sortie: 0 }
}

const totalEntrees = computed(() => lignes.value.reduce((s, l) => s + (Number(l.entree) || 0), 0))
const totalSorties = computed(() => lignes.value.reduce((s, l) => s + (Number(l.sortie) || 0), 0))
const ecartReleve = computed(() => Math.round(((Number(form.soldeOuverture) || 0) + totalEntrees.value - totalSorties.value - (Number(form.soldeCloture) || 0)) * 100) / 100)

async function enregistrer() {
  erreurDialogue.value = ''
  if (!form.etablissementId || form.soldeOuverture === null || form.soldeCloture === null) {
    erreurDialogue.value = 'Indiquez le compte et les soldes d’ouverture et de clôture du relevé.'
    return
  }
  const incompletes = lignes.value.filter(l => !l.libelle.trim() || (!(Number(l.entree) > 0) === !(Number(l.sortie) > 0)))
  if (incompletes.length) {
    erreurDialogue.value = `${incompletes.length} ligne(s) incomplète(s) : chaque opération a un libellé et soit une entrée, soit une sortie.`
    return
  }
  enregistrement.value = true
  try {
    const r = await api<any>('/rapprochements', {
      method: 'POST',
      body: {
        ...form,
        lignes: lignes.value.map(l => ({ ...l, entree: Number(l.entree) || 0, sortie: Number(l.sortie) || 0, reference: l.reference || null })),
      },
    })
    dialogue.value = false
    await navigateTo(`/comptabilite/rapprochement/${r.id}`)
  } catch (e) {
    erreurDialogue.value = messageErreurApi(e, 'Le relevé n’a pas pu être enregistré.')
  } finally {
    enregistrement.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Rapprochement bancaire</h1>
        <p class="page-sub">Relevés des banques et du mobile money pointés contre la comptabilité, mois par mois</p>
      </div>
      <div class="d-flex ga-2 align-center">
        <v-text-field v-model.number="annee" type="number" label="Année" density="compact" hide-details style="max-width: 110px" />
        <v-btn v-if="peutEcrire" color="primary" prepend-icon="mdi-file-upload-outline" height="40" @click="ouvrir()">Nouveau relevé</v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="chargement && !etablissements.length" type="table" />

    <v-card v-else class="classroom-card">
      <div class="rb-table-wrap">
        <table class="rb-table">
          <thead>
            <tr>
              <th>Compte</th>
              <th v-for="(m, i) in MOIS" :key="m" class="rb-mois" :class="{ 'rb-mois--courant': i + 1 === moisCourant }">{{ m }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in etablissements" :key="e.id">
              <td class="rb-compte">
                <strong>{{ e.nom }}</strong>
                <span>{{ e.type === 'BANQUE' ? 'Banque' : 'Mobile money' }} · {{ e.compteNumero }} · {{ e.devise }}</span>
              </td>
              <td v-for="(m, i) in MOIS" :key="m" class="rb-cell">
                <NuxtLink v-if="releve(e.id, i + 1)" :to="`/comptabilite/rapprochement/${releve(e.id, i + 1)!.id}`"
                  class="rb-etat" :class="releve(e.id, i + 1)!.statut === 'VALIDE' ? 'rb-etat--ok' : 'rb-etat--cours'"
                  :title="releve(e.id, i + 1)!.statut === 'VALIDE' ? `${m} : rapproché et validé` : `${m} : ${releve(e.id, i + 1)!.nombreLignesNonPointees} opération(s) à pointer`">
                  <v-icon :icon="releve(e.id, i + 1)!.statut === 'VALIDE' ? 'mdi-check-decagram' : 'mdi-progress-clock'" size="16" />
                  <span v-if="releve(e.id, i + 1)!.statut !== 'VALIDE'">{{ releve(e.id, i + 1)!.nombreLignesNonPointees }}</span>
                </NuxtLink>
                <button v-else-if="peutEcrire && i + 1 <= moisCourant" type="button" class="rb-etat rb-etat--vide"
                  :aria-label="`Créer le relevé ${m} ${annee} de ${e.nom}`" @click="ouvrir(e.id, i + 1)">
                  <v-icon icon="mdi-plus" size="14" />
                </button>
                <span v-else class="rb-etat rb-etat--futur">·</span>
              </td>
            </tr>
            <tr v-if="!etablissements.length">
              <td colspan="13" class="rb-vide">Aucun compte de banque ni de mobile money : créez-les dans Administration › Établissements.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="rb-legende">
        <span><v-icon icon="mdi-check-decagram" size="14" color="success" /> Rapproché et validé</span>
        <span><v-icon icon="mdi-progress-clock" size="14" color="warning" /> En cours (nombre d'opérations à pointer)</span>
      </p>
    </v-card>

    <!-- Nouveau relevé -->
    <v-dialog v-model="dialogue" max-width="1100" scrollable :persistent="extraction || enregistrement">
      <v-card>
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-file-upload-outline" class="mr-2" /> Nouveau relevé
          <v-spacer />
          <v-btn icon="mdi-close" variant="text" aria-label="Fermer" :disabled="extraction || enregistrement" @click="dialogue = false" />
        </v-card-title>
        <v-card-text>
          <v-alert v-if="erreurDialogue" type="error" variant="tonal" density="compact" class="mb-3">{{ erreurDialogue }}</v-alert>
          <v-row dense>
            <v-col cols="12" md="6">
              <v-select v-model="form.etablissementId" :items="etablissements" item-value="id"
                :item-title="(e: any) => `${e.nom} — ${e.compteNumero} (${e.devise})`" label="Compte *" />
            </v-col>
            <v-col cols="6" md="3">
              <v-select v-model="form.mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois *" />
            </v-col>
            <v-col cols="6" md="3">
              <v-text-field v-model.number="form.annee" type="number" label="Année *" />
            </v-col>
          </v-row>

          <template v-if="etape === 'choix'">
            <div class="rb-import">
              <v-file-input v-model="fichier" label="Relevé : PDF, photo, Excel ou CSV" accept=".pdf,.png,.jpg,.jpeg,.webp,.xlsx,.xls,.csv"
                prepend-icon="mdi-paperclip" show-size :disabled="extraction" hide-details class="mb-3" />
              <div class="d-flex flex-wrap ga-2">
                <v-btn color="purple" prepend-icon="mdi-robot-outline" :loading="extraction" :disabled="!fichier || !form.etablissementId" @click="lireAvecIa">
                  Lire le relevé avec l'IA
                </v-btn>
                <v-btn variant="text" :disabled="!form.etablissementId || extraction" @click="saisieManuelle">Saisir à la main</v-btn>
              </div>
              <p v-if="extraction" class="text-caption mt-2">Lecture en cours… un relevé de plusieurs pages peut prendre jusqu'à deux minutes.</p>
              <p class="text-caption text-medium-emphasis mt-3 mb-0">
                L'IA lit les soldes et chaque opération du relevé. Rien n'est enregistré avant votre relecture.
              </p>
            </div>
          </template>

          <template v-else>
            <v-alert v-for="(a, i) in avertissements" :key="i" type="warning" variant="tonal" density="compact" class="mb-2">{{ a }}</v-alert>
            <v-row dense class="mt-1">
              <v-col cols="6" md="3"><v-text-field v-model.number="form.soldeOuverture" type="number" :label="`Solde d'ouverture (${etabChoisi?.devise || ''}) *`" /></v-col>
              <v-col cols="6" md="3"><v-text-field v-model.number="form.soldeCloture" type="number" :label="`Solde de clôture (${etabChoisi?.devise || ''}) *`" /></v-col>
              <v-col cols="12" md="6" class="d-flex align-center">
                <span class="rb-controle" :class="ecartReleve === 0 ? 'rb-controle--ok' : 'rb-controle--ko'">
                  Ouverture + entrées − sorties − clôture = {{ fmtMontant(ecartReleve) }}
                  {{ ecartReleve === 0 ? '✓' : '— une opération manque ou est mal lue' }}
                </span>
              </v-col>
            </v-row>
            <div class="rb-table-wrap">
              <table class="rb-saisie">
                <thead>
                  <tr><th>Date</th><th>Libellé</th><th>Référence</th><th class="num">Entrée</th><th class="num">Sortie</th><th /></tr>
                </thead>
                <tbody>
                  <tr v-for="(l, i) in lignes" :key="i">
                    <td><input v-model="l.dateOperation" type="date" aria-label="Date de l'opération"></td>
                    <td><input v-model="l.libelle" type="text" aria-label="Libellé" class="rb-saisie__libelle"></td>
                    <td><input v-model="l.reference" type="text" aria-label="Référence"></td>
                    <td><input v-model.number="l.entree" type="number" min="0" step="0.01" class="num" aria-label="Entrée"></td>
                    <td><input v-model.number="l.sortie" type="number" min="0" step="0.01" class="num" aria-label="Sortie"></td>
                    <td><v-btn icon="mdi-delete-outline" size="small" variant="text" color="error" aria-label="Retirer la ligne" @click="lignes.splice(i, 1)" /></td>
                  </tr>
                </tbody>
                <tfoot>
                  <tr><td colspan="3">{{ lignes.length }} opération(s)</td><td class="num">{{ fmtMontant(totalEntrees) }}</td><td class="num">{{ fmtMontant(totalSorties) }}</td><td /></tr>
                </tfoot>
              </table>
            </div>
            <v-btn variant="text" prepend-icon="mdi-plus" class="mt-2" @click="lignes.push(ligneVide())">Ajouter une opération</v-btn>
          </template>
        </v-card-text>
        <v-card-actions v-if="etape === 'relecture'">
          <v-btn variant="text" @click="etape = 'choix'">Recommencer</v-btn>
          <v-spacer />
          <v-btn color="primary" variant="flat" :loading="enregistrement" prepend-icon="mdi-content-save-outline" @click="enregistrer">Enregistrer le relevé</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rb-table-wrap { overflow-x: auto; }
.rb-table { width: 100%; border-collapse: collapse; min-width: 860px; }
.rb-table th { font-size: 0.72rem; color: #4b5563; text-transform: uppercase; padding: 10px 6px; text-align: left; background: #f8fafc; }
.rb-table td { border-top: 1px solid #f1f2f4; padding: 8px 6px; }
.rb-mois { text-align: center !important; }
.rb-mois--courant { color: var(--color-primary) !important; }
.rb-compte strong { display: block; font-size: 0.88rem; }
.rb-compte span { font-size: 0.74rem; color: #4b5563; }
.rb-cell { text-align: center; }
.rb-etat { display: inline-flex; align-items: center; justify-content: center; gap: 3px; min-width: 40px; height: 30px; border-radius: 8px; font-size: 0.75rem; font-weight: 700; text-decoration: none; border: none; cursor: pointer; }
.rb-etat--ok { background: #dcfce7; color: #15803d; }
.rb-etat--cours { background: #fef3c7; color: #92400e; }
.rb-etat--vide { background: #f3f4f6; color: #6b7280; }
.rb-etat--vide:hover { background: var(--color-primary-light); color: var(--color-primary); }
.rb-etat--futur { color: #d1d5db; cursor: default; }
.rb-vide { text-align: center; color: #4b5563; padding: 24px !important; }
.rb-legende { display: flex; gap: 18px; flex-wrap: wrap; font-size: 0.75rem; color: #4b5563; padding: 10px 14px; margin: 0; }
.rb-import { border: 1px dashed #c7cdd6; border-radius: 12px; padding: 16px; margin-top: 4px; }
.rb-controle { font-size: 0.82rem; font-weight: 600; padding: 6px 10px; border-radius: 8px; }
.rb-controle--ok { background: #dcfce7; color: #166534; }
.rb-controle--ko { background: #fee2e2; color: #991b1b; }
.rb-saisie { width: 100%; border-collapse: collapse; min-width: 760px; font-size: 0.82rem; }
.rb-saisie th { font-size: 0.7rem; text-transform: uppercase; color: #4b5563; text-align: left; padding: 6px; background: #f8fafc; }
.rb-saisie td { padding: 3px 4px; border-top: 1px solid #f1f2f4; }
.rb-saisie input { width: 100%; border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 6px; font: inherit; background: #fff; }
.rb-saisie__libelle { min-width: 260px; }
.rb-saisie .num, .rb-saisie input.num { text-align: right; font-variant-numeric: tabular-nums; }
.rb-saisie tfoot td { font-weight: 700; background: #f8fafc; }
</style>
