<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Editeur visuel du plan des salles et tables (façon Odoo Point de Vente) :
 * glisser pour déplacer une table (aimantée sur la grille), poignées aux
 * coins pour la redimensionner. "occupée" est un statut manuel (présence de
 * clients) ; aCommandeNonPayee/aCommandePayee sont dérivés côté serveur des
 * ventes rattachées à la table (voir ventes/nouvelle.vue) et purement
 * informatifs ici.
 */
interface TableItem {
  id: number | null
  numero: string
  forme: 'CARRE' | 'ROND'
  posX: number
  posY: number
  largeur: number
  hauteur: number
  nbChaises: number
  occupee: boolean
  aCommandeNonPayee: boolean
  aCommandePayee: boolean
}
interface LigneCommande {
  designation: string
  quantite: number
  prixUnitaire: number
  montantTtc: number
}
interface Commande {
  id: number
  reference: string
  totalTtc: number
  devise: string
  statut: string
  reglee: boolean
  lignes: LigneCommande[]
}
interface Etablissement { id: number; nom: string }
interface Salle {
  id: number
  nom: string
  ordre: number
  actif: boolean
  tables: TableItem[]
}

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })
/** Disposition du plan (position, taille, ajout/suppression de tables) : reservee a l'administrateur. */
const canWrite = computed(() => auth.hasRole('ADMIN'))
/** Statut occupee/libre : action du quotidien, ouverte au responsable restaurant. */
const peutGererStatut = computed(() => auth.hasAnyRole(['ADMIN', 'RESP_RESTAURANT']))

/** Pas de la grille magnetique (px) — doit rester egal a --plan-grille dans le CSS ci-dessous. */
const PAS_GRILLE = 22
const aimanter = (v: number) => Math.round(v / PAS_GRILLE) * PAS_GRILLE

const salles = ref<Salle[]>([])
const salleActiveId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

const tablesEnCours = ref<TableItem[]>([])
const selectionIndex = ref<number | null>(null)
const modifie = ref(false)

const salleActive = computed(() => salles.value.find(s => s.id === salleActiveId.value) || null)
const tableSelectionnee = computed(() =>
  selectionIndex.value !== null ? tablesEnCours.value[selectionIndex.value] ?? null : null)

// ── Commandes de la table selectionnee ────────────────────────────────────
const commandesTable = ref<Commande[]>([])
const chargeantCommandes = ref(false)
const commandeOuverteId = ref<number | null>(null)

