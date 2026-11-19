<script setup lang="ts">
import QRCode from 'qrcode'

/**
 * Inventaire du patrimoine (19/11/2026) : biens relevés sur la fiche d'inventaire, un par unité, avec le code collé
 * dessus. Le responsable logistique saisit la valeur et la date de chaque bien : à partir de 100 USD, la fiche
 * d'amortissement SYSCOHADA est créée et l'acquisition comptabilisée (D 2xxx / C 1013) ; en dessous, le bien reste du
 * petit matériel. Les étiquettes (code + QR) s'impriment par lot.
 */
definePageMeta({ module: 'PATRIMOINE' })
useHead({ title: 'Inventaire du patrimoine' })

type Categorie = 'MOBILIER' | 'MATERIEL_BUREAU' | 'MATERIEL_INFORMATIQUE' | 'MATERIEL_INDUSTRIEL' | 'MATERIEL_TRANSPORT' | 'LOGICIEL' | 'BATIMENT' | 'AUTRE'
interface Bien {
  id: number, code: string, libelle: string, categorie: Categorie, dureeMois: number, localisation: string | null, etat: string | null,
  statut: 'A_VALORISER' | 'IMMOBILISE' | 'PETIT_MATERIEL', valeurUsd: number | null, dateAcquisition: string | null,
  dateMiseService: string | null, immobilisationId: number | null, immobilisationReference: string | null, ligneSource: number | null
}

const CATEGORIES: { value: Categorie, title: string, duree: number }[] = [
  { value: 'MOBILIER', title: 'Mobilier de bureau (2444)', duree: 120 },
  { value: 'MATERIEL_BUREAU', title: 'Matériel de bureau (2441)', duree: 60 },
  { value: 'MATERIEL_INFORMATIQUE', title: 'Matériel informatique (2442)', duree: 36 },
  { value: 'MATERIEL_INDUSTRIEL', title: 'Matériel industriel (2411)', duree: 60 },
  { value: 'MATERIEL_TRANSPORT', title: 'Matériel de transport (2451)', duree: 48 },
  { value: 'LOGICIEL', title: 'Logiciel (2131)', duree: 36 },
  { value: 'BATIMENT', title: 'Bâtiment (2311)', duree: 240 },
  { value: 'AUTRE', title: 'Autre (2441)', duree: 60 },
]
const STATUTS = {
  A_VALORISER: { titre: 'À valoriser', couleur: 'warning' },
  IMMOBILISE: { titre: 'Immobilisé', couleur: 'success' },
  PETIT_MATERIEL: { titre: 'Petit matériel', couleur: 'info' },
} as const
const SEUIL = 100

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const peutEcrire = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'GEST_PATRIMOINE', 'ADMIN']))
const biens = ref<Bien[]>([])
const chargement = ref(false)
const erreur = ref('')
const succes = ref('')
const recherche = ref('')
const filtreStatut = ref<string>('TOUS')
const filtreLieu = ref<string>('TOUS')
const selection = ref<number[]>([])

