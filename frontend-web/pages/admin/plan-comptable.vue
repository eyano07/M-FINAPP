<script setup lang="ts">
definePageMeta({ roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

import { useDisplay } from 'vuetify'

const auth = useAuthStore()
// Ajout/suppression de comptes reserves a ADMIN et DFIN (AdminService,
// ajouterCompte/supprimerCompte) : DFIN pilote le referentiel comptable au
// quotidien, ADMIN le supervise.
const peutGererComptes = computed(() => auth.hasAnyRole(['ADMIN', 'DFIN']))

// Bascule table <-> cartes sous 600px. Base sur la largeur reactive de
// Vuetify plutot que $vuetify.display.mobile (seuil global a 1280px, non
// adapte ici) : v-if/v-else evite de doubler le DOM sur une liste qui peut
// depasser 1300 comptes.
const { width } = useDisplay()
const esMobile = computed(() => width.value < 600)

interface Compte {
  id: number
  numero: string
  libelle: string
  type: string
  classe: number
  manuel: boolean
  parentNumero?: string
  /** true si le compte porte les rubriques du référentiel commenté. */
  commente?: boolean
}

/** Détail d'un compte, rubriques du référentiel SYSCOHADA incluses. */
interface CompteDetail extends Compte {
  parentLibelle?: string
  contenu?: string
  commentaires?: string
  fonctionnement?: string
  exclusions?: string
  controle?: string
}

const ALL_TYPES = ['ACTIF', 'PASSIF', 'CHARGE', 'PRODUIT']
const CLASSES = [
  { num: 1, label: 'Ressources durables' },
  { num: 2, label: 'Actif immobilisé' },
  { num: 3, label: 'Stocks' },
  { num: 4, label: 'Comptes de tiers' },
  { num: 5, label: 'Trésorerie' },
  { num: 6, label: 'Charges' },
  { num: 7, label: 'Produits' },
  { num: 8, label: 'Hors activités ordinaires' },
]

const api = useApi()
const loading = ref(false)
const saving = ref(false)
const deleting = ref<number | null>(null)
const erreur = ref('')
const succes = ref('')
const comptes = ref<Compte[]>([])
const search = ref('')
const filtreClasse = ref<number | null>(null)
const filtreType = ref<string | null>(null)
const dialog = ref(false)
const dialogDel = ref(false)
const comptePourSuppr = ref<Compte | null>(null)
const dialogEdit = ref(false)
const comptePourEdit = ref<Compte | null>(null)
const formEdit = reactive({ libelle: '' })
const savingEdit = ref(false)
const erreurEdit = ref('')

// ── Fiche du référentiel commenté ─────────────────────────────────────────
// Les rubriques pèsent plusieurs centaines de Ko sur l'ensemble du plan :
// elles ne sont chargées qu'à l'ouverture d'un compte, et mises en cache.
const dialogFiche = ref(false)
const ficheLoading = ref(false)
const fiche = ref<CompteDetail | null>(null)
const cacheFiches = new Map<number, CompteDetail>()

const RUBRIQUES = [
  { cle: 'contenu', titre: 'Contenu', icone: 'mdi-text-box-outline', couleur: '#4338ca' },
  { cle: 'commentaires', titre: 'Commentaires', icone: 'mdi-comment-quote-outline', couleur: '#7c3aed' },
  { cle: 'fonctionnement', titre: 'Fonctionnement', icone: 'mdi-swap-horizontal', couleur: '#0369a1' },
  { cle: 'exclusions', titre: 'Exclusions', icone: 'mdi-cancel', couleur: '#b91c1c' },
  { cle: 'controle', titre: 'Éléments de contrôle', icone: 'mdi-check-decagram-outline', couleur: '#15803d' },
] as const

const ficheRubriques = computed(() =>
  fiche.value
    ? RUBRIQUES.map((r) => ({ ...r, texte: (fiche.value as any)[r.cle] as string | undefined }))
        .filter((r) => !!r.texte)
    : []
)

async function ouvrirFiche(c: Compte) {
  dialogFiche.value = true
  const cachee = cacheFiches.get(c.id)
  if (cachee) {
    fiche.value = cachee
    return
  }
  fiche.value = null
  ficheLoading.value = true
  try {
    const detail = await api<CompteDetail>(`/comptes/${c.id}`)
    cacheFiches.set(c.id, detail)
    fiche.value = detail
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la fiche du compte.')
    dialogFiche.value = false
  } finally {
    ficheLoading.value = false
  }
}

/** Le référentiel présente les rubriques en paragraphes et en puces. */
function lignesRubrique(texte: string) {
  return texte.split('\n').map((l) => l.trim()).filter(Boolean)
    .map((l) => ({ puce: l.startsWith('•') || l.startsWith('–') || l.startsWith('-'),
                   texte: l.replace(/^[•–-]\s*/, '') }))
}

const form = reactive({ parentNumero: '', suffixe: '', libelle: '' })

// ── Compte parent sélectionné ─────────────────────────────────────────────
const parentChoisi = computed<Compte | null>(() =>
  form.parentNumero ? (comptes.value.find((c) => c.numero === form.parentNumero) ?? null) : null
)
const numeroPreview = computed(() =>
  form.parentNumero && form.suffixe.trim()
    ? `${form.parentNumero}.${form.suffixe.trim()}`
    : ''
)

// ── Chargement ────────────────────────────────────────────────────────────
async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    comptes.value = await api<Compte[]>('/comptes')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger le plan comptable.'
  } finally {
    loading.value = false
  }
}
onMounted(charger)

