<script setup lang="ts">
// Inventaire physique (audit SYSCOHADA du 10/11/2026, C-22) : ouverture, comptage ligne par ligne, validation par une
// autre personne (comptabilité) et procès-verbal imprimable. Sert aux stocks (ajustement comptable des écarts au coût
// moyen) et aux immobilisations (présence, état, localisation).
const props = defineProps<{ type: 'STOCK' | 'IMMOBILISATIONS' }>()

interface Ligne {
  id: number
  objetId: number
  code: string
  libelle: string
  quantiteTheorique: number | null
  quantiteComptee: number | null
  coutUnitaire: number | null
  ecartValeur: number | null
  present: boolean | null
  etat: string | null
  localisationAttendue: string | null
  localisationConstatee: string | null
  commentaire: string | null
  mouvement: string | null
}
interface Inventaire {
  id: number
  reference: string
  entrepot: string | null
  dateInventaire: string
  statut: string
  observations: string | null
  creePar: string | null
  creeLe: string
  validePar: string | null
  valideLe: string | null
  lignes: Ligne[]
}

const api = useApi()
const dialogues = useDialogues()
const auth = useAuthStore()
const base = computed(() => (props.type === 'STOCK' ? '/logistique/inventaires' : '/patrimoine/inventaires'))
const peutValider = computed(() => auth.hasAnyRole(['COMPTABLE', 'DFIN']))
const peutSaisir = computed(() => auth.hasAnyRole(props.type === 'STOCK'
  ? ['LOGISTIQUE', 'COMPTABLE', 'DFIN']
  : ['GEST_PATRIMOINE', 'LOGISTIQUE', 'COMPTABLE', 'DFIN']))
// La validation relève de la fonction financière : elle passe par la comptabilité.
const urlValidation = computed(() => (props.type === 'STOCK'
  ? '/comptabilite/inventaires/stock' : '/comptabilite/inventaires/immobilisations'))

const liste = ref<Inventaire[]>([])
const courant = ref<Inventaire | null>(null)
const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')
const form = reactive({ entrepotId: null as number | null, dateInventaire: new Date().toISOString().slice(0, 10), observations: '' })

const ETATS = [
  { title: 'Bon', value: 'BON' }, { title: 'Moyen', value: 'MOYEN' },
  { title: 'Mauvais', value: 'MAUVAIS' }, { title: "Hors d'usage", value: 'HORS_USAGE' },
]

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    liste.value = await api<Inventaire[]>(base.value)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les inventaires.')
  } finally {
    loading.value = false
  }
}