watch(() => tableSelectionnee.value?.id, async (id) => {
  commandesTable.value = []
  commandeOuverteId.value = null
  if (!id) return
  chargeantCommandes.value = true
  try {
    commandesTable.value = await api<Commande[]>(`/restaurant/tables/${id}/ventes`)
  } catch {
    // Best-effort : l'absence de commandes affichees n'empeche pas de gerer la table.
  } finally {
    chargeantCommandes.value = false
  }
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const data = await api<Salle[]>('/restaurant/salles')
    salles.value = data
    if (salleActiveId.value === null || !data.some(s => s.id === salleActiveId.value)) {
      salleActiveId.value = data.length ? data[0].id : null
    }
    rechargerTablesEnCours()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les salles.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

function rechargerTablesEnCours() {
  tablesEnCours.value = salleActive.value ? salleActive.value.tables.map(t => ({ ...t })) : []
  selectionIndex.value = null
  modifie.value = false
}

function choisirSalle(id: number) {
  if (id === salleActiveId.value) return
  if (modifie.value && !confirm('Les modifications non enregistrées de cette salle seront perdues. Continuer ?')) {
    return
  }
  salleActiveId.value = id
  rechargerTablesEnCours()
}

// ── Gestion des salles (créer / renommer / supprimer) ────────────────────
const dialogSalle = ref(false)
const editSalleId = ref<number | null>(null)
const formSalle = reactive({ nom: '' })
const savingSalle = ref(false)

function ouvrirCreationSalle() {
  editSalleId.value = null
  formSalle.nom = ''
  erreur.value = ''
  dialogSalle.value = true
}
function ouvrirEditionSalle(s: Salle) {
  editSalleId.value = s.id
  formSalle.nom = s.nom
  erreur.value = ''
  dialogSalle.value = true
}
async function enregistrerSalle() {
  if (!formSalle.nom.trim()) {
    erreur.value = 'Le nom de la salle est obligatoire.'
    return
  }
  savingSalle.value = true
  erreur.value = ''
  try {
    const body = { nom: formSalle.nom, ordre: salles.value.length, actif: true }
    if (editSalleId.value) {
      await api(`/restaurant/salles/${editSalleId.value}`, { method: 'PUT', body })
    } else {
      const cree = await api<Salle>('/restaurant/salles', { method: 'POST', body })
      salleActiveId.value = cree.id
    }
    dialogSalle.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement de la salle.")
  } finally {
    savingSalle.value = false
  }
}
async function supprimerSalleActive() {
  if (!salleActive.value) return
  if (!confirm(`Supprimer la salle « ${salleActive.value.nom} » et toutes ses tables ?`)) return
  erreur.value = ''
  try {
    await api(`/restaurant/salles/${salleActive.value.id}`, { method: 'DELETE' })
    salleActiveId.value = null
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la suppression.')
  }
}

// ── Edition du plan : ajout / suppression / sélection ────────────────────
function ajouterTable() {
  const decalage = (tablesEnCours.value.length * 24) % 220
  tablesEnCours.value.push({
    id: null,
    numero: String(tablesEnCours.value.length + 1),
    forme: 'CARRE',
    posX: aimanter(24 + decalage),
    posY: aimanter(24 + decalage),
    largeur: 100,
    hauteur: 100,
    nbChaises: 4,
    occupee: false,
    aCommandeNonPayee: false,
    aCommandePayee: false,
  })
  selectionIndex.value = tablesEnCours.value.length - 1
  modifie.value = true
}

function supprimerTableSelectionnee() {
  if (selectionIndex.value === null) return
  tablesEnCours.value.splice(selectionIndex.value, 1)
  selectionIndex.value = null
  modifie.value = true
}

// ── Glisser pour déplacer ─────────────────────────────────────────────────
let drag: { index: number; startX: number; startY: number; origX: number; origY: number } | null = null

function demarrerDeplacement(e: MouseEvent, i: number) {
  // Toujours selectionner (le responsable restaurant doit pouvoir choisir une
  // table pour en changer le statut), meme s'il ne peut pas la deplacer.
  selectionIndex.value = i
  if (!canWrite.value) return
  drag = { index: i, startX: e.clientX, startY: e.clientY, origX: tablesEnCours.value[i].posX, origY: tablesEnCours.value[i].posY }
  window.addEventListener('mousemove', surDeplacement)
  window.addEventListener('mouseup', arreterDeplacement)
}
function surDeplacement(e: MouseEvent) {
  if (!drag) return
  const t = tablesEnCours.value[drag.index]
  t.posX = aimanter(Math.max(0, drag.origX + (e.clientX - drag.startX)))
  t.posY = aimanter(Math.max(0, drag.origY + (e.clientY - drag.startY)))
  modifie.value = true
}
function arreterDeplacement() {
  drag = null
  window.removeEventListener('mousemove', surDeplacement)
  window.removeEventListener('mouseup', arreterDeplacement)
}

// ── Poignées pour redimensionner ──────────────────────────────────────────
const TAILLE_MIN = 40
let resize: { index: number; coin: string; startX: number; startY: number; orig: TableItem } | null = null

function demarrerRedimension(e: MouseEvent, i: number, coin: string) {
  if (!canWrite.value) return
  selectionIndex.value = i
  resize = { index: i, coin, startX: e.clientX, startY: e.clientY, orig: { ...tablesEnCours.value[i] } }
  window.addEventListener('mousemove', surRedimension)
  window.addEventListener('mouseup', arreterRedimension)
}
function surRedimension(e: MouseEvent) {
  if (!resize) return
  const dx = e.clientX - resize.startX
  const dy = e.clientY - resize.startY
  const t = tablesEnCours.value[resize.index]
  const { orig, coin } = resize
  // Le coin mobile est aimante sur la grille ; le coin oppose (fixe) ne
  // bouge pas — c'est lui qui sert de reference pour deduire la nouvelle
  // largeur/hauteur, plutot que d'aimanter largeur/hauteur independamment.
  if (coin.includes('e')) {
    t.largeur = Math.max(TAILLE_MIN, aimanter(orig.posX + orig.largeur + dx) - orig.posX)
  }
  if (coin.includes('s')) {
    t.hauteur = Math.max(TAILLE_MIN, aimanter(orig.posY + orig.hauteur + dy) - orig.posY)
  }
  if (coin.includes('w')) {
    const coinFixeX = orig.posX + orig.largeur
    const nvLargeur = Math.max(TAILLE_MIN, coinFixeX - aimanter(orig.posX + dx))
    t.posX = coinFixeX - nvLargeur
    t.largeur = nvLargeur
  }
  if (coin.includes('n')) {
    const coinFixeY = orig.posY + orig.hauteur
    const nvHauteur = Math.max(TAILLE_MIN, coinFixeY - aimanter(orig.posY + dy))
    t.posY = coinFixeY - nvHauteur
    t.hauteur = nvHauteur
  }
  modifie.value = true
}
function arreterRedimension() {
  resize = null
  window.removeEventListener('mousemove', surRedimension)
  window.removeEventListener('mouseup', arreterRedimension)
}

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', surDeplacement)
  window.removeEventListener('mouseup', arreterDeplacement)
  window.removeEventListener('mousemove', surRedimension)
  window.removeEventListener('mouseup', arreterRedimension)
})

