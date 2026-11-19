<script setup lang="ts">
import { useExerciceStore } from '~/stores/exercice'
// DA exclu explicitement : voir NavigationDrawer.vue (comptabiliteItems).
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DG', 'COMPTABLE'] })

interface Piece {
  id: number
  reference: string
  datePiece: string
  journal: string
  libelle?: string
  beneficiaire?: string | null
  statut: string
  soldeOuverture?: boolean
  totalDebit: number
  totalCredit: number
  createdByEmail?: string
  /** Qui a validé la pièce et quand (vide pour une pièce validée avant cette trace). */
  comptabiliseeParEmail?: string | null
  comptabiliseeLe?: string | null
  /** Pièce de clôture de l'exercice : exclue du compte de résultat. */
  clotureExercice?: boolean
  /** Pièce d'origine d'une extourne (renseignée sur une pièce d'extourne, vide sinon). */
  pieceOrigineId?: number | null
  /**
   * Pièce annulée qui peut être restaurée : brouillon à faire valider pour le comptable, comptabilisation rétablie
   * tout de suite pour le DFIN et l'administrateur.
   */
  restaurable?: boolean
  /** Pièce annulée dont celle-ci est la restauration. */
  pieceRestaureeReference?: string | null
  /**
   * Calculé par le serveur : une pièce de stock, de vente ou de trésorerie
   * s'annule depuis son opération d'origine, jamais seule d'ici.
   */
  extournable: boolean
  /** Brouillon validable d'ici : ni journal ni compte de trésorerie. */
  comptabilisable: boolean
  /** Mouvemente la trésorerie (classe 5) : ni comptabilisée ni extournée d'ici. */
  tresorerie: boolean
}

const api = useApi()
const auth = useAuthStore()
const dialogues = useDialogues()
const loading = ref(false)
const erreur = ref('')
const pieces = ref<Piece[]>([])

// Recherche par référence : filtre la liste déjà chargée (le serveur renvoie toutes les pièces). Sans casse et sans
// tenir compte des espaces, tirets ou barres : « pc-2026-000093 », « PC 2026 000093 » et « 93 » retrouvent la même pièce.
const recherche = ref<string | null>('')
const page = ref(1)
const requete = computed(() => (recherche.value ?? '').trim())
const compacter = (texte: string) => texte.toLowerCase().replace(/[\s\-_/.]+/g, '')
const piecesFiltrees = computed(() => {
  const cherche = compacter(requete.value)
  return cherche ? pieces.value.filter(p => compacter(p.reference ?? '').includes(cherche)) : pieces.value
})
// Une nouvelle recherche repart de la première page (sinon on pourrait rester sur une page devenue vide).
watch(requete, () => { page.value = 1 })

const canWrite = computed(() => auth.hasAnyRole(['DFIN', 'COMPTABLE']))
// Audit SYSCOHADA du 10/11/2026, C-06 : le comptable saisit, le DFIN valide.
const peutSaisir = computed(() => auth.hasRole('COMPTABLE'))
const peutValider = computed(() => auth.hasRole('DFIN'))
// Seul le comptable annule une comptabilisation ; le DFIN et l'administrateur peuvent la rétablir (« Restaurer »).
// Le comptable, lui, n'obtient qu'un brouillon à faire valider (il ne valide pas ses propres pièces).
const peutAnnuler = computed(() => auth.hasRole('COMPTABLE'))
const peutRestaurer = computed(() => auth.hasAnyRole(['COMPTABLE', 'DFIN', 'ADMIN']))
const retablitDirectement = computed(() => auth.hasAnyRole(['DFIN', 'ADMIN']))
// Reclassification ouverture/mouvements : reservee au DFIN (ADMIN a le
// filet de securite cote serveur mais pas ce controle a l'ecran, meme
// convention que le reste de l'app), COMPTABLE exclu — ca affecte la
// presentation des etats financiers officiels, pas une simple saisie.
const peutBasculerOuverture = computed(() => auth.hasAnyRole(['DFIN']))

const statutColor: Record<string, string> = {
  BROUILLON: 'grey',
  COMPTABILISEE: 'success',
  ANNULEE: 'error',
}

const journalLabel: Record<string, string> = {
  OPERATIONS_DIVERSES: 'Opérations diverses',
  CAISSE: 'Caisse',
  BANQUE: 'Banque',
  MOBILE_MONEY: 'Mobile Money',
  ACHATS: 'Achats',
  VENTES: 'Ventes',
  STOCK: 'Stock',
  ANALYTIQUE: 'Analytique',
  PAIE: 'Paie',
  A_NOUVEAUX: 'À-nouveaux',
}

