<script setup lang="ts">
definePageMeta({ module: 'DRH_PRESENCES' })

interface Employe { id: number; matricule: string; nomComplet: string }
interface Presence { id: number; employeId: number; date: string; statut: string; motif: string | null }

const STATUTS: { value: string; code: string; label: string; bg: string; color: string; motif: boolean }[] = [
  { value: 'PRESENT', code: 'P', label: 'Présent', bg: 'var(--color-primary-light)', color: 'var(--color-primary-dark)', motif: false },
  { value: 'ABSENT', code: 'A', label: 'Absent', bg: '#fee2e2', color: '#b91c1c', motif: true },
  { value: 'CONGE', code: 'C', label: 'Congé', bg: '#dbeafe', color: '#1d4ed8', motif: true },
  { value: 'MALADIE', code: 'M', label: 'Maladie', bg: '#fef3c7', color: '#b45309', motif: true },
  { value: 'MISSION', code: 'J', label: 'Mission', bg: '#ede9fe', color: '#6d28d9', motif: true },
  { value: 'FERIE', code: 'F', label: 'Férié / week-end', bg: '#f3f4f6', color: '#6b7280', motif: false },
]
const statutMeta = (v: string) => STATUTS.find(s => s.value === v) ?? STATUTS[0]

const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const canWrite = computed(() => auth.hasAnyRole(['RESP_DRH']))

const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())
const MOIS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const employes = ref<Employe[]>([])
const presences = ref<Presence[]>([])
const dialog = ref(false)
const cellule = reactive({ employeId: 0, employeNom: '', date: '', statut: 'PRESENT', motif: '' })

const jours = computed(() => {
  const n = new Date(annee.value, mois.value, 0).getDate()
  return Array.from({ length: n }, (_, i) => i + 1)
})

function statutParDefaut(jour: number): string {
  const d = new Date(annee.value, mois.value - 1, jour)
  const dow = d.getDay()
  return (dow === 0 || dow === 6) ? 'FERIE' : 'PRESENT'
}

function dateIso(jour: number): string {
  return `${annee.value}-${String(mois.value).padStart(2, '0')}-${String(jour).padStart(2, '0')}`
}

function statutDe(employeId: number, jour: number): string {
  const p = presences.value.find(p => p.employeId === employeId && p.date === dateIso(jour))
  return p ? p.statut : statutParDefaut(jour)
}

// Bascule le document entier sur la page nommee paysage (voir classroom.scss)
// pendant la duree de vie de ce composant : appliquer `page` sur <html> plutot
// que sur un element scope profondement imbrique dans l'arbre flex de
// Vuetify (.v-application > .v-application__wrap > .v-main, tous en
// display:flex) evite un bug d'impression Chromium ou la hauteur de page
// budgetee pour la pagination ne correspondait pas a la page nommee reelle
// (grand espace vide puis 2e feuille quasi blanche). Nettoyage garanti par
// le hook onUnmounted, pas par un stylesheet injecte : aucun risque de fuite
// vers une autre page lors d'une navigation cote client (meme incident que
// fiche-manuelle.vue, corrige differemment ici).
onMounted(() => document.documentElement.classList.add('print-paysage'))
onUnmounted(() => document.documentElement.classList.remove('print-paysage'))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [e, p] = await Promise.all([
      employes.value.length ? Promise.resolve(employes.value) : api<Employe[]>('/drh/employes'),
      api<Presence[]>('/drh/presences', { params: { mois: mois.value, annee: annee.value } }),
    ])
    employes.value = e
    presences.value = p
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les présences.')
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })
watch([mois, annee], charger)

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

function ouvrirCellule(emp: Employe, jour: number) {
  if (!canWrite.value) return
  const p = presences.value.find(p => p.employeId === emp.id && p.date === dateIso(jour))
  Object.assign(cellule, {
    employeId: emp.id, employeNom: emp.nomComplet, date: dateIso(jour),
    statut: p ? p.statut : statutParDefaut(jour), motif: p?.motif ?? '',
  })
  erreur.value = ''
  dialog.value = true
}