async function ouvrir(id: number) {
  erreur.value = ''
  try {
    courant.value = await api<Inventaire>(`${base.value}/${id}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'ouvrir l'inventaire.")
  }
}

async function creer() {
  envoi.value = true
  erreur.value = ''
  try {
    courant.value = await api<Inventaire>(base.value, { method: 'POST', body: { ...form } })
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'ouvrir l'inventaire.")
  } finally {
    envoi.value = false
  }
}

async function enregistrerLigne(l: Ligne) {
  if (!courant.value) return
  erreur.value = ''
  try {
    courant.value = await api<Inventaire>(`${base.value}/${courant.value.id}/lignes/${l.id}`, {
      method: 'PUT',
      body: {
        quantiteComptee: l.quantiteComptee === null || (l.quantiteComptee as any) === '' ? null : Number(l.quantiteComptee),
        coutUnitaire: l.coutUnitaire,
        present: l.present,
        etat: l.etat,
        localisationConstatee: l.localisationConstatee,
        commentaire: l.commentaire,
      },
    })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  }
}

async function valider() {
  if (!courant.value) return
  const confirme = await dialogues.confirmer({
    titre: "Valider l'inventaire ?",
    message: props.type === 'STOCK'
      ? 'Chaque écart sera passé en mouvement de stock et en comptabilité, au coût moyen.'
      : 'Le procès-verbal sera figé.',
    bouton: "Valider l'inventaire",
    nature: 'attention',
    icone: 'mdi-clipboard-check-outline',
  })
  if (!confirme) return
  envoi.value = true
  erreur.value = ''
  try {
    courant.value = await api<Inventaire>(`${urlValidation.value}/${courant.value.id}/valider`, { method: 'POST' })
    succes.value = 'Inventaire validé.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Validation impossible.')
  } finally {
    envoi.value = false
  }
}

async function annuler() {
  if (!courant.value) return
  const confirme = await dialogues.confirmer({
    titre: 'Abandonner cet inventaire ?',
    message: "L'inventaire en cours sera annulé.",
    bouton: "Abandonner l'inventaire",
    abandon: 'Retour',
    nature: 'danger',
    icone: 'mdi-cancel',
  })
  if (!confirme) return
  try {
    await api(`${base.value}/${courant.value.id}/annuler`, { method: 'POST' })
    courant.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Abandon impossible.')
  }
}

function imprimer() {
  window.print()
}

const enCours = computed(() => courant.value?.statut === 'EN_COURS')
const totalEcarts = computed(() => (courant.value?.lignes ?? []).reduce((t, l) => t + (l.ecartValeur ?? 0), 0))
const absents = computed(() => (courant.value?.lignes ?? []).filter(l => l.present === false))

onMounted(charger)

const fmt = (v: number | null) => (v === null || v === undefined ? '' : new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(v))
const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '')
</script>

<template>
  <div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print">{{ succes }}</v-alert>

    <v-card v-if="peutSaisir" class="classroom-card pa-4 mb-4 no-print">
      <div class="text-subtitle-1 font-weight-bold mb-2">Ouvrir un inventaire</div>
      <div class="d-flex flex-wrap ga-3 align-center">
        <LogistiqueSelecteurEntrepot v-if="type === 'STOCK'" v-model="form.entrepotId" style="min-width: 240px" />
        <v-text-field v-model="form.dateInventaire" type="date" label="Date du comptage" variant="outlined"
                      density="comfortable" hide-details style="max-width: 200px" />
        <v-text-field v-model="form.observations" label="Observations" variant="outlined" density="comfortable"
                      hide-details style="min-width: 240px" />
        <v-btn color="primary" :loading="envoi" :disabled="type === 'STOCK' && !form.entrepotId" @click="creer">
          Ouvrir
        </v-btn>
      </div>
    </v-card>

    <v-card class="classroom-card mb-4 no-print">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          ...(type === 'STOCK' ? [{ title: 'Entrepôt', key: 'entrepot' }] : []),
          { title: 'Date', key: 'dateInventaire' },
          { title: 'Statut', key: 'statut' },
          { title: 'Ouvert par', key: 'creePar' },
          { title: 'Validé par', key: 'validePar' },
        ]"
        :items="liste"
        :loading="loading"
        items-per-page="10"
        no-data-text="Aucun inventaire."
        @click:row="(_e: any, { item }: any) => ouvrir(item.id)"
      >
        <template #item.dateInventaire="{ item }">{{ fmtDate(item.dateInventaire) }}</template>
        <template #item.statut="{ item }">
          <v-chip size="small" :color="item.statut === 'VALIDE' ? 'success' : item.statut === 'EN_COURS' ? 'warning' : 'grey'">
            {{ item.statut }}
          </v-chip>
        </template>
      </v-data-table>
    </v-card>

    <!-- ── Inventaire ouvert / procès-verbal ──────────────────────────── -->
    <v-card v-if="courant" class="classroom-card pa-4 pv">
      <div class="d-flex flex-wrap justify-space-between align-center mb-3">
        <div>
          <div class="text-h6 font-weight-bold">
            Procès-verbal d'inventaire {{ type === 'STOCK' ? 'des stocks' : 'du patrimoine' }} — {{ courant.reference }}
          </div>
          <div class="text-body-2">
            <span v-if="courant.entrepot">Entrepôt {{ courant.entrepot }} — </span>
            comptage du {{ fmtDate(courant.dateInventaire) }} — statut {{ courant.statut }}
          </div>
          <div v-if="courant.observations" class="text-body-2">{{ courant.observations }}</div>
        </div>
        <div class="d-flex flex-wrap ga-2 no-print">
          <v-btn variant="outlined" prepend-icon="mdi-printer" @click="imprimer">Imprimer</v-btn>
          <v-btn v-if="enCours && peutValider" color="success" :loading="envoi" @click="valider">Valider</v-btn>
          <v-btn v-if="enCours && peutSaisir" variant="text" color="error" @click="annuler">Abandonner</v-btn>
        </div>
      </div>

      <div class="tableau">
        <table v-if="type === 'STOCK'">
          <thead>
            <tr><th>Article</th><th class="nb">Théorique</th><th class="nb">Compté</th><th class="nb">Écart</th>
              <th class="nb">Coût unitaire</th><th class="nb">Écart en valeur</th><th>Commentaire</th><th>Mouvement</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in courant.lignes" :key="l.id">
              <td>{{ l.code }} — {{ l.libelle }}</td>
              <td class="nb">{{ fmt(l.quantiteTheorique) }}</td>
              <td class="nb">
                <input v-if="enCours && peutSaisir" v-model="l.quantiteComptee" type="number" min="0" step="0.001"
                       class="saisie" @change="enregistrerLigne(l)">
                <span v-else>{{ fmt(l.quantiteComptee) }}</span>
              </td>
              <td class="nb">{{ l.quantiteComptee !== null ? fmt(Number(l.quantiteComptee) - (l.quantiteTheorique ?? 0)) : '' }}</td>
              <td class="nb">{{ fmt(l.coutUnitaire) }}</td>
              <td class="nb">{{ fmt(l.ecartValeur) }}</td>
              <td>
                <input v-if="enCours && peutSaisir" v-model="l.commentaire" class="saisie large" @change="enregistrerLigne(l)">
                <span v-else>{{ l.commentaire }}</span>
              </td>
              <td>{{ l.mouvement }}</td>
            </tr>
            <tr class="total"><td colspan="5">Total des écarts en valeur</td><td class="nb">{{ fmt(totalEcarts) }}</td><td colspan="2" /></tr>
          </tbody>
        </table>

        <table v-else>
          <thead>
            <tr><th>Bien</th><th>Présent</th><th>État</th><th>Localisation attendue</th><th>Constatée</th><th>Commentaire</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in courant.lignes" :key="l.id">
              <td>{{ l.code }} — {{ l.libelle }}</td>
              <td>
                <select v-if="enCours && peutSaisir" v-model="l.present" class="saisie" @change="enregistrerLigne(l)">
                  <option :value="null">—</option><option :value="true">Présent</option><option :value="false">Absent</option>
                </select>
                <span v-else>{{ l.present === null ? '' : l.present ? 'Présent' : 'Absent' }}</span>
              </td>
              <td>
                <select v-if="enCours && peutSaisir" v-model="l.etat" class="saisie" @change="enregistrerLigne(l)">
                  <option :value="null">—</option>
                  <option v-for="e in ETATS" :key="e.value" :value="e.value">{{ e.title }}</option>
                </select>
                <span v-else>{{ ETATS.find(e => e.value === l.etat)?.title }}</span>
              </td>
              <td>{{ l.localisationAttendue }}</td>
              <td>
                <input v-if="enCours && peutSaisir" v-model="l.localisationConstatee" class="saisie" @change="enregistrerLigne(l)">
                <span v-else>{{ l.localisationConstatee }}</span>
              </td>
              <td>
                <input v-if="enCours && peutSaisir" v-model="l.commentaire" class="saisie large" @change="enregistrerLigne(l)">
                <span v-else>{{ l.commentaire }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="type === 'IMMOBILISATIONS' && absents.length" class="mt-3 text-body-2">
        <strong>Biens absents :</strong> {{ absents.map(a => a.code).join(', ') }} — à sortir du patrimoine
        (cession ou mise au rebut) depuis l'écran Immobilisations.
      </div>

      <div class="signatures">
        <div>Ouvert par : {{ courant.creePar }}<br>le {{ fmtDate(courant.creeLe) }}</div>
        <div>Validé par : {{ courant.validePar ?? '…………………………' }}<br>{{ courant.valideLe ? 'le ' + fmtDate(courant.valideLe) : '' }}</div>
      </div>
    </v-card>
  </div>
</template>

<style scoped>
.tableau {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.85rem;
}
th, td {
  padding: 4px 8px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
  text-align: left;
}
.nb {
  text-align: right;
  white-space: nowrap;
}
.saisie {
  border: 1px solid rgba(0, 0, 0, 0.2);
  border-radius: 4px;
  padding: 2px 6px;
  width: 100px;
}
.saisie.large {
  width: 180px;
}
.total td {
  font-weight: 600;
}
.signatures {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  margin-top: 32px;
}
@media print {
  .no-print {
    display: none !important;
  }
}
</style>