const exerciceConsulte = useExerciceStore()

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const toutes = await api<Piece[]>('/comptabilite/pieces')
    // Exercice choisi dans la barre du haut ; la pièce d'un lien de notification reste visible quel que soit son exercice.
    const annee = String(exerciceConsulte.anneeChoisie)
    const demandee = Number(useRoute().query.piece) || null
    pieces.value = toutes.filter(x => x.datePiece?.startsWith(annee) || x.id === demandee)
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les pièces comptables.'
  } finally {
    loading.value = false
  }
}

// Lien d'une notification (« /comptabilite/pieces?piece=123 ») : la liste s'ouvre filtrée sur cette pièce, par sa
// référence ACTUELLE (celle d'un brouillon change quand le DFIN le comptabilise). Une pièce disparue (brouillon
// supprimé) laisse la liste entière.
const route = useRoute()
function appliquerPieceDemandee() {
  const id = Number(route.query.piece)
  if (!id) return
  const p = pieces.value.find(x => x.id === id)
  recherche.value = p ? p.reference : ''
}
onMounted(async () => {
  await charger()
  appliquerPieceDemandee()
})
// Autre notification ouverte alors que la page est déjà affichée : même page, autre pièce.
watch(() => route.query.piece, async () => {
  await charger()
  appliquerPieceDemandee()
})

async function comptabiliser(id: number) {
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}/comptabiliser`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Comptabilisation impossible.'
  }
}

async function annuler(item: Piece) {
  const confirme = await dialogues.confirmer({
    titre: `Annuler la pièce ${item.reference} ?`,
    message: [
      "Une pièce d'extourne sera générée ; la pièce reste visible, au statut ANNULEE (SYSCOHADA).",
      // Seule une pièce d'origine se restaure : une extourne annulée ne se rétablit pas.
      ...(item.pieceOrigineId ? [] : ['Si l’annulation est une erreur, le DFIN ou l’administrateur pourra rétablir la comptabilisation.']),
    ],
    bouton: 'Annuler la pièce',
    abandon: 'Retour',
    nature: 'danger',
    icone: 'mdi-cancel',
  })
  if (!confirme) return
  const id = item.id
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}/annuler`, { method: 'POST' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Annulation impossible.'
  }
}

/**
 * Pièce annulée par erreur : le DFIN et l'administrateur rétablissent la comptabilisation d'un coup (nouvelle pièce
 * identique, comptabilisée tout de suite) ; le comptable obtient un nouveau brouillon identique, que le DFIN valide
 * ensuite. Dans les deux cas, la pièce annulée et son extourne restent au journal (SYSCOHADA).
 */
async function restaurer(item: Piece) {
  const direct = retablitDirectement.value
  const confirme = await dialogues.confirmer({
    titre: direct ? `Rétablir la comptabilisation de ${item.reference} ?` : `Restaurer la pièce ${item.reference} ?`,
    message: [
      direct
        ? 'Une nouvelle pièce identique sera comptabilisée tout de suite, aux mêmes comptes et aux mêmes montants.'
        : 'Un nouveau brouillon identique sera créé ; le DFIN devra le comptabiliser.',
      'La pièce annulée et son extourne restent au journal.',
    ],
    bouton: direct ? 'Rétablir la comptabilisation' : 'Restaurer en brouillon',
    nature: 'info',
    icone: 'mdi-restore',
  })
  if (!confirme) return
  erreur.value = ''
  succesRestauration.value = ''
  try {
    const r = await api<Piece>(`/comptabilite/pieces/${item.id}/restaurer`, { method: 'POST' })
    succesRestauration.value = r.statut === 'COMPTABILISEE'
      ? `Comptabilisation rétablie : la pièce ${r.reference} reprend la pièce annulée ${item.reference}.`
      : `Brouillon ${r.reference} créé : il attend la validation du DFIN.`
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Restauration impossible.'
  }
}
const succesRestauration = ref('')

async function supprimerBrouillon(id: number) {
  const confirme = await dialogues.confirmer({
    titre: 'Supprimer ce brouillon ?',
    message: 'Cette suppression est définitive.',
    bouton: 'Supprimer',
    nature: 'danger',
    icone: 'mdi-trash-can-outline',
  })
  if (!confirme) return
  erreur.value = ''
  try {
    await api(`/comptabilite/pieces/${id}`, { method: 'DELETE' })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Suppression impossible.'
  }
}

