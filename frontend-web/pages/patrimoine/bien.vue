<script setup lang="ts">
/**
 * Fiche d'un bien d'après le code de son étiquette (26/11/2026) : page ouverte par l'application MBSC Logistique
 * après le scan du QR (/patrimoine/bien?code=…), ou depuis le site en saisissant le code. Elle montre l'identité du
 * bien, son emplacement, son statut, ses photos, sa facture et, s'il est immobilisé, sa fiche d'amortissement.
 */
definePageMeta({ module: 'PATRIMOINE' })
useHead({ title: 'Fiche du bien' })

interface Bien {
  id: number, code: string, libelle: string, categorie: string, dureeMois: number, localisation: string | null, etat: string | null,
  statut: 'A_VALORISER' | 'IMMOBILISE' | 'PETIT_MATERIEL', valeurUsd: number | null, dateAcquisition: string | null,
  dateMiseService: string | null, immobilisationId: number | null, immobilisationReference: string | null,
  emplacementNom: string | null, emplacementType: string | null, nbPhotos: number, factureNom: string | null, factureType: string | null
}
interface Fiche {
  reference: string, compteImmobilisation: string | null, valeurAcquisition: number, dureeMois: number, fournisseur: string | null,
  numeroSerie: string | null, responsableNom: string | null, cumulAmortissements: number, valeurNetteComptable: number, statut: string
}

const CATEGORIES: Record<string, string> = {
  MOBILIER: 'Mobilier de bureau', MATERIEL_BUREAU: 'Matériel de bureau', MATERIEL_INFORMATIQUE: 'Matériel informatique',
  MATERIEL_INDUSTRIEL: 'Matériel industriel', MATERIEL_TRANSPORT: 'Matériel de transport', LOGICIEL: 'Logiciel',
  BATIMENT: 'Bâtiment', AUTRE: 'Autre',
}
const STATUTS = {
  A_VALORISER: { titre: 'À valoriser', couleur: 'warning', icone: 'mdi-progress-clock' },
  IMMOBILISE: { titre: 'Immobilisé', couleur: 'success', icone: 'mdi-check-decagram' },
  PETIT_MATERIEL: { titre: 'Non amortissable', couleur: 'info', icone: 'mdi-information-outline' },
} as const

const route = useRoute()
const router = useRouter()
const api = useApi()
const bien = ref<Bien | null>(null)
const fiche = ref<Fiche | null>(null)
const photos = ref<string[]>([])
const grande = ref<string | null>(null)
const chargement = ref(false)
const erreur = ref('')
const saisie = ref('')

/** Pont de l'application MBSC Logistique (window.LogistiqueAndroid), absent dans un navigateur. */
const pont = computed(() => import.meta.client ? (window as any).LogistiqueAndroid ?? null : null)

function liberer() {
  photos.value.forEach(u => URL.revokeObjectURL(u))
  photos.value = []
}

async function charger(code: string) {
  liberer()
  bien.value = null
  fiche.value = null
  erreur.value = ''
  if (!code) return
  chargement.value = true
  try {
    const b = await api<Bien>(`/patrimoine/inventaire/par-code?${new URLSearchParams({ code })}`)
    bien.value = b
    const [f, liste] = await Promise.all([
      b.immobilisationId ? api<Fiche>(`/patrimoine/immobilisations/${b.immobilisationId}`).catch(() => null) : null,
      b.nbPhotos ? api<{ id: number }[]>(`/patrimoine/inventaire/${b.id}/photos`).catch(() => []) : [],
    ])
    fiche.value = f
    photos.value = await Promise.all(liste.map(async p =>
      URL.createObjectURL(await api<Blob>(`/patrimoine/inventaire/${b.id}/photos/${p.id}`, { responseType: 'blob' }))))
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Bien introuvable.')
  } finally {
    chargement.value = false
  }
}

watch(() => route.query.code, c => {
  saisie.value = typeof c === 'string' ? c : ''
  charger(saisie.value.trim())
}, { immediate: true })
onBeforeUnmount(liberer)

function chercher() {
  const c = saisie.value.trim()
  if (c) router.replace({ query: { code: c } })
}

function scanner() {
  pont.value?.scanner?.()
}

async function voirFacture() {
  if (!bien.value) return
  try {
    const blob = await api<Blob>(`/patrimoine/inventaire/${bien.value.id}/facture`, { responseType: 'blob' })
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "La facture n'a pas pu être ouverte.")
  }
}

const fmtUsd = (v: number | null | undefined) => v == null ? '—'
  : new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD' }).format(v)
const fmtDate = (d: string | null) => d ? new Date(d).toLocaleDateString('fr-FR') : '—'
const duree = (m: number) => m % 12 === 0 ? `${m / 12} an${m / 12 > 1 ? 's' : ''}` : `${m} mois`
const lieu = computed(() => bien.value?.emplacementNom || bien.value?.localisation || '—')
</script>

