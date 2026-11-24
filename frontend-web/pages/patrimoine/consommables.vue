<script setup lang="ts">
definePageMeta({ module: 'PATRIMOINE' })

/**
 * Consommables (fournitures consommées en interne), V157 :
 *  - création simplifiée : nom, type, unité, prix d'achat, seuil et facture proforma (image ou PDF) ; le serveur
 *    attribue le code et crée les sous-comptes de stock (33x) et d'achat (604x) propres au consommable ;
 *  - achat groupé : plusieurs consommables et leurs quantités ; la validation crée une note de frais soumise au DFIN
 *    (DFIN, DA, DFIN, caisse). Au paiement, les quantités entrent en stock et cette page se met à jour ; un retour de
 *    fonds sur la note (depuis la note elle-même) annule tout ou partie de l'achat et ressort la quantité du stock.
 */
interface Consommable {
  id: number
  code: string
  libelle: string
  type: string | null
  typeLibelle: string | null
  uniteMesure: string | null
  prixAchat: number | null
  stockMin: number
  actif: boolean
  compteStock: string | null
  compteAchat: string | null
  proformaNom: string | null
  proformaType: string | null
  quantite: number
  valeur: number
  sousSeuil: boolean
}

const TYPES = [
  { value: 'FOURNITURES_BUREAU', title: 'Fournitures de bureau', comptes: '334 · 6047' },
  { value: 'PRODUITS_ENTRETIEN', title: "Produits d'entretien", comptes: '338 · 6043' },
  { value: 'CARBURANT', title: 'Carburant et combustibles', comptes: '338 · 6042' },
  { value: 'FOURNITURES_ATELIER', title: "Fournitures d'atelier", comptes: '331 · 6044' },
  { value: 'FOURNITURES_MAGASIN', title: 'Fournitures de magasin', comptes: '333 · 6046' },
  { value: 'MATIERES_CONSOMMABLES', title: 'Matières consommables', comptes: '331 · 6041' },
]
const TYPES_PROFORMA = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf']

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['LOGISTIQUE', 'GEST_PATRIMOINE', 'DFIN', 'ADMIN']))
const loading = ref(false)
const erreur = ref('')
const succes = ref('')
const consommables = ref<Consommable[]>([])

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    consommables.value = await api<Consommable[]>('/patrimoine/consommables')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les consommables.')
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  charger()
  parametresStore.charger()
  window.addEventListener('beforeprint', () => { lignesParPage.value = -1 })
  window.addEventListener('afterprint', () => { lignesParPage.value = 25 })
})

const usd = (v: number | null | undefined) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(Number(v || 0))
const fmtQte = (v: number) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 3 }).format(Number(v || 0))

// ── Filtres ───────────────────────────────────────────────────────────────
const filtreRecherche = ref('')
const filtreType = ref('TOUS')
const filtreSousSeuil = ref(false)
const liste = computed(() => {
  const q = filtreRecherche.value.trim().toLowerCase()
  return consommables.value.filter((c) => {
    if (q && !c.code.toLowerCase().includes(q) && !c.libelle.toLowerCase().includes(q)) return false
    if (filtreType.value !== 'TOUS' && c.type !== filtreType.value) return false
    if (filtreSousSeuil.value && !c.sousSeuil) return false
    return true
  })
})
const valeurTotale = computed(() => consommables.value.reduce((s, c) => s + Number(c.valeur || 0), 0))
const sousSeuil = computed(() => consommables.value.filter(c => c.sousSeuil))

const lignesParPage = ref(25)
const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  window.print()
}

// ── Création / modification ────────────────────────────────────────────────
const form = reactive({
  ouvert: false, id: null as number | null, libelle: '', type: 'FOURNITURES_BUREAU', uniteMesure: '',
  prixAchat: null as number | null, stockMin: 0 as number | null, proforma: null as File | null, enCours: false, erreur: '',
})
const proformaInvalide = computed(() => !!form.proforma
  && (!TYPES_PROFORMA.includes(form.proforma.type) || form.proforma.size > 15 * 1024 * 1024))
const formValide = computed(() => form.libelle.trim() && form.type && Number(form.prixAchat) > 0 && !proformaInvalide.value)