async function charger() {
  chargement.value = true
  try {
    biens.value = await api<Bien[]>('/patrimoine/inventaire')
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'inventaire.")
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

const lieux = computed(() => [...new Set(biens.value.map(b => b.localisation || '—'))].sort())
const filtres = computed(() => {
  const q = recherche.value.trim().toLowerCase()
  return biens.value.filter(b => (filtreStatut.value === 'TOUS' || b.statut === filtreStatut.value)
    && (filtreLieu.value === 'TOUS' || (b.localisation || '—') === filtreLieu.value)
    && (!q || `${b.code} ${b.libelle} ${b.localisation ?? ''}`.toLowerCase().includes(q)))
})
const compte = (s: string) => biens.value.filter(b => b.statut === s).length
const totalImmobilise = computed(() => biens.value.filter(b => b.statut === 'IMMOBILISE').reduce((t, b) => t + Number(b.valeurUsd || 0), 0))
const usd = (v: number | null) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(v) || 0)
const libCategorie = (c: Categorie) => CATEGORIES.find(x => x.value === c)?.title ?? c
const duree = (m: number) => (m % 12 === 0 ? `${m / 12} an${m > 12 ? 's' : ''}` : `${m} mois`)

// ── Valorisation ────────────────────────────────────────────────────────
const val = reactive({ ouvert: false, bien: null as Bien | null, valeurUsd: null as number | null, dateAcquisition: '', dateMiseService: '',
  categorie: 'MOBILIER' as Categorie, dureeMois: 120, fournisseur: '', numeroSerie: '', enCours: false, erreur: '', lot: [] as Bien[], fait: 0 })
function ouvrirValorisation(b: Bien, lot: Bien[] = []) {
  Object.assign(val, { ouvert: true, bien: b, valeurUsd: null, dateAcquisition: '', dateMiseService: '', categorie: b.categorie,
    dureeMois: b.dureeMois, fournisseur: '', numeroSerie: '', enCours: false, erreur: '', lot, fait: 0 })
}
/** Biens cochés encore à valoriser : même valeur unitaire, même date et même fiche pour chacun (ex. 103 chaises identiques). */
const lotAValoriser = computed(() => biens.value.filter(b => selection.value.includes(b.id) && b.statut === 'A_VALORISER'))
function ouvrirValorisationLot() {
  const lot = lotAValoriser.value
  if (lot.length) ouvrirValorisation(lot[0], lot)
}
watch(() => val.categorie, (c, avant) => {
  if (avant && c !== avant) val.dureeMois = CATEGORIES.find(x => x.value === c)?.duree ?? val.dureeMois
})
const sousSeuil = computed(() => val.valeurUsd != null && val.valeurUsd < SEUIL)
async function valoriser() {
  if (!val.bien) return
  val.erreur = ''
  val.enCours = true
  // Un bien = une fiche et une pièce : le lot est valorisé bien par bien, et s'arrête au premier refus (rien n'est perdu :
  // les biens déjà faits restent faits, les autres restent « à valoriser »).
  const cibles = val.lot.length ? val.lot : [val.bien]
  val.fait = 0
  try {
    for (const b of cibles) {
      await api(`/patrimoine/inventaire/${b.id}/valorisation`, { method: 'POST', body: {
        valeurUsd: val.valeurUsd, dateAcquisition: val.dateAcquisition, dateMiseService: val.dateMiseService || null,
        categorie: val.categorie, dureeMois: val.dureeMois, fournisseur: val.fournisseur || null,
        numeroSerie: cibles.length > 1 ? null : (val.numeroSerie || null),
      } })
      val.fait++
    }
    const quoi = cibles.length > 1 ? `${cibles.length} biens` : val.bien.code
    succes.value = sousSeuil.value
      ? `${quoi} : petit matériel (sous ${SEUIL} USD), suivi à l'inventaire sans écriture.`
      : `${quoi} : fiche d'amortissement créée et acquisition comptabilisée (contrepartie 1013)${cibles.length > 1 ? ', une pièce par bien' : ''}.`
    val.ouvert = false
    selection.value = []
  } catch (e) {
    val.erreur = (val.fait ? `${val.fait} bien(s) valorisé(s), puis arrêt : ` : '') + messageErreurApi(e, "La valorisation n'a pas été enregistrée.")
  } finally {
    val.enCours = false
    await charger()
  }
}

// ── Correction (avant valorisation) ─────────────────────────────────────
const cor = reactive({ ouvert: false, bien: null as Bien | null, libelle: '', categorie: 'MOBILIER' as Categorie, dureeMois: 120, localisation: '', etat: '' })
function ouvrirCorrection(b: Bien) {
  Object.assign(cor, { ouvert: true, bien: b, libelle: b.libelle, categorie: b.categorie, dureeMois: b.dureeMois, localisation: b.localisation ?? '', etat: b.etat ?? '' })
}
async function corriger() {
  if (!cor.bien) return
  try {
    await api(`/patrimoine/inventaire/${cor.bien.id}`, { method: 'PUT', body: { libelle: cor.libelle, categorie: cor.categorie,
      dureeMois: cor.dureeMois, localisation: cor.localisation || null, etat: cor.etat || null } })
    cor.ouvert = false
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e)
  }
}