async function enregistrerPlan() {
  if (!salleActive.value) return
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const body = {
      tables: tablesEnCours.value.map(t => ({
        id: t.id,
        numero: t.numero,
        forme: t.forme,
        posX: Math.round(t.posX),
        posY: Math.round(t.posY),
        largeur: Math.round(t.largeur),
        hauteur: Math.round(t.hauteur),
        nbChaises: t.nbChaises,
      })),
    }
    await api(`/restaurant/salles/${salleActive.value.id}/plan`, { method: 'PUT', body })
    succes.value = 'Plan enregistré.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement du plan.")
  } finally {
    saving.value = false
  }
}

// ── Statut occupée / libre : independant du plan, applique immediatement ──
const changeantStatut = ref(false)

async function toggleOccupation() {
  const t = tableSelectionnee.value
  if (!t || !t.id) return
  const nouvelEtat = !t.occupee
  changeantStatut.value = true
  erreur.value = ''
  try {
    await api(`/restaurant/tables/${t.id}/statut`, { method: 'PUT', body: { occupee: nouvelEtat } })
    t.occupee = nouvelEtat
    // Reflete aussi dans la liste chargee, pour rester coherent si l'on
    // change de salle sans recharger depuis le serveur.
    const s = salleActive.value
    const original = s?.tables.find(x => x.id === t.id)
    if (original) original.occupee = nouvelEtat
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la mise à jour du statut.')
  } finally {
    changeantStatut.value = false
  }
}

function fmtCommande(c: Commande): string {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(c.totalTtc) + ' ' + c.devise
}
function fmtMontant(montant: number, devise: string): string {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(montant) + ' ' + devise
}
function toggleCommande(id: number) {
  commandeOuverteId.value = commandeOuverteId.value === id ? null : id
}

// ── Addition : recapitulatif + reglement groupe de la table ───────────────
const commandesNonPayees = computed(() => commandesTable.value.filter(c => !c.reglee))

/** Somme par devise : une addition peut cumuler des commandes saisies en CDF et en USD. */
const totauxAdditionParDevise = computed(() => {
  const totaux: Record<string, number> = {}
  for (const c of commandesNonPayees.value) {
    totaux[c.devise] = (totaux[c.devise] || 0) + c.totalTtc
  }
  return totaux
})

const dialogAddition = ref(false)
const banques = ref<Etablissement[]>([])
const operateurs = ref<Etablissement[]>([])
const formReglementAddition = reactive({
  modeReglement: 'CAISSE' as 'CAISSE' | 'BANQUE' | 'MOBILE_MONEY',
  etablissementId: null as number | null,
  dateReglement: new Date().toISOString().slice(0, 10),
})
const besoinEtablissementAddition = computed(() =>
  formReglementAddition.modeReglement === 'BANQUE' || formReglementAddition.modeReglement === 'MOBILE_MONEY')
const etablissementsOptionsAddition = computed(() =>
  (formReglementAddition.modeReglement === 'BANQUE' ? banques.value : operateurs.value)
    .map(e => ({ title: e.nom, value: e.id })))

const busyAddition = ref(false)
const erreurAddition = ref('')

async function ouvrirAddition() {
  erreurAddition.value = ''
  dialogAddition.value = true
  if (banques.value.length === 0 && operateurs.value.length === 0) {
    const [bqs, ops] = await Promise.all([
      api<Etablissement[]>('/etablissements?type=BANQUE').catch(() => []),
      api<Etablissement[]>('/etablissements?type=MOBILE_MONEY').catch(() => []),
    ])
    banques.value = bqs
    operateurs.value = ops
  }
}