/**
 * Reclasse une pièce en/hors solde d'ouverture — y compris déjà
 * comptabilisée (case à cocher oubliée à la saisie) : ne touche ni montants
 * ni comptes, seulement la colonne où la balance la range.
 */
async function basculerOuverture(item: Piece) {
  erreur.value = ''
  // Une pièce comptabilisée ne se reclasse qu'avec un motif, gardé au journal des événements comptables.
  let motif: string | null = null
  if (item.statut !== 'BROUILLON') {
    motif = await dialogues.saisir({
      titre: `Reclasser la pièce ${item.reference} ?`,
      message: item.soldeOuverture
        ? 'Elle repassera en mouvement de la période, sans changer ses montants ni ses comptes.'
        : 'Elle sera comptée dans les soldes d’ouverture, sans changer ses montants ni ses comptes.',
      libelle: 'Motif du reclassement (gardé au journal des événements)',
      obligatoire: true,
      bouton: 'Reclasser la pièce',
      nature: 'attention',
      icone: 'mdi-calendar-start-outline',
    })
    if (!motif) return
  }
  try {
    await api(`/comptabilite/pieces/${item.id}/solde-ouverture`, {
      method: 'PATCH',
      body: { soldeOuverture: !item.soldeOuverture, motif },
    })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Reclassification impossible.'
  }
}

// La trésorerie ne se modifie jamais depuis cet écran (voir
// ComptabiliteService.exigerHorsTresorerie) : on dit pourquoi une action
// manque plutôt que de la proposer pour la voir refusée.
const MOTIF_TRESORERIE = 'Mouvemente la trésorerie : un encaissement ou un décaissement passe par une note de frais, pas par cet écran.'
function motifVerrou(item: Piece): string | null {
  if (item.statut === 'BROUILLON' && !item.comptabilisable && canWrite.value) {
    return item.tresorerie ? MOTIF_TRESORERIE + ' Supprimez ce brouillon.' : 'Journal réservé à la trésorerie : supprimez ce brouillon.'
  }
  // Le DFIN et l'administrateur n'annulent pas : l'explication ne concerne que le comptable.
  if (item.statut === 'COMPTABILISEE' && !item.extournable && peutAnnuler.value) {
    return item.tresorerie
      ? MOTIF_TRESORERIE
      : "Pièce d'un mouvement de stock ou d'une vente : elle s'annule depuis son opération d'origine."
  }
  // Pièce annulée sans bouton « Restaurer » : on dit pourquoi plutôt que de laisser croire à un oubli.
  if (item.statut === 'ANNULEE' && !item.restaurable && peutRestaurer.value) {
    if (item.pieceOrigineId) {
      return "Pièce d'extourne : elle ne se restaure pas. Pour rétablir la comptabilisation, restaurez la pièce d'origine."
    }
    if (item.tresorerie) {
      return 'Mouvemente la trésorerie : une pièce de caisse, de banque ou de mobile money ne se restaure pas depuis cet écran.'
    }
    return "Rien à restaurer ici : la pièce est liée à une opération (stock, vente, caisse), déjà restaurée, ou sa comptabilisation a déjà été rétablie."
  }
  return null
}