function ouvrirCreation() {
  Object.assign(form, { ouvert: true, id: null, libelle: '', type: 'FOURNITURES_BUREAU', uniteMesure: '', prixAchat: null,
    stockMin: 0, proforma: null, enCours: false, erreur: '' })
}
function ouvrirModification(c: Consommable) {
  Object.assign(form, { ouvert: true, id: c.id, libelle: c.libelle, type: c.type ?? 'FOURNITURES_BUREAU',
    uniteMesure: c.uniteMesure ?? '', prixAchat: c.prixAchat, stockMin: c.stockMin, proforma: null, enCours: false, erreur: '' })
}
async function envoyerProforma(id: number, f: File) {
  const fd = new FormData()
  fd.append('fichier', f)
  await api(`/patrimoine/consommables/${id}/proforma`, { method: 'POST', body: fd })
}
async function enregistrer() {
  if (!formValide.value) return
  form.enCours = true
  form.erreur = ''
  const body = { libelle: form.libelle.trim(), type: form.type, uniteMesure: form.uniteMesure.trim() || null,
    prixAchat: form.prixAchat, stockMin: form.stockMin || 0 }
  try {
    const c = form.id
      ? await api<Consommable>(`/patrimoine/consommables/${form.id}`, { method: 'PUT', body })
      : await api<Consommable>('/patrimoine/consommables', { method: 'POST', body })
    if (form.proforma) await envoyerProforma(c.id, form.proforma)
    succes.value = form.id
      ? `${c.code} modifié.`
      : `${c.code} « ${c.libelle} » créé, avec ses comptes ${c.compteStock} (stock) et ${c.compteAchat} (achat).`
    form.ouvert = false
    await charger()
  } catch (e: any) {
    form.erreur = messageErreurApi(e, "Le consommable n'a pas été enregistré.")
  } finally {
    form.enCours = false
  }
}

async function voirProforma(c: Consommable) {
  try {
    const blob = await api<Blob>(`/patrimoine/consommables/${c.id}/proforma`, { responseType: 'blob' })
    window.open(URL.createObjectURL(blob), '_blank')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'ouvrir la proforma.")
  }
}

// ── Achat groupé → note de frais ───────────────────────────────────────────
interface LigneAchat { consommableId: number | null, quantite: number | null, observation: string }
const achat = reactive({
  ouvert: false, lignes: [] as LigneAchat[], objet: '', justification: '', enCours: false, erreur: '',
})
const achetables = computed(() => consommables.value.filter(c => c.actif && Number(c.prixAchat) > 0))
const parId = computed(() => new Map(consommables.value.map(c => [c.id, c])))
function ouvrirAchat(pre?: Consommable) {
  Object.assign(achat, { ouvert: true, objet: '', justification: '', enCours: false, erreur: '',
    lignes: [{ consommableId: pre?.id ?? null, quantite: null, observation: '' }] })
}
function ajouterLigne() { achat.lignes.push({ consommableId: null, quantite: null, observation: '' }) }
function retirerLigne(i: number) { achat.lignes.splice(i, 1) }
const montantLigne = (l: LigneAchat) => {
  const c = l.consommableId ? parId.value.get(l.consommableId) : null
  return c && l.quantite ? Number(c.prixAchat) * Number(l.quantite) : 0
}
const totalAchat = computed(() => achat.lignes.reduce((s, l) => s + montantLigne(l), 0))
const dejaChoisis = (i: number) => new Set(achat.lignes.filter((_, j) => j !== i).map(l => l.consommableId))
const achatValide = computed(() => achat.lignes.length > 0
  && achat.lignes.every(l => l.consommableId && Number(l.quantite) > 0)
  && new Set(achat.lignes.map(l => l.consommableId)).size === achat.lignes.length)