async function confirmerReglementAddition() {
  const tableId = tableSelectionnee.value?.id
  if (!tableId) return
  if (besoinEtablissementAddition.value && !formReglementAddition.etablissementId) {
    erreurAddition.value = formReglementAddition.modeReglement === 'BANQUE'
      ? 'Choisissez la banque encaisseuse.'
      : "Choisissez l'opérateur mobile money."
    return
  }
  busyAddition.value = true
  erreurAddition.value = ''
  try {
    await api(`/restaurant/tables/${tableId}/regler-addition`, {
      method: 'POST',
      body: {
        modeReglement: formReglementAddition.modeReglement,
        etablissementId: besoinEtablissementAddition.value ? formReglementAddition.etablissementId : null,
        dateReglement: formReglementAddition.dateReglement,
      },
    })
    dialogAddition.value = false
    succes.value = 'Addition réglée.'
    await rafraichirApresReglementAddition(tableId)
  } catch (e: any) {
    erreurAddition.value = messageErreurApi(e, "Échec de l'encaissement de l'addition.")
  } finally {
    busyAddition.value = false
  }
}

/**
 * Recharge salles + commandes sans passer par rechargerTablesEnCours(), qui
 * viderait la selection courante — l'utilisateur doit voir immediatement le
 * badge "Payée" sur la table qu'il vient de regler, pas revenir a un plan
 * sans rien de selectionne.
 */
async function rafraichirApresReglementAddition(tableId: number) {
  const [data, cmds] = await Promise.all([
    api<Salle[]>('/restaurant/salles'),
    api<Commande[]>(`/restaurant/tables/${tableId}/ventes`),
  ])
  salles.value = data
  commandesTable.value = cmds
  const tableMaj = data.find(s => s.id === salleActiveId.value)?.tables.find(t => t.id === tableId)
  if (tableMaj && selectionIndex.value !== null) {
    tablesEnCours.value[selectionIndex.value] = { ...tableMaj }
  }
}

// ── Ticket imprimable (thermique 80mm) de l'addition ──────────────────────
const dateImpressionAddition = ref('')

function imprimerAddition() {
  dateImpressionAddition.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => {
    const style = document.createElement('style')
    style.id = 'addition-ticket-page-style'
    style.textContent = '@page { size: 80mm auto; margin: 3mm; }'
    document.head.appendChild(style)
    window.print()
    window.addEventListener('afterprint', () => {
      document.getElementById('addition-ticket-page-style')?.remove()
    }, { once: true })
  })
}

/**
 * Position (en px, relative au coin haut-gauche de la table) de chaque
 * chaise, reparties en cercle/ellipse autour du perimetre — simple repere
 * visuel, pas une simulation geometrique exacte des coins du rectangle.
 */
function positionsChaises(t: TableItem): { x: number; y: number }[] {
  const cx = t.largeur / 2
  const cy = t.hauteur / 2
  const rx = t.largeur / 2 + 14
  const ry = t.hauteur / 2 + 14
  const n = Math.max(0, t.nbChaises)
  return Array.from({ length: n }, (_, i) => {
    const angle = (i / n) * 2 * Math.PI - Math.PI / 2
    return { x: cx + rx * Math.cos(angle), y: cy + ry * Math.sin(angle) }
  })
}
</script>