// ── Étiquettes ──────────────────────────────────────────────────────────
const etiquettes = ref<{ code: string, libelle: string, lieu: string, qr: string }[]>([])
async function imprimerEtiquettes() {
  const choix = biens.value.filter(b => selection.value.includes(b.id))
  const liste = choix.length ? choix : filtres.value
  etiquettes.value = await Promise.all(liste.map(async b => ({
    code: b.code, libelle: b.libelle, lieu: b.localisation ?? '',
    qr: await QRCode.toDataURL(b.code, { margin: 0, width: 160, errorCorrectionLevel: 'M' }),
  })))
  await nextTick()
  document.documentElement.classList.add('impression-etiquettes')
  window.print()
  document.documentElement.classList.remove('impression-etiquettes')
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Inventaire du patrimoine</h1>
        <p class="page-sub">Biens relevés, codes collés et entrée en immobilisation (SYSCOHADA révisé)</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn v-if="lotAValoriser.length > 1" color="primary" variant="flat" prepend-icon="mdi-cash-plus" @click="ouvrirValorisationLot">
          Valoriser la sélection ({{ lotAValoriser.length }})
        </v-btn>
        <v-btn color="primary" variant="tonal" prepend-icon="mdi-qrcode" :disabled="!filtres.length" @click="imprimerEtiquettes">
          Étiquettes{{ selection.length ? ` (${selection.length})` : '' }}
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4 no-print" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4 no-print" @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="compte('A_VALORISER')" type="info" variant="tonal" density="compact" class="mb-4 no-print">
      {{ compte('A_VALORISER') }} bien(s) attendent leur valeur et leur date. À partir de {{ SEUIL }} USD, « Valoriser » crée la fiche
      d'amortissement et comptabilise l'entrée au bilan (débit du compte d'immobilisation, crédit 1013 « apport ») ; en dessous, le bien reste du
      petit matériel suivi à l'inventaire. Les dotations se passent ensuite dans Patrimoine › Amortissements.
    </v-alert>

    <v-row class="mb-2 no-print">
      <v-col v-for="(s, cle) in STATUTS" :key="cle" cols="6" md="3">
        <v-card class="classroom-card pa-4" @click="filtreStatut = filtreStatut === cle ? 'TOUS' : (cle as string)">
          <div class="kpi-label">{{ s.titre }}</div>
          <div class="kpi-value">{{ compte(cle as string) }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur immobilisée</div>
          <div class="kpi-value">{{ usd(totalImmobilise) }}</div>
        </v-card>
      </v-col>
    </v-row>

    <div class="d-flex flex-wrap ga-3 mb-4 no-print">
      <v-text-field v-model="recherche" prepend-inner-icon="mdi-magnify" label="Rechercher (code, désignation, emplacement)" hide-details clearable
        variant="outlined" density="comfortable" style="min-width: 260px; flex: 1" />
      <v-select v-model="filtreLieu" :items="['TOUS', ...lieux]" label="Emplacement" hide-details variant="outlined" density="comfortable" style="max-width: 240px" />
      <v-select v-model="filtreStatut" :items="[{ value: 'TOUS', title: 'Tous' }, ...Object.entries(STATUTS).map(([value, s]) => ({ value, title: s.titre }))]"
        label="Statut" hide-details variant="outlined" density="comfortable" style="max-width: 200px" />
    </div>

    <v-card class="classroom-card no-print">
      <v-data-table v-model="selection" :items="filtres" item-value="id" show-select select-strategy="all" :loading="chargement" items-per-page="50" density="comfortable"
        no-data-text="Aucun bien."
        :headers="[
          { title: 'Code', key: 'code' }, { title: 'Désignation', key: 'libelle' }, { title: 'Emplacement', key: 'localisation' },
          { title: 'Catégorie', key: 'categorie' }, { title: 'Durée', key: 'dureeMois' }, { title: 'Valeur', key: 'valeurUsd', align: 'end' },
          { title: 'Statut', key: 'statut' }, { title: '', key: 'actions', sortable: false, align: 'end' },
        ]">
        <template #[`item.code`]="{ item }"><code class="code-bien">{{ item.code }}</code></template>
        <template #[`item.categorie`]="{ item }">{{ libCategorie(item.categorie) }}</template>
        <template #[`item.dureeMois`]="{ item }">{{ duree(item.dureeMois) }}</template>
        <template #[`item.valeurUsd`]="{ item }">{{ item.valeurUsd == null ? '—' : usd(item.valeurUsd) }}</template>
        <template #[`item.statut`]="{ item }">
          <v-chip size="small" variant="tonal" :color="STATUTS[item.statut].couleur">{{ STATUTS[item.statut].titre }}</v-chip>
          <NuxtLink v-if="item.immobilisationReference" to="/patrimoine/immobilisations" class="ml-1 text-caption">{{ item.immobilisationReference }}</NuxtLink>
        </template>
        <template #[`item.actions`]="{ item }">
          <template v-if="peutEcrire && item.statut === 'A_VALORISER'">
            <v-btn size="small" variant="text" icon="mdi-pencil" aria-label="Corriger" @click="ouvrirCorrection(item)" />
            <v-btn size="small" color="primary" variant="tonal" @click="ouvrirValorisation(item)">Valoriser</v-btn>
          </template>
        </template>
      </v-data-table>
    </v-card>

    <!-- Étiquettes à coller : imprimées seules (voir le style global plus bas). -->
    <div class="etiquettes">
      <div v-for="e in etiquettes" :key="e.code" class="etiquette">
        <img :src="e.qr" alt="">
        <div class="etiquette__texte">
          <div class="etiquette__societe">{{ parametresStore.parametres.nom }}</div>
          <div class="etiquette__code">{{ e.code }}</div>
          <div class="etiquette__libelle">{{ e.libelle }}</div>
          <div class="etiquette__lieu">{{ e.lieu }}</div>
        </div>
      </div>
    </div>

    <v-dialog v-model="val.ouvert" max-width="560">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">{{ val.lot.length > 1 ? `Valoriser ${val.lot.length} biens` : `Valoriser ${val.bien?.libelle}` }}</v-card-title>
        <v-card-text>
          <p v-if="val.lot.length > 1" class="text-body-2 text-medium-emphasis mb-4">
            Même valeur <strong>unitaire</strong>, même date et même fiche pour chacun ; une fiche d'amortissement et une pièce par bien.
            <br>{{ [...new Set(val.lot.map(b => b.libelle))].slice(0, 3).join(', ') }}{{ new Set(val.lot.map(b => b.libelle)).size > 3 ? '…' : '' }}
            <span v-if="val.enCours"> — {{ val.fait }} / {{ val.lot.length }}</span>
          </p>
          <p v-else class="text-body-2 text-medium-emphasis mb-4"><code>{{ val.bien?.code }}</code> · {{ val.bien?.localisation }}</p>
          <v-alert v-if="val.erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ val.erreur }}</v-alert>
          <v-row dense>
            <v-col cols="12" sm="6"><v-text-field v-model.number="val.valeurUsd" type="number" min="0" label="Valeur d'acquisition (USD) *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="val.dateAcquisition" type="date" label="Date d'acquisition *" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="val.dateMiseService" type="date" label="Mise en service (si différente)" variant="outlined" density="comfortable" /></v-col>
            <v-col cols="12" sm="6"><v-select v-model="val.categorie" :items="CATEGORIES" label="Catégorie" variant="outlined" density="comfortable" :disabled="sousSeuil" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model.number="val.dureeMois" type="number" min="1" label="Durée d'amortissement (mois)" variant="outlined" density="comfortable" :disabled="sousSeuil"
              :hint="`Taux linéaire : ${val.dureeMois ? (1200 / val.dureeMois).toFixed(2) : '—'} % par an`" persistent-hint /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="val.fournisseur" label="Fournisseur" variant="outlined" density="comfortable" /></v-col>
            <v-col v-if="val.lot.length <= 1" cols="12"><v-text-field v-model="val.numeroSerie" label="N° de série" variant="outlined" density="comfortable" /></v-col>
          </v-row>
          <v-alert v-if="sousSeuil" type="info" variant="tonal" density="compact">
            Sous {{ SEUIL }} USD : petit matériel de faible valeur, non immobilisé et sans écriture ; il reste suivi avec son code.
          </v-alert>
          <v-alert v-else-if="val.valeurUsd && val.lot.length > 1" type="success" variant="tonal" density="compact">
            {{ val.lot.length }} fiches d'amortissement sur {{ duree(val.dureeMois) }} et {{ val.lot.length }} pièces d'acquisition :
            total {{ usd(val.valeurUsd * val.lot.length) }} au débit du compte d'immobilisation, crédit 1013.
          </v-alert>
          <v-alert v-else-if="val.valeurUsd" type="success" variant="tonal" density="compact">
            Fiche d'amortissement linéaire sur {{ duree(val.dureeMois) }} (prorata au jour de la mise en service) et pièce d'acquisition :
            débit du compte d'immobilisation, crédit 1013, {{ usd(val.valeurUsd) }}.
          </v-alert>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="val.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="val.enCours" :disabled="!val.valeurUsd || !val.dateAcquisition" @click="valoriser">Valoriser</v-btn></v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="cor.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Corriger le bien</v-card-title>
        <v-card-text>
          <v-text-field v-model="cor.libelle" label="Désignation" variant="outlined" density="comfortable" />
          <v-select v-model="cor.categorie" :items="CATEGORIES" label="Catégorie" variant="outlined" density="comfortable"
            @update:model-value="(c: Categorie) => cor.dureeMois = CATEGORIES.find(x => x.value === c)?.duree ?? cor.dureeMois" />
          <v-text-field v-model.number="cor.dureeMois" type="number" min="1" label="Durée d'amortissement proposée (mois)" variant="outlined" density="comfortable" />
          <v-text-field v-model="cor.localisation" label="Emplacement" variant="outlined" density="comfortable" />
          <v-text-field v-model="cor.etat" label="État" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="cor.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" @click="corriger">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.code-bien { font-size: 0.78rem; white-space: nowrap; }
.etiquettes { display: none; }
</style>

<style>
/* Impression des étiquettes seules : planche A4 de 3 colonnes, 6,4 × 3,4 cm. */
@media print {
  html.impression-etiquettes body * { visibility: hidden; }
  html.impression-etiquettes .etiquettes, html.impression-etiquettes .etiquettes * { visibility: visible; }
  html.impression-etiquettes .etiquettes {
    display: grid !important; position: absolute; left: 0; top: 0; width: 100%;
    grid-template-columns: repeat(3, 64mm); gap: 3mm; padding: 6mm;
  }
  .etiquette {
    display: flex; gap: 2.5mm; align-items: center; width: 64mm; height: 34mm; padding: 2.5mm; box-sizing: border-box;
    border: 0.3mm dashed #999; break-inside: avoid; color: #000; font-family: Arial, sans-serif;
  }
  .etiquette img { width: 26mm; height: 26mm; }
  .etiquette__texte { min-width: 0; }
  .etiquette__societe { font-size: 7pt; font-weight: 700; text-transform: uppercase; }
  .etiquette__code { font-size: 7.5pt; font-weight: 700; word-break: break-all; margin: 1mm 0; }
  .etiquette__libelle { font-size: 7.5pt; }
  .etiquette__lieu { font-size: 6.5pt; color: #444; }
}
</style>
