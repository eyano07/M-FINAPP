<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'

definePageMeta({ module: 'PATRIMOINE' })

/**
 * Sorties de consommables (19/11/2026) : fournitures remises à un service ou à une personne. Chaque sortie est un
 * mouvement de stock de SORTIE, créé et validé d'un coup (POST /logistique/consommables/sorties) : la charge passe
 * automatiquement au compte de l'article, au coût moyen. Écriture : logistique et administrateur (StockService).
 */
interface Sortie {
  mouvementId: number
  reference: string
  dateSortie: string
  libelle: string | null
  statut: string | null
  articleId: number
  articleCode: string
  articleLibelle: string
  uniteMesure: string | null
  entrepotNom: string | null
  quantite: number
  coutUnitaire: number
  montant: number
  compteCharge: string | null
  pieceReference: string | null
  auteur: string | null
}
interface Article { id: number, type: string }
interface StockNiveau {
  articleId: number
  articleCode: string
  articleLibelle: string
  entrepotId?: number
  entrepotNom: string
  quantite: number
  coutMoyen: number
}
interface Entrepot { id: number, code: string, nom: string }
interface LigneForm { cle: string | null, quantite: number | null }

const api = useApi()
const auth = useAuthStore()
const exercice = useExerciceStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'ADMIN']) && !exercice.lectureSeule)

const loading = ref(false)
const erreur = ref('')
const succes = ref('')
const sorties = ref<Sortie[]>([])
const disponibles = ref<(StockNiveau & { entrepotId: number })[]>([])
const tauxChange = ref(0)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [lignes, arts, stk, entrepots, taux] = await Promise.all([
      api<Sortie[]>('/logistique/consommables/sorties'),
      api<Article[]>('/logistique/articles').catch(() => [] as Article[]),
      api<StockNiveau[]>('/logistique/stock').catch(() => [] as StockNiveau[]),
      api<Entrepot[]>('/logistique/entrepots').catch(() => [] as Entrepot[]),
      api<{ taux: number }>('/admin/taux-change').catch(() => ({ taux: 0 })),
    ])
    // Exercice choisi dans la barre du haut : ses sorties seulement (tout, pour l'exercice en cours).
    sorties.value = lignes.filter(l => exercice.dansExercice(l.dateSortie))
    const consommables = new Set(arts.filter(a => a.type === 'CONSOMMABLE').map(a => a.id))
    const idEntrepot = new Map(entrepots.map(e => [e.nom, e.id]))
    disponibles.value = stk
      .filter(s => consommables.has(s.articleId) && Number(s.quantite) > 0)
      .map(s => ({ ...s, entrepotId: s.entrepotId ?? idEntrepot.get(s.entrepotNom) ?? 0 }))
      .filter(s => s.entrepotId > 0)
    tauxChange.value = taux.taux || 0
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les sorties de consommables.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

// ── Filtres ──────────────────────────────────────────────────────────────
const recherche = ref('')
const du = ref(exercice.enConsultation ? exercice.debut : '')
const au = ref(exercice.enConsultation ? exercice.fin : '')
const sortiesFiltrees = computed(() => {
  const q = recherche.value.trim().toLowerCase()
  return sorties.value.filter((s) => {
    if (du.value && s.dateSortie < du.value) return false
    if (au.value && s.dateSortie > au.value) return false
    if (q && ![s.reference, s.articleCode, s.articleLibelle, s.libelle, s.auteur]
      .some(v => (v || '').toLowerCase().includes(q))) return false
    return true
  })
})