async function enregistrerCellule() {
  saving.value = true
  erreur.value = ''
  try {
    await api('/drh/presences', {
      method: 'PUT',
      body: { employeId: cellule.employeId, date: cellule.date, statut: cellule.statut, motif: cellule.motif || null },
    })
    dialog.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

async function toutPresent() {
  saving.value = true
  erreur.value = ''
  try {
    await api('/drh/presences/tout-present', { method: 'POST', params: { mois: mois.value, annee: annee.value } })
    succes.value = 'Présences réinitialisées pour la période.'
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Échec de la réinitialisation.')
  } finally {
    saving.value = false
  }
}

// ── Vue « jour » (téléphone et tablette) : un jour à la fois, au lieu de la grille de 31 colonnes ──────────────────
const jourSelectionne = ref(aujourdhui.getDate())
const estAujourdhui = (j: number) =>
  j === aujourdhui.getDate() && mois.value === aujourdhui.getMonth() + 1 && annee.value === aujourdhui.getFullYear()
const INITIALES_JOUR = ['D', 'L', 'M', 'M', 'J', 'V', 'S']
const initialeJour = (j: number) => INITIALES_JOUR[new Date(annee.value, mois.value - 1, j).getDay()]
const titreJour = computed(() =>
  new Date(annee.value, mois.value - 1, jourSelectionne.value)
    .toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))
const resumeJour = computed(() =>
  STATUTS.map(s => ({ ...s, n: employes.value.filter(e => statutDe(e.id, jourSelectionne.value) === s.value).length }))
    .filter(s => s.n > 0))

function moisPrecedent() {
  if (mois.value === 1) { mois.value = 12; annee.value-- } else mois.value--
}
function moisSuivant() {
  if (mois.value === 12) { mois.value = 1; annee.value++ } else mois.value++
}
// Changement de mois : aujourd'hui si c'est le mois courant, sinon le 1er.
watch([mois, annee], () => {
  jourSelectionne.value = (mois.value === aujourdhui.getMonth() + 1 && annee.value === aujourdhui.getFullYear()) ? aujourdhui.getDate() : 1
})
// Le jour choisi reste au centre de la bande de jours.
const bandeJours = ref<HTMLElement | null>(null)
function centrerJour() {
  nextTick(() => {
    const bande = bandeJours.value
    const actif = bande?.querySelector<HTMLElement>('.presence-jours__jour--actif')
    if (bande && actif) bande.scrollTo({ left: actif.offsetLeft - bande.clientWidth / 2 + actif.clientWidth / 2, behavior: 'smooth' })
  })
}
watch([jourSelectionne, mois, annee], centrerJour)
onMounted(centrerJour)

const pourcentagePresence = (employeId: number) => {
  const comptes = jours.value.map(j => statutDe(employeId, j))
  const comptables = comptes.filter(s => s !== 'FERIE').length
  const prestes = comptes.filter(s => s === 'PRESENT' || s === 'MISSION').length
  return comptables > 0 ? Math.round((prestes / comptables) * 100) : 0
}
</script>

<template>
  <div class="presence-page">
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Présences</h1>
        <p class="page-sub">Pointage journalier — cliquez sur une cellule pour saisir le statut du jour</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-clipboard-text-outline" to="/drh/presences/fiche-manuelle">Fiche manuelle</v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline"
          :disabled="!employes.length" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn v-if="canWrite" color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-check-all"
          :loading="saving" @click="toutPresent">
          Marquer tout présent
        </v-btn>
      </div>
    </div>

    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="erreur && !dialog" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>

    <!-- Téléphone et tablette : navigateur de mois. -->
    <div class="presence-nav no-print">
      <v-btn icon="mdi-chevron-left" variant="text" aria-label="Mois précédent" @click="moisPrecedent" />
      <span class="presence-nav__mois">{{ MOIS[mois - 1] }} {{ annee }}</span>
      <v-btn icon="mdi-chevron-right" variant="text" aria-label="Mois suivant" @click="moisSuivant" />
    </div>

    <div class="presence-filtres--pc d-flex ga-3 mb-4 no-print">
      <v-select v-model="mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined"
        density="comfortable" rounded="lg" hide-details style="max-width: 200px" />
      <v-text-field v-model.number="annee" type="number" label="Année" variant="outlined" density="comfortable"
        rounded="lg" hide-details style="max-width: 140px" />
    </div>

    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__service">Direction des Ressources Humaines</span>
          <span class="etat-print-header__doc">Fiche de présence — {{ MOIS[mois - 1] }} {{ annee }}</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <div class="legende mb-3">
      <span v-for="s in STATUTS" :key="s.value" class="legende__item">
        <span class="legende__badge" :style="{ background: s.bg, color: s.color }">{{ s.code }}</span> {{ s.label }}
      </span>
    </div>

    <v-card class="classroom-card presence-card">
      <v-skeleton-loader v-if="loading" type="table" class="presence-grille" />
      <div v-else class="presence-scroll tableau-fixe presence-grille">
        <table class="presence-grid">
          <thead>
            <tr>
              <th class="presence-grid__emp-head">Employé</th>
              <th v-for="j in jours" :key="j" class="presence-grid__day">{{ j }}</th>
              <th class="presence-grid__pct">%</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="emp in employes" :key="emp.id">
              <td class="presence-grid__emp">{{ emp.nomComplet }}</td>
              <td v-for="j in jours" :key="j" class="presence-grid__cell" @click="ouvrirCellule(emp, j)">
                <span class="presence-grid__badge" :style="{ background: statutMeta(statutDe(emp.id, j)).bg, color: statutMeta(statutDe(emp.id, j)).color }">
                  {{ statutMeta(statutDe(emp.id, j)).code }}
                </span>
              </td>
              <td class="presence-grid__pct-val">{{ pourcentagePresence(emp.id) }}%</td>
            </tr>
          </tbody>
        </table>
        <div v-if="!employes.length" class="pa-6 text-center text-medium-emphasis">Aucun employé actif.</div>
      </div>

      <!-- Téléphone et tablette : la bande des jours du mois, puis les employés du jour choisi. -->
      <div class="presence-jour no-print">
        <div ref="bandeJours" class="presence-jours" role="group" aria-label="Jour du mois">
          <button
            v-for="j in jours" :key="j" type="button" class="presence-jours__jour"
            :class="{
              'presence-jours__jour--actif': j === jourSelectionne,
              'presence-jours__jour--repos': statutParDefaut(j) === 'FERIE',
              'presence-jours__jour--aujourdhui': estAujourdhui(j),
            }"
            :aria-pressed="j === jourSelectionne"
            @click="jourSelectionne = j"
          >
            <span class="presence-jours__semaine">{{ initialeJour(j) }}</span>
            <span class="presence-jours__numero">{{ j }}</span>
          </button>
        </div>

        <p class="presence-jour__titre">{{ titreJour }}</p>
        <div v-if="employes.length" class="presence-jour__resume">
          <span v-for="s in resumeJour" :key="s.value" class="presence-jour__puce" :style="{ background: s.bg, color: s.color }">
            {{ s.n }} · {{ s.label }}
          </span>
        </div>

        <v-skeleton-loader v-if="loading" type="list-item-two-line, list-item-two-line, list-item-two-line" />
        <ul v-else-if="employes.length" class="presence-jour__liste">
          <li v-for="emp in employes" :key="emp.id" class="presence-jour__employe">
            <div class="presence-jour__nom">{{ emp.nomComplet }}</div>
            <div class="presence-jour__ligne">
              <span class="presence-jour__pct">Présence du mois : {{ pourcentagePresence(emp.id) }} %</span>
              <button
                type="button" class="presence-jour__statut" :disabled="!canWrite"
                :style="{ background: statutMeta(statutDe(emp.id, jourSelectionne)).bg, color: statutMeta(statutDe(emp.id, jourSelectionne)).color }"
                :aria-label="`${emp.nomComplet} : ${statutMeta(statutDe(emp.id, jourSelectionne)).label}`"
                @click="ouvrirCellule(emp, jourSelectionne)"
              >
                <span class="presence-jour__code">{{ statutMeta(statutDe(emp.id, jourSelectionne)).code }}</span>
                {{ statutMeta(statutDe(emp.id, jourSelectionne)).label }}
              </button>
            </div>
          </li>
        </ul>
        <p v-else class="pa-6 text-center text-medium-emphasis">Aucun employé actif.</p>
      </div>
    </v-card>

    <v-dialog v-model="dialog" max-width="420">
      <v-card>
        <v-card-title>Présence du {{ new Date(cellule.date).toLocaleDateString('fr-FR') }}</v-card-title>
        <v-divider />
        <v-card-text>
          <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
          <p class="mb-3 text-body-2"><strong>{{ cellule.employeNom }}</strong></p>
          <v-select v-model="cellule.statut" :items="STATUTS.map(s => ({ title: s.label, value: s.value }))"
            label="Statut" variant="outlined" density="comfortable" />
          <v-text-field v-if="statutMeta(cellule.statut).motif" v-model="cellule.motif" label="Motif" variant="outlined" density="comfortable" />
        </v-card-text>
        <v-divider />
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" :disabled="saving" @click="dialog = false">Annuler</v-btn>
          <v-btn color="success" variant="flat" :loading="saving" @click="enregistrerCellule">Enregistrer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.legende { display: flex; flex-wrap: wrap; gap: 14px; }