<template>
  <div>
  <div class="no-print">
    <div class="page-head">
      <div>
        <h1 class="page-title">Salles et tables</h1>
        <p class="page-sub">Plan visuel du restaurant — glissez pour déplacer, tirez un coin pour redimensionner</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <div class="salles-tabs mb-4">
      <v-btn
        v-for="s in salles" :key="s.id"
        :variant="s.id === salleActiveId ? 'flat' : 'outlined'"
        :color="s.id === salleActiveId ? 'indigo' : undefined"
        rounded="lg" size="small"
        @click="choisirSalle(s.id)"
      >
        {{ s.nom }}
      </v-btn>
      <v-btn v-if="canWrite" icon="mdi-plus" size="small" variant="tonal" title="Nouvelle salle" @click="ouvrirCreationSalle" />
    </div>

    <v-card v-if="loading" class="pa-8 d-flex justify-center classroom-card">
      <v-progress-circular indeterminate color="primary" />
    </v-card>

    <v-card v-else-if="!salleActive" class="pa-8 text-center text-medium-emphasis classroom-card">
      {{ canWrite ? 'Aucune salle. Cliquez sur "+" pour en créer une.' : "Aucune salle n'est configurée." }}
    </v-card>

    <template v-else>
      <div class="d-flex justify-space-between align-center flex-wrap ga-2 mb-3">
        <div v-if="canWrite" class="d-flex ga-2">
          <v-btn prepend-icon="mdi-pencil-outline" variant="text" size="small" @click="ouvrirEditionSalle(salleActive)">
            Renommer la salle
          </v-btn>
          <v-btn prepend-icon="mdi-delete-outline" variant="text" color="error" size="small" @click="supprimerSalleActive">
            Supprimer la salle
          </v-btn>
        </div>
        <div v-if="canWrite" class="d-flex ga-2">
          <v-btn prepend-icon="mdi-table-plus" variant="tonal" rounded="lg" size="small" @click="ajouterTable">
            Table
          </v-btn>
          <v-btn
            v-if="tableSelectionnee"
            prepend-icon="mdi-delete-outline" variant="tonal" color="error" rounded="lg" size="small"
            @click="supprimerTableSelectionnee"
          >
            Retirer
          </v-btn>
          <v-btn
            color="primary" variant="flat" rounded="lg" size="small"
            :loading="saving" :disabled="!modifie"
            prepend-icon="mdi-content-save-outline"
            @click="enregistrerPlan"
          >
            Enregistrer
          </v-btn>
        </div>
      </div>

      <div class="plan-layout">
      <div class="plan-salle" @mousedown.self="selectionIndex = null">
        <div
          v-for="(t, i) in tablesEnCours" :key="i"
          class="table-item"
          :class="{
            'table-item--ronde': t.forme === 'ROND',
            'table-item--selected': i === selectionIndex,
            'table-item--occupee': t.occupee,
          }"
          :style="{ left: t.posX + 'px', top: t.posY + 'px', width: t.largeur + 'px', height: t.hauteur + 'px' }"
          @mousedown="demarrerDeplacement($event, i)"
        >
          <span
            v-for="(c, ci) in positionsChaises(t)" :key="ci"
            class="chaise"
            :style="{ left: c.x + 'px', top: c.y + 'px' }"
          />
          <span class="table-item__numero">{{ t.numero }}</span>
          <v-icon v-if="t.occupee" icon="mdi-lock" size="14" class="table-item__verrou" />
          <v-icon
            v-if="t.aCommandeNonPayee" icon="mdi-cash-remove" size="14"
            class="table-item__badge-paiement table-item__badge-paiement--du"
            title="Commande non payée"
          />
          <v-icon
            v-else-if="t.aCommandePayee" icon="mdi-cash-check" size="14"
            class="table-item__badge-paiement table-item__badge-paiement--paye"
            title="Commande payée"
          />
          <template v-if="i === selectionIndex && canWrite">
            <span class="poignee poignee--nw" @mousedown.stop="demarrerRedimension($event, i, 'nw')" />
            <span class="poignee poignee--ne" @mousedown.stop="demarrerRedimension($event, i, 'ne')" />
            <span class="poignee poignee--sw" @mousedown.stop="demarrerRedimension($event, i, 'sw')" />
            <span class="poignee poignee--se" @mousedown.stop="demarrerRedimension($event, i, 'se')" />
          </template>
        </div>
        <p v-if="!tablesEnCours.length" class="plan-salle__vide">
          Aucune table dans cette salle. {{ canWrite ? 'Cliquez sur "Table" pour en ajouter une.' : '' }}
        </p>
      </div>

      <v-card v-if="tableSelectionnee && (canWrite || peutGererStatut)" class="pa-4 classroom-card plan-panneau">
        <p class="text-caption text-medium-emphasis mb-2">Table {{ tableSelectionnee.numero }}</p>

        <template v-if="canWrite">
          <v-text-field
            v-model="tableSelectionnee.numero"
            label="Numéro" variant="outlined" density="comfortable" hide-details class="mb-3"
            @update:model-value="modifie = true"
          />
          <v-btn-toggle
            v-model="tableSelectionnee.forme" mandatory density="comfortable" variant="outlined" rounded="lg" class="mb-3"
            @update:model-value="modifie = true"
          >
            <v-btn value="CARRE" size="small"><v-icon icon="mdi-square-outline" class="mr-1" size="16" />Carrée</v-btn>
            <v-btn value="ROND" size="small"><v-icon icon="mdi-circle-outline" class="mr-1" size="16" />Ronde</v-btn>
          </v-btn-toggle>
          <v-text-field
            v-model.number="tableSelectionnee.nbChaises"
            type="number" min="0" label="Nombre de chaises"
            variant="outlined" density="comfortable" hide-details
            @update:model-value="modifie = true"
          />
        </template>

        <template v-if="peutGererStatut">
          <v-divider v-if="canWrite" class="my-4" />
          <v-btn
            v-if="tableSelectionnee.id"
            block rounded="lg" variant="tonal"
            :color="tableSelectionnee.occupee ? 'success' : 'error'"
            :loading="changeantStatut"
            :prepend-icon="tableSelectionnee.occupee ? 'mdi-lock-open-variant-outline' : 'mdi-lock-outline'"
            @click="toggleOccupation"
          >
            {{ tableSelectionnee.occupee ? 'Libérer la table' : 'Marquer occupée' }}
          </v-btn>
          <p v-else class="text-caption text-medium-emphasis">
            Enregistrez le plan pour pouvoir marquer cette table occupée.
          </p>
        </template>

        <template v-if="tableSelectionnee.id">
          <v-divider class="my-4" />
          <p class="text-caption text-medium-emphasis mb-2">Commandes</p>
          <div v-if="chargeantCommandes" class="d-flex justify-center pa-2">
            <v-progress-circular indeterminate color="primary" size="20" />
          </div>
          <p v-else-if="!commandesTable.length" class="text-caption text-medium-emphasis">
            Aucune commande rattachée à cette table.
          </p>
          <div v-for="c in commandesTable" :key="c.id" class="commande">
            <button type="button" class="commande-ligne" @click="toggleCommande(c.id)">
              <v-icon :icon="commandeOuverteId === c.id ? 'mdi-chevron-down' : 'mdi-chevron-right'" size="18" />
              <span class="commande-ligne__ref">{{ c.reference }}</span>
              <span class="commande-ligne__montant">{{ fmtCommande(c) }}</span>
              <v-chip :color="c.reglee ? 'success' : 'error'" size="x-small" variant="tonal">
                {{ c.reglee ? 'Payée' : 'Non payée' }}
              </v-chip>
            </button>
            <div v-if="commandeOuverteId === c.id" class="commande-detail">
              <div v-for="(l, li) in c.lignes" :key="li" class="commande-detail__ligne">
                <span class="commande-detail__designation">{{ l.designation }} × {{ l.quantite }}</span>
                <span>{{ fmtMontant(l.montantTtc, c.devise) }}</span>
              </div>
              <p v-if="!c.lignes.length" class="text-caption text-medium-emphasis ma-0">
                Détail indisponible.
              </p>
            </div>
          </div>

          <v-btn
            v-if="peutGererStatut && commandesNonPayees.length"
            block rounded="lg" variant="flat" color="primary" class="mt-3"
            prepend-icon="mdi-receipt-text-outline"
            @click="ouvrirAddition"
          >
            Générer l'addition
          </v-btn>
        </template>
      </v-card>
      </div>
    </template>

    <v-dialog v-model="dialogSalle" max-width="420">
      <v-card class="pa-6">
        <h2 class="text-h6 mb-4">{{ editSalleId ? 'Renommer la' : 'Nouvelle' }} salle</h2>
        <v-text-field
          v-model="formSalle.nom" label="Nom de la salle" placeholder="ex: Terrasse"
          variant="outlined" density="comfortable" class="mb-2" autofocus
          @keyup.enter="enregistrerSalle"
        />
        <div class="d-flex justify-end ga-2 mt-4">
          <v-btn variant="text" :disabled="savingSalle" @click="dialogSalle = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :loading="savingSalle" @click="enregistrerSalle">Enregistrer</v-btn>
        </div>
      </v-card>
    </v-dialog>

    <!-- ── Addition : recapitulatif + reglement groupe ──────────── -->
    <v-dialog v-model="dialogAddition" max-width="480">
      <v-card class="classroom-card pa-6">
        <div class="d-flex align-center ga-3 mb-4">
          <v-icon icon="mdi-receipt-text-outline" color="primary" size="28" />
          <span class="text-h6 font-weight-bold">Addition — Table {{ tableSelectionnee?.numero }}</span>
        </div>

        <v-alert v-if="erreurAddition" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">
          {{ erreurAddition }}
        </v-alert>

        <div class="addition-recap mb-4">
          <template v-for="c in commandesNonPayees" :key="c.id">
            <p class="addition-recap__cmd">{{ c.reference }}</p>
            <div v-for="(l, li) in c.lignes" :key="li" class="addition-recap__ligne">
              <span>{{ l.designation }} × {{ l.quantite }}</span>
              <span>{{ fmtMontant(l.montantTtc, c.devise) }}</span>
            </div>
          </template>
          <v-divider class="my-2" />
          <div v-for="(total, devise) in totauxAdditionParDevise" :key="devise" class="addition-recap__total">
            <span>Total {{ devise }}</span><span>{{ fmtMontant(total, devise) }}</span>
          </div>
        </div>

        <v-btn variant="outlined" color="primary" rounded="lg" block prepend-icon="mdi-printer-outline" class="mb-4" @click="imprimerAddition">
          Imprimer le ticket
        </v-btn>

        <v-divider class="mb-4" />
        <p class="text-caption text-medium-emphasis mb-3">
          Cette addition regroupe toutes les commandes à crédit non payées de la table.
          Le règlement ci-dessous soldera chacune d'elles en une fois.
        </p>

        <v-select
          v-model="formReglementAddition.modeReglement"
          :items="[
            { title: 'Caisse', value: 'CAISSE' },
            { title: 'Banque', value: 'BANQUE' },
            { title: 'Mobile Money', value: 'MOBILE_MONEY' },
          ]"
          label="Canal d'encaissement"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-select
          v-if="besoinEtablissementAddition"
          v-model="formReglementAddition.etablissementId"
          :items="etablissementsOptionsAddition"
          :label="formReglementAddition.modeReglement === 'BANQUE' ? 'Banque' : 'Opérateur'"
          variant="outlined"
          density="comfortable"
          class="mb-3"
        />
        <v-text-field
          v-model="formReglementAddition.dateReglement"
          label="Date d'encaissement"
          type="date"
          variant="outlined"
          density="comfortable"
          class="mb-4"
        />

        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialogAddition = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="busyAddition" @click="confirmerReglementAddition">
            Confirmer l'encaissement
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>

  <!-- ── Ticket imprimable de l'addition (thermique 80mm) ─────── -->
  <div class="addition-ticket">
    <div class="addition-ticket__marque">
      <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo" class="addition-ticket__logo">
      <p class="addition-ticket__nom">{{ parametresStore.parametres.nom }}</p>
    </div>
    <div class="addition-ticket__sep" />
    <p class="addition-ticket__ref">Addition — Table {{ tableSelectionnee?.numero }}</p>
    <div class="addition-ticket__sep" />
    <template v-for="c in commandesNonPayees" :key="c.id">
      <p class="addition-ticket__cmd">{{ c.reference }}</p>
      <div v-for="(l, li) in c.lignes" :key="li" class="addition-ticket__ligne">
        <div>{{ l.designation }}</div>
        <div class="addition-ticket__ligne-detail">
          <span>{{ l.quantite }} × {{ fmtMontant(l.prixUnitaire, c.devise) }}</span>
          <span>{{ fmtMontant(l.montantTtc, c.devise) }}</span>
        </div>
      </div>
    </template>
    <div class="addition-ticket__sep" />
    <div v-for="(total, devise) in totauxAdditionParDevise" :key="devise" class="addition-ticket__total-row addition-ticket__total-row--net">
      <span>TOTAL {{ devise }}</span><span>{{ fmtMontant(total, devise) }}</span>
    </div>
    <div class="addition-ticket__sep" />
    <p class="addition-ticket__merci">Merci de votre visite !</p>
    <p v-if="dateImpressionAddition" class="addition-ticket__horodatage">{{ dateImpressionAddition }}</p>
  </div>
  </div>