const toUSD = (fc: number) => (tauxChange.value > 0 ? (Number(fc) || 0) / tauxChange.value : 0)
const fmt = (fc: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(toUSD(fc))
const fmtQte = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(Number(v) || 0)
const fmtDate = (d: string) => new Date(d + 'T00:00:00').toLocaleDateString('fr-FR')
const totalSorti = computed(() => sortiesFiltrees.value
  .filter(s => s.statut !== 'ANNULE').reduce((t, s) => t + Number(s.montant || 0), 0))
const beneficiaires = computed(() => new Set(sortiesFiltrees.value.map(s => s.libelle)).size)

const entetes = [
  { title: 'Date', key: 'dateSortie' },
  { title: 'Référence', key: 'reference' },
  { title: 'Consommable', key: 'articleLibelle' },
  { title: 'Bénéficiaire / motif', key: 'libelle', sortable: false },
  { title: 'Entrepôt', key: 'entrepotNom' },
  { title: 'Quantité', key: 'quantite', align: 'end' as const },
  { title: 'Coût unitaire', key: 'coutUnitaire', align: 'end' as const },
  { title: 'Montant', key: 'montant', align: 'end' as const },
  { title: 'Compte', key: 'compteCharge' },
  { title: 'Statut', key: 'statut', align: 'center' as const },
]
const beneficiaireDe = (libelle: string | null) => (libelle || '').replace(/^Sortie de consommables — /, '') || '—'

// ── Nouvelle sortie ──────────────────────────────────────────────────────
const dialogue = ref(false)
const enregistrement = ref(false)
const erreurForm = ref('')
const aujourdhui = () => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const form = reactive({ dateSortie: aujourdhui(), beneficiaire: '', motif: '', lignes: [] as LigneForm[] })

const cleDe = (s: { articleId: number, entrepotId: number }) => `${s.articleId}|${s.entrepotId}`
const options = computed(() => disponibles.value.map(s => ({
  value: cleDe(s),
  title: `${s.articleCode} — ${s.articleLibelle}`,
  subtitle: `${s.entrepotNom} · disponible : ${fmtQte(s.quantite)}`,
})))
const niveauDe = (cle: string | null) => disponibles.value.find(s => cleDe(s) === cle)

function ouvrir() {
  Object.assign(form, { dateSortie: aujourdhui(), beneficiaire: '', motif: '', lignes: [{ cle: null, quantite: null }] })
  erreurForm.value = ''
  dialogue.value = true
}
const valeurEstimee = computed(() => form.lignes.reduce((t, l) => {
  const n = niveauDe(l.cle)
  return t + (n ? Number(n.coutMoyen) * Number(l.quantite || 0) : 0)
}, 0))

async function enregistrer() {
  erreurForm.value = ''
  const lignes = form.lignes.filter(l => l.cle && Number(l.quantite) > 0)
  if (!form.beneficiaire.trim()) { erreurForm.value = 'Indiquez le service ou la personne qui reçoit les fournitures.'; return }
  if (lignes.length === 0) { erreurForm.value = 'Ajoutez au moins un consommable avec sa quantité.'; return }
  for (const l of lignes) {
    const n = niveauDe(l.cle)
    if (n && Number(l.quantite) > Number(n.quantite)) {
      erreurForm.value = `Stock insuffisant pour ${n.articleLibelle} à ${n.entrepotNom} : ${fmtQte(n.quantite)} disponible(s).`
      return
    }
  }
  enregistrement.value = true
  try {
    const r = await api<{ reference: string }>('/logistique/consommables/sorties', {
      method: 'POST',
      body: {
        dateSortie: form.dateSortie,
        beneficiaire: form.beneficiaire.trim(),
        motif: form.motif.trim() || null,
        lignes: lignes.map((l) => {
          const n = niveauDe(l.cle)!
          return { articleId: n.articleId, entrepotId: n.entrepotId, quantite: Number(l.quantite) }
        }),
      },
    })
    dialogue.value = false
    succes.value = `Sortie ${r.reference} enregistrée : la charge est passée en comptabilité.`
    await charger()
  } catch (e) {
    erreurForm.value = messageErreurApi(e, "La sortie n'a pas pu être enregistrée.")
  } finally {
    enregistrement.value = false
  }
}

// ── Impression ───────────────────────────────────────────────────────────
const lignesParPage = ref(25)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  lignesParPage.value = -1
  nextTick(() => {
    window.print()
    lignesParPage.value = 25
  })
}
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Sorties de consommables</h1>
        <p class="page-sub">Fournitures remises aux services — la charge passe automatiquement en comptabilité</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="sortiesFiltrees.length" color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline"
          @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" rounded="lg" prepend-icon="mdi-export" :disabled="!disponibles.length"
          @click="ouvrir">
          Nouvelle sortie
        </v-btn>
      </div>
    </div>

    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Sorties de consommables</span>
          <span class="etat-print-header__service">
            <template v-if="du || au">Du {{ du ? fmtDate(du) : 'début' }} au {{ au ? fmtDate(au) : "aujourd'hui" }}</template>
            <template v-else>Toutes les sorties</template>
          </span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta"><span>Imprimé le : {{ dateImpression }}</span></div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4 no-print" @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="!loading && canWrite && !disponibles.length" type="info" variant="tonal" class="mb-4 no-print">
      Aucun consommable en stock. Les consommables se créent dans <NuxtLink to="/patrimoine/consommables">Consommables</NuxtLink>
      et entrent en stock par un achat (note de frais) ou un mouvement d'entrée.
    </v-alert>

    <v-row class="mb-2 no-print">
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Lignes de sortie</div>
          <div class="kpi-value">{{ sortiesFiltrees.length }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur sortie (charge)</div>
          <div class="kpi-value">{{ fmt(totalSorti) }}</div>
        </v-card>
      </v-col>
      <v-col cols="12" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Remises distinctes</div>
          <div class="kpi-value">{{ beneficiaires }}</div>
        </v-card>
      </v-col>
    </v-row>

    <div class="d-flex flex-wrap ga-3 mb-4 no-print">
      <v-text-field v-model="du" type="date" label="Du" variant="outlined" density="comfortable" rounded="lg" hide-details style="max-width: 170px" />
      <v-text-field v-model="au" type="date" label="Au" variant="outlined" density="comfortable" rounded="lg" hide-details style="max-width: 170px" />
      <v-text-field v-model="recherche" label="Rechercher (consommable, bénéficiaire, référence)" prepend-inner-icon="mdi-magnify"
        variant="outlined" density="comfortable" rounded="lg" hide-details clearable style="min-width: 260px; flex: 1" />
    </div>

    <v-card class="classroom-card">
      <v-data-table :headers="entetes" :items="sortiesFiltrees" :loading="loading" density="comfortable"
        :items-per-page="lignesParPage" no-data-text="Aucune sortie de consommable sur la période.">
        <template #[`item.dateSortie`]="{ item }">{{ fmtDate(item.dateSortie) }}</template>
        <template #[`item.articleLibelle`]="{ item }">
          <div class="font-weight-medium">{{ item.articleLibelle }}</div>
          <div class="text-caption text-medium-emphasis">{{ item.articleCode }}</div>
        </template>
        <template #[`item.libelle`]="{ item }">{{ beneficiaireDe(item.libelle) }}</template>
        <template #[`item.quantite`]="{ item }">{{ fmtQte(item.quantite) }} {{ item.uniteMesure || '' }}</template>
        <template #[`item.coutUnitaire`]="{ item }">{{ fmt(item.coutUnitaire) }}</template>
        <template #[`item.montant`]="{ item }"><strong>{{ fmt(item.montant) }}</strong></template>
        <template #[`item.compteCharge`]="{ item }">{{ item.compteCharge || '—' }}</template>
        <template #[`item.statut`]="{ item }">
          <v-chip size="x-small" variant="tonal"
            :color="item.statut === 'VALIDE' ? 'success' : item.statut === 'ANNULE' ? 'error' : 'grey'">
            {{ item.statut === 'VALIDE' ? 'Validée' : item.statut === 'ANNULE' ? 'Annulée' : 'Brouillon' }}
          </v-chip>
        </template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="dialogue" max-width="720" persistent>
      <v-card class="classroom-card pa-6">
        <h2 class="text-h6 mb-1">Nouvelle sortie de consommables</h2>
        <p class="text-body-2 text-medium-emphasis mb-4">
          Les fournitures quittent le stock au coût moyen ; la charge passe sur le compte de chaque consommable.
        </p>
        <v-alert v-if="erreurForm" type="error" variant="tonal" density="compact" class="mb-4">{{ erreurForm }}</v-alert>
        <v-row dense>
          <v-col cols="12" sm="4">
            <v-text-field v-model="form.dateSortie" type="date" label="Date" variant="outlined" density="comfortable" />
          </v-col>
          <v-col cols="12" sm="8">
            <v-text-field v-model="form.beneficiaire" label="Remis à (service ou personne) *" variant="outlined" density="comfortable"
              maxlength="120" />
          </v-col>
          <v-col cols="12">
            <v-text-field v-model="form.motif" label="Motif (facultatif)" variant="outlined" density="comfortable" maxlength="200" />
          </v-col>
        </v-row>

        <div class="d-flex align-center justify-space-between mt-2 mb-2">
          <span class="text-subtitle-2">Consommables</span>
          <v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-plus" @click="form.lignes.push({ cle: null, quantite: null })">
            Ajouter une ligne
          </v-btn>
        </div>
        <v-row v-for="(l, i) in form.lignes" :key="i" dense class="align-center">
          <v-col cols="12" sm="8">
            <v-autocomplete v-model="l.cle" :items="options" item-title="title" item-value="value" label="Consommable et entrepôt"
              variant="outlined" density="comfortable" hide-details>
              <template #item="{ props: p, item }">
                <v-list-item v-bind="p" :subtitle="item.raw.subtitle" />
              </template>
            </v-autocomplete>
          </v-col>
          <v-col cols="9" sm="3">
            <v-text-field v-model.number="l.quantite" type="number" min="0" label="Quantité" variant="outlined" density="comfortable"
              hide-details :hint="niveauDe(l.cle) ? `max ${fmtQte(niveauDe(l.cle)!.quantite)}` : ''" />
          </v-col>
          <v-col cols="3" sm="1" class="text-right">
            <v-btn icon="mdi-delete-outline" variant="text" color="error" size="small" :disabled="form.lignes.length === 1"
              aria-label="Retirer la ligne" @click="form.lignes.splice(i, 1)" />
          </v-col>
        </v-row>

        <div class="d-flex align-center justify-space-between mt-4">
          <span class="text-body-2 text-medium-emphasis">Valeur estimée : <strong>{{ fmt(valeurEstimee) }}</strong></span>
          <div class="d-flex ga-2">
            <v-btn variant="text" :disabled="enregistrement" @click="dialogue = false">Annuler</v-btn>
            <v-btn color="primary" :loading="enregistrement" prepend-icon="mdi-check" @click="enregistrer">Enregistrer la sortie</v-btn>
          </div>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>