.legende__item { display: inline-flex; align-items: center; gap: 6px; font-size: 0.78rem; color: #6b7280; }
.legende__badge { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 5px; font-size: 0.68rem; font-weight: 700; }

.presence-scroll { overflow-x: auto; }
/* Coin de la grille (en-tête « Employé ») : figé à gauche ET en haut, au-dessus des deux ; colonne des noms au-dessus des cellules. */
.presence-grid__emp-head { top: 0; z-index: 4; }
.presence-grid__emp { z-index: 2; }
.presence-grid { border-collapse: collapse; font-size: 0.78rem; width: 100%; }
.presence-grid th, .presence-grid td { padding: 4px 3px; text-align: center; border-bottom: 1px solid #f3f4f6; }
.presence-grid__emp-head, .presence-grid__emp {
  text-align: left; position: sticky; left: 0; background: #fff; white-space: nowrap; padding-right: 14px;
  font-weight: 600; color: #111827;
}
.presence-grid__day { font-size: 0.68rem; color: #9ca3af; font-weight: 700; min-width: 26px; }
.presence-grid__pct { font-size: 0.68rem; color: #9ca3af; font-weight: 700; }
.presence-grid__pct-val { font-weight: 700; color: #111827; }
.presence-grid__cell { cursor: pointer; }
.presence-grid__cell:hover { background: #f9fafb; }
.presence-grid__badge {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 5px; font-size: 0.7rem; font-weight: 700;
}

/* ── Téléphone et tablette : navigateur de mois et vue « jour » ───────────────────────────────────────────────────── */
.presence-nav, .presence-jour { display: none; }
@media screen and (max-width: 959px) {
  /* `d-flex` (Vuetify) impose `display: flex !important` : il faut le même poids pour masquer les champs PC. */
  .presence-filtres--pc, .presence-grille { display: none !important; }
  .presence-card { background: transparent !important; border: 0 !important; }
  .presence-nav {
    display: flex; align-items: center; justify-content: space-between; gap: 8px;
    margin-bottom: 12px; padding: 2px; background: #fff; border: 1px solid #eef0f2; border-radius: 14px;
  }
  .presence-nav__mois { font-size: 1rem; font-weight: 700; color: #111827; }
  .presence-jour { display: block; }
  .legende { gap: 8px 12px; }
}
.presence-jours { position: relative; display: flex; gap: 6px; overflow-x: auto; padding: 2px 2px 10px; scrollbar-width: none; }
.presence-jours::-webkit-scrollbar { display: none; }
.presence-jours__jour {
  flex: 0 0 auto; width: 46px; height: 62px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  border: 1px solid #e5e7eb; border-radius: 14px; background: #fff; color: #374151; font: inherit; cursor: pointer;
}
.presence-jours__semaine { font-size: 0.66rem; font-weight: 700; color: #9ca3af; }
.presence-jours__numero { font-size: 1rem; font-weight: 800; }
.presence-jours__jour--repos { background: #f9fafb; color: #9ca3af; }
.presence-jours__jour--aujourdhui { border-color: var(--color-primary); }
.presence-jours__jour--actif { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.presence-jours__jour--actif .presence-jours__semaine { color: rgba(255, 255, 255, 0.8); }
.presence-jour__titre { margin: 4px 2px 8px; font-size: 0.95rem; font-weight: 700; color: #111827; }
.presence-jour__titre::first-letter { text-transform: uppercase; }
.presence-jour__resume { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
.presence-jour__puce { display: inline-flex; align-items: center; padding: 4px 10px; border-radius: 100px; font-size: 0.75rem; font-weight: 700; white-space: nowrap; }
.presence-jour__liste { margin: 0; padding: 0; list-style: none; display: grid; gap: 10px; grid-template-columns: minmax(0, 1fr); }
@media screen and (min-width: 600px) { .presence-jour__liste { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.presence-jour__employe { background: #fff; border: 1px solid #eef0f2; border-left: 4px solid var(--color-primary); border-radius: 14px; padding: 12px 14px; }
.presence-jour__nom { font-size: 0.95rem; font-weight: 700; color: #111827; overflow-wrap: anywhere; }
.presence-jour__ligne { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; }
.presence-jour__pct { font-size: 0.78rem; color: #6b7280; }
.presence-jour__statut {
  flex: 0 0 auto; display: inline-flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 14px;
  border: 0; border-radius: 12px; font: inherit; font-size: 0.85rem; font-weight: 700; cursor: pointer;
}
.presence-jour__statut:disabled { cursor: default; }
.presence-jour__code {
  display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px;
  border-radius: 6px; background: rgba(255, 255, 255, 0.55); font-size: 0.72rem;
}

@media print {
  /* La fiche imprimée est toujours la grille du mois, quelle que soit la largeur de l'écran. */
  .presence-nav, .presence-jour { display: none !important; }
  .presence-grille { display: block !important; }
  .presence-filtres--pc { display: none !important; }
  /* L'activation de la page nommee paysage se fait sur <html> (classe
     print-paysage, voir onMounted/onUnmounted et classroom.scss) — pas ici,
     un @page nomme applique via un selecteur scope profondement imbrique
     dans l'arbre flex de Vuetify faisait planter le calcul de pagination
     de Chromium (grand vide puis 2e feuille quasi blanche). */

  .legende { gap: 8px; margin-bottom: 8px; }
  .legende__item { font-size: 0.64rem; }
  .legende__badge { width: 15px; height: 15px; font-size: 0.56rem; }

  /* overflow-x:auto ne sert qu'au scroll ecran ; en laissant "visible" on
     evite qu'un moteur de rendu applique quand meme une boite de defilement
     au lieu de simplement etaler la grille sur la largeur de la page. */
  .presence-scroll { overflow: visible; }
  .presence-grid { font-size: 0.6rem; }
  .presence-grid th, .presence-grid td { padding: 2px 3px; }
  .presence-grid__day { min-width: 0; font-size: 0.56rem; }
  .presence-grid__badge { width: 15px; height: 15px; font-size: 0.54rem; border-radius: 3px; }
  /* position:sticky n'a aucune utilite hors d'un conteneur qui defile —
     la neutraliser evite qu'un moteur de pagination l'interprete comme une
     contrainte de mise en page a l'impression. */
  .presence-grid__emp-head, .presence-grid__emp { position: static; }
}
</style>