function fmt(v: number) {
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(v)
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('fr-FR')
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Pièces comptables</h1>
        <p class="page-sub">
          Opérations sans mouvement de trésorerie — un encaissement ou un décaissement passe par une note de frais
        </p>
      </div>
      <v-btn
        v-if="peutSaisir"
        color="primary"
        prepend-icon="mdi-plus"
        to="/comptabilite/pieces/nouvelle"
      >
        Nouvelle pièce
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succesRestauration" type="success" variant="tonal" class="mb-4" closable
             @click:close="succesRestauration = ''">{{ succesRestauration }}</v-alert>

    <div class="d-flex align-center flex-wrap ga-3 mb-4">
      <v-text-field
        v-model="recherche"
        label="Référence de la pièce"
        placeholder="Ex. PC-2026-000093 ou 93"
        prepend-inner-icon="mdi-magnify"
        variant="outlined"
        density="comfortable"
        rounded="lg"
        hide-details
        clearable
        autocomplete="off"
        style="max-width: 420px"
      />
      <span v-if="requete" class="text-body-2 text-medium-emphasis" aria-live="polite">
        {{ piecesFiltrees.length }} pièce{{ piecesFiltrees.length > 1 ? 's' : '' }} sur {{ pieces.length }}
      </span>
    </div>

    <v-card class="classroom-card">
      <v-data-table
        :headers="[
          { title: 'Référence', key: 'reference' },
          { title: 'Date', key: 'datePiece' },
          { title: 'Journal', key: 'journal' },
          { title: 'Libellé', key: 'libelle' },
          { title: 'Statut', key: 'statut' },
          { title: 'Saisie / validation', key: 'tracabilite', sortable: false },
          { title: 'Débit', key: 'totalDebit', align: 'end' },
          { title: 'Crédit', key: 'totalCredit', align: 'end' },
          { title: 'Actions', key: 'actions', sortable: false },
        ]"
        :items="piecesFiltrees"
        :loading="loading"
        items-per-page="15"
        v-model:page="page"
        :no-data-text="requete ? `Aucune pièce ne correspond à « ${requete} ».` : 'Aucune pièce comptable.'"
      >
        <template #item.datePiece="{ item }">{{ fmtDate(item.datePiece) }}</template>
        <template #item.journal="{ item }">{{ journalLabel[item.journal] || item.journal }}</template>
        <template #item.libelle="{ item }">
          {{ item.libelle || '—' }}
          <div v-if="item.beneficiaire" class="text-caption text-medium-emphasis">Bénéficiaire : {{ item.beneficiaire }}</div>
          <v-chip
            v-if="item.soldeOuverture"
            color="indigo"
            size="x-small"
            variant="tonal"
            class="ml-2"
          >
            Ouverture
          </v-chip>
          <v-chip v-if="item.clotureExercice" color="deep-purple" size="x-small" variant="tonal" class="ml-2">
            Clôture d'exercice
          </v-chip>
          <v-chip v-if="item.pieceRestaureeReference" color="teal" size="x-small" variant="tonal" class="ml-2">
            Restaure {{ item.pieceRestaureeReference }}
          </v-chip>
        </template>
        <template #item.statut="{ item }">
          <v-chip :color="statutColor[item.statut] || 'grey'" size="small" variant="flat">
            {{ item.statut }}
          </v-chip>
        </template>
        <template #item.tracabilite="{ item }">
          <div class="text-caption">{{ item.createdByEmail || '—' }}</div>
          <div v-if="item.comptabiliseeParEmail" class="text-caption text-success">
            validée par {{ item.comptabiliseeParEmail }}
          </div>
        </template>
        <template #item.totalDebit="{ item }">{{ fmt(item.totalDebit) }}</template>
        <template #item.totalCredit="{ item }">{{ fmt(item.totalCredit) }}</template>
        <template #item.actions="{ item }">
          <div class="d-flex ga-1">
            <template v-if="canWrite">
              <v-btn
                v-if="item.statut === 'BROUILLON' && item.comptabilisable && peutValider"
                size="small"
                color="success"
                variant="tonal"
                @click="comptabiliser(item.id)"
              >
                Comptabiliser
              </v-btn>
              <v-btn
                v-if="item.statut === 'BROUILLON'"
                size="small"
                color="error"
                variant="text"
                icon="mdi-delete-outline"
                @click="supprimerBrouillon(item.id)"
              />
              <v-btn
                v-if="item.statut === 'COMPTABILISEE' && item.extournable && peutAnnuler"
                size="small"
                color="error"
                variant="tonal"
                @click="annuler(item)"
              >
                Annuler
              </v-btn>
            </template>
            <!-- Hors du bloc ci-dessus : l'administrateur, qui ne saisit rien, peut rétablir une comptabilisation. -->
            <v-btn
              v-if="item.restaurable && peutRestaurer"
              size="small"
              color="teal"
              variant="tonal"
              prepend-icon="mdi-restore"
              :title="retablitDirectement
                ? 'Rétablir la comptabilisation annulée : une nouvelle pièce identique est comptabilisée tout de suite'
                : 'Créer un nouveau brouillon identique, que le DFIN comptabilisera'"
              @click="restaurer(item)"
            >
              Restaurer
            </v-btn>
            <v-icon
              v-if="(canWrite || peutRestaurer) && motifVerrou(item)"
              icon="mdi-lock-outline"
              size="18"
              class="text-medium-emphasis"
              :title="motifVerrou(item)!"
              :aria-label="motifVerrou(item)!"
            />
            <v-btn
              v-if="peutBasculerOuverture"
              size="small"
              variant="text"
              icon="mdi-calendar-start-outline"
              :color="item.soldeOuverture ? 'indigo' : undefined"
              :title="item.soldeOuverture
                ? 'Retirer le marqueur solde d\'ouverture (repasser en mouvement de la période)'
                : 'Marquer comme solde d\'ouverture (reclasse la pièce sans changer ses montants)'"
              @click="basculerOuverture(item)"
            />
          </div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>