// ── Filtres ───────────────────────────────────────────────────────────────
const comptesFiltres = computed(() => {
  let list = comptes.value
  if (filtreClasse.value !== null) list = list.filter((c) => c.classe === filtreClasse.value)
  if (filtreType.value)            list = list.filter((c) => c.type === filtreType.value)
  if (search.value.trim()) {
    const s = search.value.toLowerCase()
    list = list.filter((c) => c.numero.includes(s) || c.libelle.toLowerCase().includes(s))
  }
  return list
})

// ── Dialog ────────────────────────────────────────────────────────────────
function ouvrirDialog() {
  form.parentNumero = ''
  form.suffixe = ''
  form.libelle = ''
  erreur.value = ''
  dialog.value = true
}

// ── Ajout ─────────────────────────────────────────────────────────────────
async function ajouter() {
  if (!form.parentNumero || !form.suffixe.trim() || !form.libelle.trim()) {
    erreur.value = 'Compte parent, suffixe et libellé sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    const c = await api<Compte>('/comptes', {
      method: 'POST',
      body: { parentNumero: form.parentNumero, suffixe: form.suffixe.trim(), libelle: form.libelle.trim() },
    })
    comptes.value.push(c)
    comptes.value.sort((a, b) => a.numero.localeCompare(b.numero))
    succes.value = `Compte ${fmtNumero(c.numero)} — ${c.libelle} créé.`
    dialog.value = false
  } catch (e: any) {
    erreur.value = e?.data?.message || "Échec de l'ajout."
  } finally {
    saving.value = false
  }
}

// ── Modification (renommage) ───────────────────────────────────────────────
function demanderEdit(c: Compte) {
  comptePourEdit.value = c
  formEdit.libelle = c.libelle
  erreurEdit.value = ''
  dialogEdit.value = true
}

async function confirmerEdit() {
  const c = comptePourEdit.value
  if (!c) return
  if (!formEdit.libelle.trim()) {
    erreurEdit.value = 'Le libellé est obligatoire.'
    return
  }
  savingEdit.value = true
  erreurEdit.value = ''
  try {
    const maj = await api<Compte>(`/comptes/${c.id}`, {
      method: 'PUT',
      body: { libelle: formEdit.libelle.trim() },
    })
    const i = comptes.value.findIndex((x) => x.id === c.id)
    if (i !== -1) comptes.value[i] = { ...comptes.value[i], libelle: maj.libelle }
    cacheFiches.delete(c.id)
    succes.value = `Compte ${fmtNumero(c.numero)} renommé.`
    dialogEdit.value = false
  } catch (e: any) {
    erreurEdit.value = e?.data?.message || 'Échec de la modification.'
  } finally {
    savingEdit.value = false
  }
}

// ── Suppression ───────────────────────────────────────────────────────────
function demanderSuppr(c: Compte) {
  comptePourSuppr.value = c
  dialogDel.value = true
}