<template>
  <div class="fiche-bien">
    <div class="page-head">
      <div>
        <h1 class="page-title">Fiche du bien</h1>
        <p class="page-sub">Scannez le QR de l'étiquette ou saisissez son code</p>
      </div>
    </div>

    <v-card class="classroom-card pa-4 mb-4">
      <div class="d-flex ga-2 align-center flex-wrap">
        <v-text-field v-model="saisie" label="Code du bien" placeholder="MBSC/APPROS/…" variant="outlined" density="comfortable"
          hide-details prepend-inner-icon="mdi-barcode" class="flex-grow-1" style="min-width: 220px" @keyup.enter="chercher" />
        <v-btn color="primary" variant="tonal" height="48" :loading="chargement" @click="chercher">Chercher</v-btn>
        <v-btn v-if="pont" color="primary" variant="flat" height="48" prepend-icon="mdi-qrcode-scan" @click="scanner">Scanner</v-btn>
      </div>
    </v-card>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="chargement && !bien" type="article, list-item-two-line@4" class="classroom-card" />

    <template v-if="bien">
      <v-card class="classroom-card pa-5 mb-4">
        <div class="d-flex align-start ga-3 flex-wrap">
          <div class="flex-grow-1">
            <div class="text-h6 font-weight-bold">{{ bien.libelle }}</div>
            <div class="text-body-2 text-medium-emphasis code">{{ bien.code }}</div>
          </div>
          <v-chip :color="STATUTS[bien.statut].couleur" variant="tonal" :prepend-icon="STATUTS[bien.statut].icone">
            {{ STATUTS[bien.statut].titre }}
          </v-chip>
        </div>

        <div v-if="photos.length" class="photos mt-4">
          <img v-for="(p, i) in photos" :key="i" :src="p" alt="Photo du bien" @click="grande = p">
        </div>

        <v-list density="compact" class="mt-3 bg-transparent">
          <v-list-item prepend-icon="mdi-map-marker-outline" title="Emplacement" :subtitle="lieu" />
          <v-list-item prepend-icon="mdi-shape-outline" title="Catégorie" :subtitle="CATEGORIES[bien.categorie] ?? bien.categorie" />
          <v-list-item prepend-icon="mdi-heart-pulse" title="État" :subtitle="bien.etat || '—'" />
          <v-list-item prepend-icon="mdi-cash" title="Valeur d'acquisition" :subtitle="fmtUsd(bien.valeurUsd)" />
          <v-list-item prepend-icon="mdi-calendar-start" title="Acquis le" :subtitle="fmtDate(bien.dateAcquisition)" />
          <v-list-item prepend-icon="mdi-calendar-check" title="Mis en service le" :subtitle="fmtDate(bien.dateMiseService)" />
        </v-list>

        <v-btn v-if="bien.factureNom" variant="tonal" color="primary" prepend-icon="mdi-file-document-outline" class="mt-2" @click="voirFacture">
          Facture
        </v-btn>
      </v-card>

      <v-card v-if="fiche" class="classroom-card pa-5 mb-4">
        <div class="d-flex align-center ga-2 mb-2">
          <v-icon icon="mdi-office-building-cog-outline" color="primary" />
          <span class="font-weight-bold">Fiche d'immobilisation {{ fiche.reference }}</span>
        </div>
        <v-row dense class="mb-2">
          <v-col cols="12" sm="4"><div class="chiffre"><span>Valeur brute</span><strong>{{ fmtUsd(fiche.valeurAcquisition) }}</strong></div></v-col>
          <v-col cols="6" sm="4"><div class="chiffre"><span>Amortissements</span><strong>{{ fmtUsd(fiche.cumulAmortissements) }}</strong></div></v-col>
          <v-col cols="6" sm="4"><div class="chiffre chiffre--net"><span>Valeur nette</span><strong>{{ fmtUsd(fiche.valeurNetteComptable) }}</strong></div></v-col>
        </v-row>
        <v-list density="compact" class="bg-transparent">
          <v-list-item prepend-icon="mdi-truck-outline" title="Fournisseur" :subtitle="fiche.fournisseur || '—'" />
          <v-list-item prepend-icon="mdi-identifier" title="Numéro de série" :subtitle="fiche.numeroSerie || '—'" />
          <v-list-item prepend-icon="mdi-timer-sand" title="Durée d'amortissement" :subtitle="duree(fiche.dureeMois)" />
          <v-list-item prepend-icon="mdi-book-outline" title="Compte" :subtitle="fiche.compteImmobilisation || '—'" />
          <v-list-item v-if="fiche.responsableNom" prepend-icon="mdi-account-outline" title="Responsable" :subtitle="fiche.responsableNom" />
        </v-list>
      </v-card>
    </template>

    <v-dialog :model-value="!!grande" max-width="900" @update:model-value="grande = null">
      <v-card><img v-if="grande" :src="grande" alt="Photo du bien" class="photo-grande" @click="grande = null"></v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.fiche-bien { max-width: 820px; margin: 0 auto; }
.code { font-family: ui-monospace, monospace; word-break: break-all; }
.photos { display: flex; gap: 8px; overflow-x: auto; }
.photos img { height: 120px; border-radius: 10px; object-fit: cover; cursor: zoom-in; flex: 0 0 auto; }
.photo-grande { width: 100%; display: block; }
.chiffre { display: flex; flex-direction: column; padding: 10px 12px; border-radius: 12px;
  background: color-mix(in srgb, var(--color-primary, #2e7d32) 6%, transparent); }
.chiffre span { font-size: 12px; opacity: .7; }
.chiffre strong { font-size: 16px; }
.chiffre--net { background: color-mix(in srgb, var(--color-primary, #2e7d32) 14%, transparent); }
</style>