</template>

<style scoped>
.salles-tabs { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }

/* Plan a gauche, panneau de la table selectionnee a droite — toujours visible
   sans avoir a deviner qu'il faut scroller sous le plan (560px de haut). */
.plan-layout { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }

.plan-panneau { flex: 0 0 320px; max-height: 560px; overflow-y: auto; }

.plan-salle {
  position: relative;
  flex: 1 1 420px;
  min-width: 0;
  height: 560px;
  overflow: auto;
  border-radius: 16px;
  background-color: #eafaf3;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.6) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.6) 1px, transparent 1px);
  background-size: 22px 22px;
  border: 1px solid #d3ede1;
}
.plan-salle__vide {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  color: #6b8f80;
  font-size: 0.875rem;
  white-space: nowrap;
}

.table-item {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--color-primary) 30%, white);
  border: 2px solid var(--color-primary-dark);
  border-radius: 10px;
  cursor: move;
  user-select: none;
}
.table-item--ronde { border-radius: 50%; }
.table-item--selected {
  border-color: #1d4ed8;
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.25);
}
.table-item--occupee {
  background: color-mix(in srgb, #dc2626 25%, white);
  border-color: #b91c1c;
}
.table-item--occupee.table-item--selected {
  border-color: #1d4ed8;
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.25);
}
.table-item__numero {
  font-weight: 800;
  font-size: 1.05rem;
  color: #1f2937;
  pointer-events: none;
}
.table-item__verrou {
  position: absolute;
  top: 4px;
  right: 4px;
  color: #b91c1c;
  pointer-events: none;
}
.table-item__badge-paiement {
  position: absolute;
  top: 4px;
  left: 4px;
  pointer-events: none;
  border-radius: 50%;
  background: #fff;
  padding: 1px;
}
.table-item__badge-paiement--du { color: #b91c1c; }
.table-item__badge-paiement--paye { color: #15803d; }

.commande:last-child .commande-ligne { border-bottom: none; }
.commande-ligne {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
  background: none;
  border-left: none;
  border-right: none;
  border-top: none;
  cursor: pointer;
  font: inherit;
  color: inherit;
  text-align: left;
}
.commande-ligne__ref { font-weight: 600; font-size: 0.8125rem; }
.commande-ligne__montant { margin-left: auto; font-size: 0.8125rem; color: #6b7280; }

.commande-detail { padding: 0 0 10px 26px; }
.commande-detail__ligne {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 0.78rem;
  color: #4b5563;
  padding: 2px 0;
}
.commande-detail__designation { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.chaise {
  position: absolute;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  margin-top: -5px;
  background: #fff;
  border: 2px solid var(--color-primary-dark);
  border-radius: 3px;
  pointer-events: none;
}
.table-item--occupee .chaise { border-color: #b91c1c; }

.poignee {
  position: absolute;
  width: 14px;
  height: 14px;
  background: #fff;
  border: 2px solid #1d4ed8;
  border-radius: 50%;
}
.poignee--nw { top: -7px; left: -7px; cursor: nwse-resize; }
.poignee--ne { top: -7px; right: -7px; cursor: nesw-resize; }
.poignee--sw { bottom: -7px; left: -7px; cursor: nesw-resize; }
.poignee--se { bottom: -7px; right: -7px; cursor: nwse-resize; }

/* ── Recapitulatif de l'addition (a l'ecran, dans le dialog) ────────── */
.addition-recap { max-height: 320px; overflow-y: auto; }
.addition-recap__cmd { font-weight: 700; font-size: 0.8125rem; margin: 10px 0 4px; }
.addition-recap__cmd:first-child { margin-top: 0; }
.addition-recap__ligne {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 0.8125rem;
  color: #4b5563;
  padding: 2px 0 2px 10px;
}
.addition-recap__total { display: flex; justify-content: space-between; font-weight: 700; font-size: 0.9375rem; }

/* ── Ticket imprimable : cache a l'ecran, seul visible a l'impression ── */
.addition-ticket { display: none; }

@media print {
  .no-print { display: none !important; }

  .addition-ticket {
    display: block;
    width: 100%;
    max-width: 74mm;
    margin: 0 auto;
    font-family: 'Courier New', monospace;
    font-size: 11px;
    line-height: 1.4;
    color: #000;
  }
  .addition-ticket p { margin: 0; }
  .addition-ticket__marque { text-align: center; margin-bottom: 4px; }
  .addition-ticket__logo { max-width: 40mm; max-height: 18mm; object-fit: contain; margin-bottom: 4px; }
  .addition-ticket__nom { font-size: 13px; font-weight: 700; text-transform: uppercase; }
  .addition-ticket__ref { font-weight: 700; text-align: center; }
  .addition-ticket__cmd { font-weight: 700; margin-top: 6px; }
  .addition-ticket__sep { border-top: 1px dashed #000; margin: 6px 0; }
  .addition-ticket__ligne { margin-bottom: 3px; }
  .addition-ticket__ligne-detail { display: flex; justify-content: space-between; padding-left: 8px; }
  .addition-ticket__total-row { display: flex; justify-content: space-between; }
  .addition-ticket__total-row--net { font-weight: 700; font-size: 13px; margin-top: 2px; }
  .addition-ticket__merci { text-align: center; font-weight: 700; margin-top: 4px; }
  .addition-ticket__horodatage { text-align: center; font-size: 9px; color: #444; margin-top: 6px; }
}
</style>