async function confirmerSuppr() {
  const c = comptePourSuppr.value
  if (!c) return
  deleting.value = c.id
  dialogDel.value = false
  try {
    await api(`/comptes/${c.id}`, { method: 'DELETE' })
    comptes.value = comptes.value.filter((x) => x.id !== c.id)
    succes.value = `Compte ${fmtNumero(c.numero)} supprimé.`
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Échec de la suppression.'
  } finally {
    deleting.value = null
    comptePourSuppr.value = null
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────
const typeColor: Record<string, { bg: string; color: string }> = {
  ACTIF:   { bg: '#dbeafe', color: '#1d4ed8' },
  PASSIF:  { bg: '#fed7aa', color: '#c2410c' },
  CHARGE:  { bg: '#fee2e2', color: '#dc2626' },
  PRODUIT: { bg: '#dcfce7', color: '#16a34a' },
}
const classeLabel = (n: number) => CLASSES.find((c) => c.num === n)?.label ?? ''
const fmtNumero = (n: string) => {
  if (n.length <= 2) return n
  if (n[2] === '.') return n // point déjà en position 2 (ex: "10.11.1")
  return n.slice(0, 2) + '.' + n.slice(2) // "1011" → "10.11", "1011.1" → "10.11.1"
}

// Autocomplete parents : tri par numéro
const parentOptions = computed(() =>
  comptes.value
    .slice()
    .sort((a, b) => a.numero.localeCompare(b.numero))
    .map((c) => ({ title: `${fmtNumero(c.numero)} — ${c.libelle}`, value: c.numero }))
)
</script>

<template>
  <div>
    <!-- ── En-tête ───────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Plan comptable OHADA</h1>
        <p class="page-sub">Référentiel SYSCOHADA — {{ comptes.length }} comptes chargés</p>
      </div>
      <div class="pc-head-actions">
        <button class="pc-refresh-btn" :disabled="loading" @click="charger">
          <v-icon icon="mdi-refresh" size="16" />
        </button>
        <button v-if="peutGererComptes" class="pc-new-btn" @click="ouvrirDialog">
          <v-icon icon="mdi-plus" size="18" class="mr-1" />
          Ajouter un compte
        </button>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <!-- ── Filtres ────────────────────────────────────────────── -->
    <div class="pc-filters">
      <div class="pc-search">
        <v-icon icon="mdi-magnify" size="16" color="#9ca3af" class="pc-search__icon" />
        <input v-model="search" class="pc-search__input" placeholder="Numéro ou libellé…" />
      </div>
      <div class="pc-chips">
        <button class="pc-chip" :class="{ 'pc-chip--on': filtreType === null }" @click="filtreType = null">Tous types</button>
        <button v-for="t in ALL_TYPES" :key="t" class="pc-chip"
          :class="{ 'pc-chip--on': filtreType === t }"
          :style="filtreType === t ? { background: typeColor[t].bg, color: typeColor[t].color, borderColor: typeColor[t].color + '55' } : {}"
          @click="filtreType = filtreType === t ? null : t">{{ t }}</button>
      </div>
      <div class="pc-chips">
        <button class="pc-chip" :class="{ 'pc-chip--on': filtreClasse === null }" @click="filtreClasse = null">Toutes classes</button>
        <button v-for="c in CLASSES" :key="c.num" class="pc-chip"
          :class="{ 'pc-chip--on': filtreClasse === c.num }"
          @click="filtreClasse = filtreClasse === c.num ? null : c.num"
          :title="c.label">Cl. {{ c.num }}</button>
      </div>
    </div>

    <p class="pc-count">{{ comptesFiltres.length }} compte{{ comptesFiltres.length !== 1 ? 's' : '' }} affiché{{ comptesFiltres.length !== 1 ? 's' : '' }}</p>

    <!-- ── Tableau ────────────────────────────────────────────── -->
    <div class="pc-card">
      <div v-if="loading" class="pc-loading">
        <v-progress-circular indeterminate color="primary" size="28" />
      </div>
      <div v-else-if="comptesFiltres.length === 0" class="pc-empty">
        <v-icon icon="mdi-book-off-outline" size="40" color="#d1d5db" />
        <p>Aucun compte trouvé.</p>
      </div>
      <div v-else-if="!esMobile" class="pc-table-scroll">
      <table class="pc-table">
        <thead>
          <tr>
            <th>Numéro</th>
            <th>Libellé</th>
            <th>Type</th>
            <th>Classe</th>
            <th class="pc-th-action"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="c in comptesFiltres"
            :key="c.id"
            :class="{ 'pc-row--manuel': c.manuel, 'pc-row--commente': c.commente }"
            @click="c.commente && ouvrirFiche(c)"
          >
            <td>
              <div class="pc-num-cell">
                <span class="pc-numero">{{ fmtNumero(c.numero) }}</span>
                <span v-if="c.manuel" class="pc-manuel-badge">Manuel</span>
              </div>
            </td>
            <td class="pc-libelle">
              {{ c.libelle }}
              <span v-if="c.commente" class="pc-fiche-badge" title="Fiche du référentiel SYSCOHADA disponible">
                <v-icon icon="mdi-book-open-page-variant-outline" size="13" />
                Fiche
              </span>
              <span v-if="c.parentNumero" class="pc-parent-hint">↳ {{ fmtNumero(c.parentNumero) }}</span>
            </td>
            <td>
              <span class="pc-type-chip"
                :style="{ background: typeColor[c.type]?.bg ?? '#f3f4f6', color: typeColor[c.type]?.color ?? '#374151' }">
                {{ c.type }}
              </span>
            </td>
            <td><span class="pc-classe">{{ c.classe }} — {{ classeLabel(c.classe) }}</span></td>
            <td>
              <div v-if="c.manuel && peutGererComptes" class="pc-row-actions">
                <button class="pc-edit-btn" @click.stop="demanderEdit(c)" title="Modifier le libellé">
                  <v-icon icon="mdi-pencil-outline" size="16" />
                </button>
                <button class="pc-del-btn" :disabled="deleting === c.id" @click.stop="demanderSuppr(c)" title="Supprimer">
                  <v-progress-circular v-if="deleting === c.id" indeterminate size="14" width="2" color="currentColor" />
                  <v-icon v-else icon="mdi-delete-outline" size="16" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      </div>

      <!-- ── Cartes (mobile) ──────────────────────────────────── -->
      <div v-else class="pc-cards">
        <div
          v-for="c in comptesFiltres"
          :key="c.id"
          class="pc-card-item"
          :class="{ 'pc-card-item--manuel': c.manuel, 'pc-card-item--commente': c.commente }"
          @click="c.commente && ouvrirFiche(c)"
        >
          <div class="pc-card-item__top">
            <span class="pc-numero">{{ fmtNumero(c.numero) }}</span>
            <span class="pc-type-chip"
              :style="{ background: typeColor[c.type]?.bg ?? '#f3f4f6', color: typeColor[c.type]?.color ?? '#374151' }">
              {{ c.type }}
            </span>
          </div>

          <p class="pc-card-item__libelle">
            {{ c.libelle }}
            <span v-if="c.manuel" class="pc-manuel-badge">Manuel</span>
          </p>
          <div v-if="c.commente || c.parentNumero" class="pc-card-item__tags">
            <span v-if="c.commente" class="pc-fiche-badge">
              <v-icon icon="mdi-book-open-page-variant-outline" size="13" />
              Fiche
            </span>
            <span v-if="c.parentNumero" class="pc-parent-hint">↳ {{ fmtNumero(c.parentNumero) }}</span>
          </div>

          <div class="pc-card-item__footer">
            <span class="pc-classe">Cl. {{ c.classe }} — {{ classeLabel(c.classe) }}</span>
            <div v-if="c.manuel && peutGererComptes" class="pc-row-actions">
              <button class="pc-edit-btn" @click.stop="demanderEdit(c)" title="Modifier le libellé">
                <v-icon icon="mdi-pencil-outline" size="16" />
              </button>
              <button class="pc-del-btn" :disabled="deleting === c.id" @click.stop="demanderSuppr(c)" title="Supprimer">
                <v-progress-circular v-if="deleting === c.id" indeterminate size="14" width="2" color="currentColor" />
                <v-icon v-else icon="mdi-delete-outline" size="16" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Dialog modification ─────────────────────────────── -->
    <v-dialog v-model="dialogEdit" max-width="460">
      <div class="pc-dialog">
        <div class="pc-dialog__head">
          <div class="pc-dialog__blob pc-dialog__blob--a" />
          <div class="pc-dialog__blob pc-dialog__blob--b" />
          <div class="pc-dialog__icon">
            <v-icon icon="mdi-pencil-outline" size="22" color="white" />
          </div>
          <div class="pc-dialog__head-text">
            <p class="pc-dialog__head-title">Modifier le libellé</p>
            <p class="pc-dialog__head-sub" v-if="comptePourEdit">{{ fmtNumero(comptePourEdit.numero) }} — numéro et classe inchangés</p>
          </div>
          <button class="pc-dialog__close" @click="dialogEdit = false">
            <v-icon icon="mdi-close" size="18" color="rgba(255,255,255,0.75)" />
          </button>
        </div>

        <div class="pc-dialog__body">
          <v-alert v-if="erreurEdit" type="error" variant="tonal" rounded="lg" density="compact" class="mb-3" closable @click:close="erreurEdit = ''">
            {{ erreurEdit }}
          </v-alert>

          <div class="pc-field">
            <label class="pc-label">Libellé *</label>
            <v-text-field
              v-model="formEdit.libelle"
              placeholder="Ex: Transports Administratifs"
              prepend-inner-icon="mdi-text"
              hide-details="auto"
              autofocus
              @keyup.enter="confirmerEdit"
            />
          </div>
        </div>

        <div class="pc-dialog__footer">
          <button class="pc-cancel-btn" :disabled="savingEdit" @click="dialogEdit = false">Annuler</button>
          <button class="pc-submit-btn" :disabled="savingEdit || !formEdit.libelle.trim()" @click="confirmerEdit">
            <v-progress-circular v-if="savingEdit" indeterminate size="16" width="2" color="white" class="mr-2" />
            <v-icon v-else icon="mdi-content-save-outline" size="17" class="mr-1" />
            Enregistrer
          </button>
        </div>
      </div>
    </v-dialog>

    <!-- ── Dialog suppression ───────────────────────────────── -->
    <v-dialog v-model="dialogDel" max-width="420">
      <div class="pc-dialog">
        <div class="pc-dialog__head pc-dialog__head--danger">
          <div class="pc-dialog__blob pc-dialog__blob--a" />
          <div class="pc-dialog__blob pc-dialog__blob--b" />
          <div class="pc-dialog__icon pc-dialog__icon--danger">
            <v-icon icon="mdi-delete-alert-outline" size="22" color="white" />
          </div>
          <div class="pc-dialog__head-text">
            <p class="pc-dialog__head-title">Supprimer le compte</p>
            <p class="pc-dialog__head-sub">Cette action est irréversible</p>
          </div>
          <button class="pc-dialog__close" @click="dialogDel = false">
            <v-icon icon="mdi-close" size="18" color="rgba(255,255,255,0.75)" />
          </button>
        </div>
        <div class="pc-del-body" v-if="comptePourSuppr">
          <div class="pc-del-compte-card">
            <span class="pc-numero">{{ fmtNumero(comptePourSuppr.numero) }}</span>
            <span class="pc-del-libelle">{{ comptePourSuppr.libelle }}</span>
          </div>
          <p class="pc-del-warning">
            <v-icon icon="mdi-alert-circle-outline" size="15" color="#f59e0b" class="mr-1" />
            Ce compte manuel sera définitivement supprimé du plan comptable.
          </p>
        </div>
        <div class="pc-dialog__footer">
          <button class="pc-cancel-btn" @click="dialogDel = false">Annuler</button>
          <button class="pc-del-confirm-btn" :disabled="deleting !== null" @click="confirmerSuppr">
            <v-progress-circular v-if="deleting !== null" indeterminate size="16" width="2" color="white" class="mr-2" />
            <v-icon v-else icon="mdi-delete-outline" size="17" class="mr-1" />
            Supprimer
          </button>
        </div>
      </div>
    </v-dialog>

    <!-- ── Dialog ajout ──────────────────────────────────────── -->
    <v-dialog v-model="dialog" max-width="560">
      <div class="pc-dialog">
        <div class="pc-dialog__head">
          <div class="pc-dialog__blob pc-dialog__blob--a" />
          <div class="pc-dialog__blob pc-dialog__blob--b" />
          <div class="pc-dialog__icon">
            <v-icon icon="mdi-book-plus-outline" size="22" color="white" />
          </div>
          <div class="pc-dialog__head-text">
            <p class="pc-dialog__head-title">Ajouter un sous-compte</p>
            <p class="pc-dialog__head-sub">Le nouveau compte hérite du type et de la classe de son parent</p>
          </div>
          <button class="pc-dialog__close" @click="dialog = false">
            <v-icon icon="mdi-close" size="18" color="rgba(255,255,255,0.75)" />
          </button>
        </div>

        <div class="pc-dialog__body">
          <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-3" closable @click:close="erreur = ''">
            {{ erreur }}
          </v-alert>

          <!-- Compte parent -->
          <div class="pc-field">
            <label class="pc-label">Compte parent *</label>
            <v-autocomplete
              v-model="form.parentNumero"
              :items="parentOptions"
              item-title="title"
              item-value="value"
              placeholder="Rechercher un compte parent…"
              prepend-inner-icon="mdi-sitemap-outline"
              hide-details="auto"
              clearable
            />
          </div>

          <!-- Héritage affiché -->
          <div v-if="parentChoisi" class="pc-inherit-card">
            <div class="pc-inherit-row">
              <span class="pc-inherit-label">Type hérité</span>
              <span class="pc-type-chip"
                :style="{ background: typeColor[parentChoisi.type]?.bg, color: typeColor[parentChoisi.type]?.color }">
                {{ parentChoisi.type }}
              </span>
            </div>
            <div class="pc-inherit-row">
              <span class="pc-inherit-label">Classe héritée</span>
              <span class="pc-inherit-val">{{ parentChoisi.classe }} — {{ classeLabel(parentChoisi.classe) }}</span>
            </div>
          </div>

          <!-- Suffixe -->
          <div class="pc-field">
            <label class="pc-label">Suffixe du nouveau compte *</label>
            <v-text-field
              v-model="form.suffixe"
              placeholder="Ex: 1, 2, A…"
              prepend-inner-icon="mdi-numeric-positive-1"
              hide-details="auto"
              :hint="numeroPreview ? `Numéro final : ${fmtNumero(numeroPreview)}` : ''"
              persistent-hint
            />
          </div>

          <!-- Libellé -->
          <div class="pc-field">
            <label class="pc-label">Libellé *</label>
            <v-text-field
              v-model="form.libelle"
              placeholder="Ex: Fournitures de bureau — Papeterie"
              prepend-inner-icon="mdi-text"
              hide-details="auto"
            />
          </div>
        </div>

        <div class="pc-dialog__footer">
          <button class="pc-cancel-btn" :disabled="saving" @click="dialog = false">Annuler</button>
          <button class="pc-submit-btn" :disabled="saving || !form.parentNumero" @click="ajouter">
            <v-progress-circular v-if="saving" indeterminate size="16" width="2" color="white" class="mr-2" />
            <v-icon v-else icon="mdi-content-save-outline" size="17" class="mr-1" />
            Créer le compte
          </button>
        </div>
      </div>
    </v-dialog>

    <!-- ── Fiche du référentiel SYSCOHADA commenté ─────────────────────── -->
    <v-dialog v-model="dialogFiche" max-width="820" scrollable>
      <div class="pc-fiche">
        <div class="pc-fiche__head">
          <div>
            <p class="pc-fiche__eyebrow">Référentiel SYSCOHADA — AUDCIF 2017</p>
            <p class="pc-fiche__title">
              <span class="pc-fiche__num">{{ fiche ? fmtNumero(fiche.numero) : '' }}</span>
              {{ fiche?.libelle }}
            </p>
            <p v-if="fiche?.parentNumero" class="pc-fiche__parent">
              ↳ rattaché à {{ fmtNumero(fiche.parentNumero) }} — {{ fiche.parentLibelle }}
            </p>
          </div>
          <button class="pc-dialog__close" @click="dialogFiche = false">
            <v-icon icon="mdi-close" size="18" />
          </button>
        </div>

        <div class="pc-fiche__body">
          <div v-if="ficheLoading" class="pc-fiche__loading">
            <v-progress-circular indeterminate size="28" color="primary" />
          </div>

          <template v-else-if="fiche">
            <section v-for="r in ficheRubriques" :key="r.cle" class="pc-rub">
              <p class="pc-rub__titre" :style="{ color: r.couleur }">
                <v-icon :icon="r.icone" size="15" class="mr-1" />{{ r.titre }}
              </p>
              <div class="pc-rub__corps" :style="{ borderColor: r.couleur + '33' }">
                <template v-for="(l, i) in lignesRubrique(r.texte!)" :key="i">
                  <li v-if="l.puce" class="pc-rub__puce">{{ l.texte }}</li>
                  <p v-else class="pc-rub__para">{{ l.texte }}</p>
                </template>
              </div>
            </section>

            <div v-if="ficheRubriques.length === 0" class="pc-fiche__vide">
              Ce compte ne porte pas de commentaire dans le référentiel.
            </div>
          </template>
        </div>
      </div>
    </v-dialog>
  </div>
</template>

<style scoped>
/* ── Fiche du référentiel ────────────────────────────────────────────────── */
.pc-fiche { background: #fff; border-radius: 18px; overflow: hidden; }
.pc-fiche__head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  padding: 22px 24px 18px;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 100%);
  border-bottom: 1px solid #e5e7eb;
}
.pc-fiche__eyebrow {
  font-size: 0.66rem; font-weight: 700; letter-spacing: 0.7px; text-transform: uppercase;
  color: #6366f1; margin: 0 0 6px;
}
.pc-fiche__title { font-size: 1.08rem; font-weight: 700; color: #111827; margin: 0; }
.pc-fiche__num {
  display: inline-block; font-family: ui-monospace, monospace; font-size: 0.95rem;
  background: #4338ca; color: #fff; border-radius: 6px; padding: 1px 8px; margin-right: 8px;
}
.pc-fiche__parent { font-size: 0.78rem; color: #6b7280; margin: 6px 0 0; }
.pc-fiche__body { padding: 20px 24px 26px; max-height: 68vh; overflow-y: auto; }
.pc-fiche__loading { display: flex; justify-content: center; padding: 40px; }
.pc-fiche__vide { color: #9ca3af; font-size: 0.86rem; text-align: center; padding: 24px; }

.pc-rub { margin-bottom: 18px; }
.pc-rub__titre {
  display: flex; align-items: center;
  font-size: 0.7rem; font-weight: 800; letter-spacing: 0.6px;
  text-transform: uppercase; margin: 0 0 7px;
}
.pc-rub__corps {
  border-left: 3px solid; border-radius: 0 10px 10px 0;
  background: #f9fafb; padding: 12px 16px;
}
.pc-rub__para { font-size: 0.85rem; color: #374151; margin: 0 0 7px; line-height: 1.55; }
.pc-rub__para:last-child { margin-bottom: 0; }
.pc-rub__puce {
  font-size: 0.85rem; color: #374151; line-height: 1.55;
  margin: 0 0 5px 18px; list-style: disc;
}

.pc-row--commente { cursor: pointer; }
.pc-row--commente:hover { background: #f5f3ff; }
.pc-fiche-badge {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 0.62rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px;
  color: #6d28d9; background: #ede9fe; border: 1px solid #ddd6fe;
  border-radius: 5px; padding: 1px 6px; margin-left: 8px; vertical-align: middle;
}

/* ── Header ──────────────────────────────────────────────────────────────── */
.pc-head-actions { display: flex; align-items: center; gap: 10px; }
.pc-refresh-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 40px; height: 40px; border-radius: 11px;
  background: #f3f4f6; border: 1px solid #e5e7eb; color: #6b7280;
  cursor: pointer; transition: background 0.15s;
}
.pc-refresh-btn:hover:not(:disabled) { background: #e5e7eb; }
.pc-refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.pc-new-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 0 20px; height: 40px; border-radius: 11px;
  background: #16a34a; color: #fff; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(22,163,74,0.25);
}
.pc-new-btn:hover { background: #15803d; }

/* ── Filtres ─────────────────────────────────────────────────────────────── */
.pc-filters { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 14px; align-items: flex-start; }
.pc-search {
  display: flex; align-items: center; gap: 8px;
  background: #fff; border: 1px solid #e5e7eb; border-radius: 11px;
  padding: 0 14px; height: 40px; min-width: 200px; flex: 1; max-width: 300px;
}
.pc-search__icon { flex-shrink: 0; }
.pc-search__input { flex: 1; border: none; outline: none; font-size: 0.875rem; color: #111827; background: transparent; }
.pc-search__input::placeholder { color: #9ca3af; }
.pc-chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.pc-chip {
  padding: 4px 12px; border-radius: 100px; font-size: 0.75rem; font-weight: 600;
  border: 1px solid #e5e7eb; background: #f9fafb; color: #6b7280;
  cursor: pointer; transition: all 0.15s;
}
.pc-chip:hover { border-color: #d1d5db; background: #f3f4f6; }
.pc-chip--on   { background: #16a34a; color: #fff; border-color: #16a34a; }
.pc-count { font-size: 0.8rem; color: #9ca3af; margin: 0 0 12px; font-weight: 500; }

/* ── Table card ──────────────────────────────────────────────────────────── */
.pc-card { background: #fff; border: 1px solid #f0f0f0; border-radius: 18px; overflow: hidden; }
.pc-loading, .pc-empty {
  display: flex; flex-direction: column; align-items: center;
  gap: 10px; padding: 48px 16px; color: #9ca3af; font-size: 0.85rem;
}
.pc-empty p { margin: 0; }
.pc-table-scroll { overflow-x: auto; }
.pc-table { width: 100%; min-width: 560px; border-collapse: collapse; font-size: 0.875rem; }
.pc-table thead tr { border-bottom: 1px solid #f3f4f6; }
.pc-table th {
  padding: 12px 16px; font-size: 0.72rem; font-weight: 700;
  letter-spacing: 0.4px; text-transform: uppercase; color: #9ca3af; text-align: left;
}
.pc-th-action { width: 48px; }
.pc-table tbody tr { border-bottom: 1px solid #f9fafb; transition: background 0.12s; }
.pc-table tbody tr:last-child { border-bottom: none; }
.pc-table tbody tr:hover { background: #fafafa; }
.pc-row--manuel { background: #f0fdf4; }
.pc-row--manuel:hover { background: #dcfce7 !important; }
.pc-table td { padding: 10px 16px; vertical-align: middle; }

.pc-num-cell { display: flex; align-items: center; gap: 6px; }
.pc-numero {
  font-family: 'JetBrains Mono', 'Courier New', monospace;
  font-size: 0.875rem; font-weight: 700; color: #111827;
  background: #f9fafb; border: 1px solid #f0f0f0;
  padding: 2px 8px; border-radius: 6px; display: inline-block;
}
.pc-manuel-badge {
  font-size: 0.6rem; font-weight: 700; padding: 1px 6px;
  border-radius: 100px; background: #dcfce7; color: #16a34a;
  border: 1px solid #bbf7d0; letter-spacing: 0.3px;
}
.pc-libelle { color: #374151; }
.pc-parent-hint { font-size: 0.72rem; color: #9ca3af; margin-left: 6px; font-family: monospace; }
.pc-type-chip {
  display: inline-block; font-size: 0.65rem; font-weight: 700;
  padding: 3px 9px; border-radius: 100px; letter-spacing: 0.3px;
}
.pc-classe { font-size: 0.78rem; color: #6b7280; }

.pc-row-actions { display: inline-flex; align-items: center; gap: 6px; }

.pc-del-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border-radius: 8px;
  background: #fef2f2; color: #ef4444; border: 1px solid #fecaca;
  cursor: pointer; transition: background 0.15s;
  flex-shrink: 0;
}
.pc-del-btn:hover:not(:disabled) { background: #fee2e2; }
.pc-del-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.pc-edit-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 30px; height: 30px; border-radius: 8px;
  background: #eff6ff; color: #2563eb; border: 1px solid #bfdbfe;
  cursor: pointer; transition: background 0.15s;
  flex-shrink: 0;
}
.pc-edit-btn:hover { background: #dbeafe; }

/* ── Cartes (mobile) ─────────────────────────────────────────────────────── */
.pc-cards { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.pc-card-item {
  background: #fff;
  border: 1px solid #eef0f2;
  border-left: 3px solid #16a34a;
  border-radius: 16px;
  padding: 14px 16px;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.06), 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: box-shadow 0.15s, transform 0.15s;
}
.pc-card-item--manuel { background: #f7fdf9; border-color: #dcfce7; border-left-color: #16a34a; }
.pc-card-item--commente { cursor: pointer; }
.pc-card-item--commente:active { transform: scale(0.99); box-shadow: 0 1px 2px rgba(0,0,0,0.04); }

.pc-card-item__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.pc-card-item__libelle {
  font-size: 0.9rem;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
  margin: 0;
}
.pc-card-item__tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.pc-card-item__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f6f6f6;
}

/* ── Dialog ──────────────────────────────────────────────────────────────── */
.pc-dialog { background: #fff; border-radius: 20px; overflow: hidden; }
.pc-dialog__head {
  position: relative; overflow: hidden;
  display: flex; align-items: center; gap: 14px; padding: 22px;
  background: linear-gradient(140deg, #22c55e 0%, #16a34a 50%, #14532d 100%);
}
.pc-dialog__head--danger {
  background: linear-gradient(140deg, #f87171 0%, #dc2626 50%, #7f1d1d 100%);
}
.pc-dialog__icon--danger {
  background: rgba(255,255,255,0.18) !important;
}
/* ── Delete body ─────────────────────────────────────────────────────────── */
.pc-del-body { padding: 22px; display: flex; flex-direction: column; gap: 14px; }
.pc-del-compte-card {
  display: flex; align-items: center; gap: 12px;
  background: #fef2f2; border: 1px solid #fecaca;
  border-radius: 12px; padding: 14px 16px;
}
.pc-del-libelle { font-size: 0.9rem; font-weight: 600; color: #374151; }
.pc-del-warning {
  display: flex; align-items: flex-start; gap: 4px;
  font-size: 0.8rem; color: #92400e;
  background: #fffbeb; border: 1px solid #fde68a;
  border-radius: 10px; padding: 10px 14px; margin: 0;
}
.pc-del-confirm-btn {
  display: inline-flex; align-items: center;
  padding: 0 22px; height: 40px; border-radius: 10px;
  background: #dc2626; color: #fff; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(220,38,38,0.30);
}
.pc-del-confirm-btn:hover:not(:disabled) { background: #b91c1c; }
.pc-del-confirm-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.pc-dialog__blob { position: absolute; border-radius: 50%; background: rgba(255,255,255,0.10); pointer-events: none; }
.pc-dialog__blob--a { width: 150px; height: 150px; top: -40px; right: -30px; }
.pc-dialog__blob--b { width: 70px;  height: 70px;  bottom: -20px; left: 50px; }
.pc-dialog__icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 46px; height: 46px; border-radius: 13px;
  background: rgba(255,255,255,0.18); backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22); flex-shrink: 0; position: relative; z-index: 1;
}
.pc-dialog__head-text { flex: 1; position: relative; z-index: 1; }
.pc-dialog__head-title { font-size: 1rem; font-weight: 700; color: #fff; margin: 0 0 3px; }
.pc-dialog__head-sub   { font-size: 0.78rem; color: rgba(255,255,255,0.72); margin: 0; }
.pc-dialog__close {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(255,255,255,0.12); border: none; cursor: pointer;
  transition: background 0.15s; position: relative; z-index: 1;
}
.pc-dialog__close:hover { background: rgba(255,255,255,0.22); }

.pc-dialog__body { padding: 22px; display: flex; flex-direction: column; gap: 16px; }
.pc-field { display: flex; flex-direction: column; gap: 6px; }
.pc-label { font-size: 0.8125rem; font-weight: 600; color: #374151; }

/* Carte d'héritage */
.pc-inherit-card {
  background: #f9fafb; border: 1px solid #f0f0f0; border-radius: 12px;
  padding: 12px 16px; display: flex; flex-direction: column; gap: 8px;
}
.pc-inherit-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.pc-inherit-label { font-size: 0.78rem; font-weight: 600; color: #9ca3af; }
.pc-inherit-val { font-size: 0.8rem; font-weight: 600; color: #374151; }

.pc-dialog__footer {
  display: flex; align-items: center; justify-content: flex-end; gap: 10px;
  padding: 16px 22px; border-top: 1px solid #f3f4f6;
}
.pc-cancel-btn {
  padding: 0 18px; height: 40px; border-radius: 10px;
  background: #f3f4f6; color: #374151; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer; transition: background 0.15s;
}
.pc-cancel-btn:hover:not(:disabled) { background: #e5e7eb; }
.pc-cancel-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.pc-submit-btn {
  display: inline-flex; align-items: center;
  padding: 0 22px; height: 40px; border-radius: 10px;
  background: #16a34a; color: #fff; font-size: 0.875rem; font-weight: 600;
  border: none; cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(22,163,74,0.25);
}
.pc-submit-btn:hover:not(:disabled) { background: #15803d; }
.pc-submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