async function validerAchat() {
  if (!achatValide.value) return
  achat.enCours = true
  achat.erreur = ''
  try {
    const r = await api<{ noteId: number, reference: string, montant: number }>('/patrimoine/consommables/achats', {
      method: 'POST',
      body: {
        lignes: achat.lignes.map(l => ({ consommableId: l.consommableId, quantite: l.quantite, observation: l.observation.trim() || null })),
        objet: achat.objet.trim() || null,
        justificationBudget: achat.justification.trim() || null,
      },
    })
    achat.ouvert = false
    noteCreee.value = r
  } catch (e: any) {
    achat.erreur = messageErreurApi(e, "L'achat n'a pas été enregistré.")
  } finally {
    achat.enCours = false
  }
}
const noteCreee = ref<{ noteId: number, reference: string, montant: number } | null>(null)
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Consommables</h1>
        <p class="page-sub">Fournitures consommées en interne — achat par note de frais, stock, valeur et seuils</p>
      </div>
      <div class="d-flex ga-2 flex-wrap">
        <v-btn v-if="liste.length" color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-package-variant-remove" to="/patrimoine/sorties-consommables">
          Sorties
        </v-btn>
        <v-btn v-if="canWrite && achetables.length" color="success" variant="flat" rounded="lg" prepend-icon="mdi-cart-outline" @click="ouvrirAchat()">
          Acheter
        </v-btn>
        <v-btn v-if="canWrite" color="primary" rounded="lg" prepend-icon="mdi-plus" @click="ouvrirCreation">
          Nouveau consommable
        </v-btn>
      </div>
    </div>

    <!-- En-tête d'impression : masquée à l'écran, visible uniquement sur le papier. -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">Consommables</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="noteCreee" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="noteCreee = null">
      Note de frais <NuxtLink :to="`/notes-frais/${noteCreee.noteId}`" class="font-weight-bold">{{ noteCreee.reference }}</NuxtLink>
      créée ({{ usd(noteCreee.montant) }}) et soumise au DFIN. Les quantités entreront en stock au paiement par la caisse ;
      pour annuler tout ou partie de l'achat, faites un retour de fonds depuis la note.
    </v-alert>

    <v-alert v-if="!loading && consommables.length === 0" type="info" variant="tonal" class="mb-4 no-print">
      Aucun consommable enregistré.<template v-if="canWrite"> Créez-en un avec <strong>Nouveau consommable</strong> : nom, type,
      prix et seuil suffisent, les comptes sont créés automatiquement.</template>
    </v-alert>

    <v-row v-if="consommables.length" class="mb-2 no-print">
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Références</div>
          <div class="kpi-value">{{ consommables.length }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Valeur du stock</div>
          <div class="kpi-value">{{ usd(valeurTotale) }}</div>
        </v-card>
      </v-col>
      <v-col cols="12" md="4">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Sous le seuil</div>
          <div class="kpi-value" :class="sousSeuil.length ? 'text-error' : 'text-success'">{{ sousSeuil.length }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-alert v-if="sousSeuil.length" type="warning" variant="tonal" class="mb-4">
      <strong>{{ sousSeuil.length }} consommable(s) à réapprovisionner :</strong>
      {{ sousSeuil.map(s => s.libelle).join(', ') }}
    </v-alert>

    <v-card v-if="consommables.length" class="classroom-card pa-4 mb-4 no-print">
      <v-row align="center" dense>
        <v-col cols="12" md="5">
          <v-text-field v-model="filtreRecherche" label="Rechercher (code, libellé)" variant="outlined"
            density="comfortable" hide-details prepend-inner-icon="mdi-magnify" clearable />
        </v-col>
        <v-col cols="7" md="4">
          <v-select v-model="filtreType" :items="[{ title: 'Tous les types', value: 'TOUS' }, ...TYPES]"
            label="Type" variant="outlined" density="comfortable" hide-details />
        </v-col>
        <v-col cols="5" md="3">
          <v-switch v-model="filtreSousSeuil" label="Sous le seuil" color="warning" density="comfortable" hide-details />
        </v-col>
      </v-row>
    </v-card>

    <v-card class="classroom-card">
      <v-data-table
        class="table-cartes"
        :headers="[
          { title: 'Code', key: 'code' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Type', key: 'typeLibelle' },
          { title: 'Prix d\'achat', key: 'prixAchat', align: 'end' },
          { title: 'En stock', key: 'quantite', align: 'end' },
          { title: 'Valeur', key: 'valeur', align: 'end' },
          { title: 'Seuil', key: 'stockMin', align: 'end' },
          { title: '', key: 'actions', sortable: false, align: 'end' },
        ]"
        :items="liste"
        :loading="loading"
        :items-per-page="lignesParPage"
      >
        <template #[`item.code`]="{ item }"><code>{{ item.code }}</code></template>
        <template #[`item.libelle`]="{ item }">
          <div class="font-weight-medium">{{ item.libelle }}</div>
          <div class="text-caption text-medium-emphasis">Comptes {{ item.compteStock || '—' }} · {{ item.compteAchat || '—' }}</div>
        </template>
        <template #[`item.typeLibelle`]="{ item }">{{ item.typeLibelle || '—' }}</template>
        <template #[`item.prixAchat`]="{ item }">
          {{ item.prixAchat == null ? '—' : usd(item.prixAchat) }}<span v-if="item.uniteMesure" class="text-medium-emphasis"> / {{ item.uniteMesure }}</span>
        </template>
        <template #[`item.quantite`]="{ item }">
          <span :class="item.sousSeuil ? 'text-error font-weight-bold' : ''">{{ fmtQte(item.quantite) }} {{ item.uniteMesure || '' }}</span>
        </template>
        <template #[`item.valeur`]="{ item }">{{ usd(item.valeur) }}</template>
        <template #[`item.stockMin`]="{ item }">{{ fmtQte(item.stockMin) }}</template>
        <template #[`item.actions`]="{ item }">
          <div class="d-flex justify-end ga-1 no-print">
            <v-btn v-if="item.proformaNom" icon="mdi-file-document-outline" size="small" variant="text" :title="`Proforma : ${item.proformaNom}`" @click="voirProforma(item)" />
            <v-btn v-if="canWrite && item.actif && Number(item.prixAchat) > 0" icon="mdi-cart-plus" size="small" variant="text" color="success" title="Acheter" @click="ouvrirAchat(item)" />
            <v-btn v-if="canWrite" icon="mdi-pencil" size="small" variant="text" title="Modifier" @click="ouvrirModification(item)" />
          </div>
        </template>
        <template #no-data>
          <div class="pa-6 text-center text-medium-emphasis">
            {{ consommables.length ? 'Aucun consommable ne correspond aux filtres.' : 'Aucun consommable pour l\'instant.' }}
          </div>
        </template>
      </v-data-table>
    </v-card>

    <!-- Création / modification -->
    <v-dialog v-model="form.ouvert" max-width="560">
      <v-card class="classroom-card pa-2">
        <v-card-title>{{ form.id ? 'Modifier le consommable' : 'Nouveau consommable' }}</v-card-title>
        <v-card-text>
          <v-alert v-if="form.erreur" type="error" variant="tonal" class="mb-3">{{ form.erreur }}</v-alert>
          <v-text-field v-model="form.libelle" label="Nom *" variant="outlined" density="comfortable" autofocus />
          <v-select v-model="form.type" :items="TYPES" label="Type *" variant="outlined" density="comfortable"
            :disabled="!!form.id" :hint="form.id ? 'Le type fixe les comptes : il ne change plus après la création.' : `Comptes de rattachement : ${TYPES.find(t => t.value === form.type)?.comptes}`"
            persistent-hint class="mb-3" />
          <v-row dense>
            <v-col cols="12" sm="6">
              <v-text-field v-model.number="form.prixAchat" type="number" min="0" step="0.01" label="Prix d'achat (USD) *"
                prepend-inner-icon="mdi-currency-usd" variant="outlined" density="comfortable" />
            </v-col>
            <v-col cols="12" sm="6">
              <v-text-field v-model="form.uniteMesure" label="Unité (rame, litre, pièce…)" variant="outlined" density="comfortable" />
            </v-col>
          </v-row>
          <v-text-field v-model.number="form.stockMin" type="number" min="0" label="Seuil (stock minimum)" variant="outlined" density="comfortable"
            hint="Sous ce seuil, le consommable est signalé à réapprovisionner." persistent-hint class="mb-3" />
          <v-file-input v-model="form.proforma" label="Facture proforma (image ou PDF)" accept="image/jpeg,image/png,image/webp,application/pdf"
            prepend-icon="" prepend-inner-icon="mdi-file-document-outline" variant="outlined" density="comfortable" show-size
            :error-messages="proformaInvalide ? 'Image (JPEG, PNG, WebP) ou PDF de 15 Mo au plus.' : []"
            :hint="form.id ? 'Remplace la proforma actuelle si vous en choisissez une.' : 'Facultative.'" persistent-hint />
          <v-alert v-if="!form.id" type="info" variant="tonal" density="compact" class="mt-4">
            Le code et les comptes (stock et achat) sont créés automatiquement. La quantité entre en stock à l'achat, quand la
            caisse paie la note de frais.
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="form.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="form.enCours" :disabled="!formValide" @click="enregistrer">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- Achat groupé -->
    <v-dialog v-model="achat.ouvert" max-width="820" scrollable>
      <v-card class="classroom-card pa-2">
        <v-card-title>Acheter des consommables</v-card-title>
        <v-card-text>
          <v-alert v-if="achat.erreur" type="error" variant="tonal" class="mb-3">{{ achat.erreur }}</v-alert>
          <div v-for="(l, i) in achat.lignes" :key="i" class="ligne-achat">
            <v-autocomplete v-model="l.consommableId" :items="achetables.filter(c => !dejaChoisis(i).has(c.id))"
              item-value="id" :item-title="(c: Consommable) => `${c.libelle} — ${usd(c.prixAchat)}${c.uniteMesure ? ' / ' + c.uniteMesure : ''}`"
              label="Consommable *" variant="outlined" density="comfortable" hide-details class="ligne-achat__conso" />
            <v-text-field v-model.number="l.quantite" type="number" min="0" step="any" label="Quantité *" variant="outlined"
              density="comfortable" hide-details class="ligne-achat__qte"
              :suffix="l.consommableId ? (parId.get(l.consommableId)?.uniteMesure || '') : ''" />
            <div class="ligne-achat__montant">{{ usd(montantLigne(l)) }}</div>
            <v-btn icon="mdi-close" size="small" variant="text" :disabled="achat.lignes.length === 1" title="Retirer" @click="retirerLigne(i)" />
            <v-text-field v-model="l.observation" label="Observation (facultative)" variant="outlined" density="compact" hide-details class="ligne-achat__obs" />
          </div>
          <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-plus" class="mb-4" @click="ajouterLigne">Ajouter un consommable</v-btn>
          <v-text-field v-model="achat.objet" label="Objet de la note (facultatif)" placeholder="Achat de consommables : …" variant="outlined" density="comfortable" class="mb-2" />
          <v-textarea v-model="achat.justification" label="Justification si l'achat dépasse le budget (facultative)" rows="2" auto-grow variant="outlined" density="comfortable" />
          <div class="d-flex justify-space-between align-center mt-2">
            <span class="text-medium-emphasis text-body-2">Une note de frais est créée et soumise au DFIN, puis DA, DFIN et caisse.</span>
            <span class="text-h6 font-weight-bold">{{ usd(totalAchat) }}</span>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="achat.ouvert = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" prepend-icon="mdi-check" :loading="achat.enCours" :disabled="!achatValide" @click="validerAchat">
            Valider l'achat
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.kpi-label { font-size: 0.72rem; color: var(--ink-soft); text-transform: uppercase; letter-spacing: 0.5px; }
.kpi-value { font-size: 1.25rem; font-weight: 700; color: var(--ink); margin-top: 4px; }
.ligne-achat {
  display: grid; grid-template-columns: minmax(0, 1fr) 140px 110px 40px; gap: 8px; align-items: center;
  padding: 12px; margin-bottom: 10px; border-radius: 14px; background: color-mix(in srgb, var(--color-primary) 5%, transparent);
}
.ligne-achat__obs { grid-column: 1 / -1; }
.ligne-achat__montant { text-align: right; font-weight: 700; }
@media (max-width: 599px) {
  .ligne-achat { grid-template-columns: 1fr 1fr 40px; }
  .ligne-achat__conso { grid-column: 1 / -1; }
}
</style>
