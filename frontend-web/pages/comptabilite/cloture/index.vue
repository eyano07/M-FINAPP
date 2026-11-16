<script setup lang="ts">
// Audit SYSCOHADA du 10/11/2026 (C-03, C-06, C-10, C-13) : le DFIN avance le verrou et demande les reculs et la
// clôture d'exercice ; le DA ou le DG approuve ou refuse ; l'administrateur consulte seulement.
definePageMeta({ module: 'COMPTABILITE', niveau: 'LECTURE', roles: ['DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN'] })

interface Demande {
  id: number
  type: 'RECUL_VERROU' | 'EXERCICE'
  dateDemandee: string | null
  motif: string
  statut: string
  demandePar: string
  demandeParId: number
  demandeLe: string
  decidePar: string | null
  decideLe: string | null
  commentaire: string | null
}
interface Historique {
  type: string
  ancienneDate: string | null
  nouvelleDate: string | null
  motif: string | null
  auteur: string
  le: string
}
interface Etat {
  dateCloture: string | null
  dateClotureExercice: string | null
  autoValidationAutorisee: boolean
  demandes: Demande[]
  historique: Historique[]
}
interface Controle {
  code: string
  libelle: string
  bloquant: boolean
  satisfait: boolean
  detail: string | null
}
interface Archive {
  id: number
  dateCloture: string
  nomFichier: string
  taille: number
  empreinte: string
  creePar: string
  creeLe: string
}

const api = useApi()
const auth = useAuthStore()

const loading = ref(false)
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')
const etat = ref<Etat | null>(null)
const archives = ref<Archive[]>([])

const estDfin = computed(() => auth.hasRole('DFIN'))
const estApprobateur = computed(() => auth.hasRole('DA') || auth.hasRole('DG'))
const enAttente = computed(() => (etat.value?.demandes ?? []).filter(d => d.statut === 'EN_ATTENTE'))
const decidees = computed(() => (etat.value?.demandes ?? []).filter(d => d.statut !== 'EN_ATTENTE'))

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    etat.value = await api<Etat>('/comptabilite/cloture')
    nouvelleDate.value = etat.value.dateCloture ?? ''
    archives.value = await api<Archive[]>('/comptabilite/cloture/archives').catch(() => [])
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger la clôture.')
  } finally {
    loading.value = false
  }
}

// ── Verrou de période ─────────────────────────────────────────────────────
const nouvelleDate = ref('')
const motifVerrou = ref('')
const reculDemande = computed(() => {
  const actuel = etat.value?.dateCloture
  if (!actuel) return false
  return !nouvelleDate.value || nouvelleDate.value < actuel
})

async function enregistrerVerrou(lever = false) {
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const date = lever ? null : nouvelleDate.value || null
    const avant = etat.value?.dateCloture
    etat.value = await api<Etat>('/comptabilite/cloture', {
      method: 'PUT',
      body: { dateCloture: date, motif: motifVerrou.value || null },
    })
    succes.value = etat.value.dateCloture !== avant
      ? `Verrou posé au ${fmtDate(etat.value.dateCloture!)} inclus.`
      : 'Demande enregistrée : le DA ou le DG doit l\'approuver.'
    motifVerrou.value = ''
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible d'enregistrer le verrou.")
  } finally {
    envoi.value = false
  }
}

// ── Clôture de l'exercice ─────────────────────────────────────────────────
const anneeCourante = new Date().getFullYear()
const dateExercice = ref(`${anneeCourante - 1}-12-31`)
const motifExercice = ref('')
const controles = ref<Controle[]>([])
const controlesCharges = ref(false)
const clotureAutorisee = computed(() => controlesCharges.value && controles.value.every(c => !c.bloquant || c.satisfait))

async function verifierControles() {
  envoi.value = true
  erreur.value = ''
  try {
    controles.value = await api<Controle[]>('/comptabilite/cloture/exercice/controles', {
      query: { date: dateExercice.value },
    })
    controlesCharges.value = true
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de vérifier les contrôles.')
  } finally {
    envoi.value = false
  }
}
watch(dateExercice, () => { controlesCharges.value = false })

async function demanderClotureExercice() {
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    etat.value = await api<Etat>('/comptabilite/cloture/exercice', {
      method: 'POST',
      body: { dateCloture: dateExercice.value, motif: motifExercice.value || null },
    })
    succes.value = 'Demande de clôture enregistrée : le DA ou le DG doit l\'approuver.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de demander la clôture de l'exercice.")
  } finally {
    envoi.value = false
  }
}

// ── Décisions (DA, DG) ────────────────────────────────────────────────────
const dialogDecision = ref(false)
const decision = ref<{ demande: Demande, approuver: boolean } | null>(null)
const commentaire = ref('')

function ouvrirDecision(demande: Demande, approuver: boolean) {
  decision.value = { demande, approuver }
  commentaire.value = ''
  dialogDecision.value = true
}

async function decider() {
  if (!decision.value) return
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  const { demande, approuver } = decision.value
  try {
    if (approuver) {
      const res = await api<any>(`/comptabilite/cloture/demandes/${demande.id}/approuver`, {
        method: 'POST', body: { commentaire: commentaire.value || null },
      })
      succes.value = demande.type === 'EXERCICE' && res
        ? `Exercice clôturé au ${fmtDate(res.dateCloture)} : résultat ${fmtMontant(res.resultatNet)}`
          + (res.pieceClotureReference ? `, pièce ${res.pieceClotureReference}` : '') + '. Archive enregistrée.'
        : 'Verrou reculé comme demandé.'
    } else {
      await api(`/comptabilite/cloture/demandes/${demande.id}/refuser`, {
        method: 'POST', body: { commentaire: commentaire.value },
      })
      succes.value = 'Demande refusée.'
    }
    dialogDecision.value = false
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Décision impossible.')
  } finally {
    envoi.value = false
  }
}

async function retirer(demande: Demande) {
  envoi.value = true
  erreur.value = ''
  try {
    etat.value = await api<Etat>(`/comptabilite/cloture/demandes/${demande.id}/retirer`, { method: 'POST' })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Retrait impossible.')
  } finally {
    envoi.value = false
  }
}

// ── Dérogation à la validation par une autre personne (DA, DG) ───────────
const motifDerogation = ref('')
async function basculerAutoValidation() {
  envoi.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const autorisee = !etat.value?.autoValidationAutorisee
    etat.value = await api<Etat>('/comptabilite/cloture/auto-validation', {
      method: 'PUT', query: { autorisee }, body: { commentaire: motifDerogation.value },
    })
    motifDerogation.value = ''
    succes.value = autorisee
      ? 'Dérogation posée : l\'auteur d\'une pièce peut la comptabiliser lui-même.'
      : 'Dérogation retirée : une pièce est comptabilisée par une autre personne que son auteur.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Modification impossible.')
  } finally {
    envoi.value = false
  }
}

async function telechargerArchive(a: Archive) {
  try {
    await telechargerFichier(api, `/comptabilite/cloture/archives/${a.id}`, a.nomFichier)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de télécharger l'archive.")
  }
}

onMounted(charger)

const fmtDate = (d: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const fmtDateHeure = (d: string | null) => (d ? new Date(d).toLocaleString('fr-FR') : '')
const fmtMontant = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 2 }).format(v || 0)
const libelleType = (t: string) => ({
  RECUL_VERROU: 'Recul du verrou', EXERCICE: "Clôture de l'exercice", VERROU: 'Verrou de période',
  PARAMETRE: 'Paramètre',
} as Record<string, string>)[t] ?? t
const couleurStatut = (s: string) => ({
  EN_ATTENTE: 'warning', APPROUVEE: 'success', REFUSEE: 'error', RETIREE: 'grey',
} as Record<string, string>)[s] ?? 'grey'
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Clôture comptable</h1>
        <p class="page-sub">
          Verrou de période, clôture définitive de l'exercice et double validation (DFIN puis DA ou DG)
        </p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4">{{ succes }}</v-alert>

    <div class="grille-cloture">
      <!-- ── État ──────────────────────────────────────────────────────── -->
      <v-card class="classroom-card pa-6" :loading="loading">
        <div class="d-flex align-center ga-3 mb-4">
          <v-icon :icon="etat?.dateCloture ? 'mdi-lock' : 'mdi-lock-open-variant'" size="32"
                  :color="etat?.dateCloture ? 'error' : 'success'" />
          <div>
            <div class="text-h6 font-weight-bold">
              {{ etat?.dateCloture ? `Verrou au ${fmtDate(etat.dateCloture)}` : 'Aucun verrou de période' }}
            </div>
            <div class="text-caption text-medium-emphasis">
              Aucune écriture ne peut être créée, comptabilisée ou extournée à cette date ou avant.
            </div>
          </div>
        </div>
        <div class="d-flex align-center ga-3">
          <v-icon icon="mdi-book-lock" size="28" color="deep-purple" />
          <div>
            <div class="font-weight-bold">
              {{ etat?.dateClotureExercice
                ? `Dernier exercice clôturé au ${fmtDate(etat.dateClotureExercice)}`
                : 'Aucun exercice clôturé définitivement' }}
            </div>
            <div class="text-caption text-medium-emphasis">
              Une clôture d'exercice est définitive : le verrou ne redescend jamais en dessous.
            </div>
          </div>
        </div>
      </v-card>

      <!-- ── Verrou de période (DFIN) ──────────────────────────────────── -->
      <v-card class="classroom-card pa-6">
        <div class="text-h6 font-weight-bold mb-2">Verrou de période</div>
        <template v-if="estDfin">
          <v-text-field v-model="nouvelleDate" label="Verrouiller jusqu'au (inclus)" type="date"
                        variant="outlined" density="comfortable" class="mb-2" />
          <v-textarea v-model="motifVerrou" :label="reculDemande ? 'Motif (obligatoire pour reculer)' : 'Motif (facultatif)'"
                      variant="outlined" density="comfortable" rows="2" auto-grow class="mb-2" />
          <v-alert v-if="reculDemande" type="info" variant="tonal" density="compact" class="mb-3">
            Reculer ou lever le verrou rouvre des périodes : c'est une demande que le DA ou le DG approuve.
          </v-alert>
          <div class="d-flex flex-wrap ga-3">
            <v-btn color="primary" :loading="envoi" prepend-icon="mdi-lock" @click="enregistrerVerrou(false)">
              {{ reculDemande ? 'Demander le recul' : 'Avancer le verrou' }}
            </v-btn>
            <v-btn v-if="etat?.dateCloture && !etat?.dateClotureExercice" variant="outlined" color="error"
                   :loading="envoi" prepend-icon="mdi-lock-open-variant" @click="enregistrerVerrou(true)">
              Demander la levée
            </v-btn>
          </div>
        </template>
        <v-alert v-else type="info" variant="tonal" density="compact">
          Le DFIN déplace le verrou ; le DA ou le DG approuve un recul.
        </v-alert>
      </v-card>
    </div>

    <!-- ── Demandes en attente ─────────────────────────────────────────── -->
    <v-card v-if="enAttente.length" class="classroom-card pa-6 mt-4">
      <div class="text-h6 font-weight-bold mb-3">Demandes en attente de décision</div>
      <div v-for="d in enAttente" :key="d.id" class="demande">
        <div>
          <div class="font-weight-bold">
            {{ libelleType(d.type) }}
            {{ d.type === 'EXERCICE' ? `au ${fmtDate(d.dateDemandee)}` : (d.dateDemandee ? `au ${fmtDate(d.dateDemandee)}` : '(levée)') }}
          </div>
          <div class="text-body-2">{{ d.motif }}</div>
          <div class="text-caption text-medium-emphasis">Demandée par {{ d.demandePar }} le {{ fmtDateHeure(d.demandeLe) }}</div>
        </div>
        <div class="d-flex flex-wrap ga-2">
          <template v-if="estApprobateur && d.demandeParId !== auth.user?.id">
            <v-btn color="success" variant="flat" size="small" @click="ouvrirDecision(d, true)">Approuver</v-btn>
            <v-btn color="error" variant="outlined" size="small" @click="ouvrirDecision(d, false)">Refuser</v-btn>
          </template>
          <v-btn v-if="d.demandeParId === auth.user?.id" variant="text" size="small" @click="retirer(d)">Retirer</v-btn>
        </div>
      </div>
    </v-card>

    <!-- ── Clôture de l'exercice ───────────────────────────────────────── -->
    <v-card class="classroom-card pa-6 mt-4">
      <div class="d-flex align-center ga-3 mb-3">
        <v-icon icon="mdi-book-check-outline" size="32" color="deep-purple" />
        <div>
          <div class="text-h6 font-weight-bold">Clôture de l'exercice</div>
          <div class="text-caption text-medium-emphasis">
            Réévaluation des soldes en devise, soldage des classes 6, 7 et 8 vers le résultat, verrou définitif et
            archive des livres — exécutés quand le DA ou le DG approuve la demande du DFIN.
          </div>
        </div>
      </div>
      <div class="d-flex flex-wrap ga-3 align-center mb-3">
        <v-text-field v-model="dateExercice" label="Exercice clos au" type="date" variant="outlined"
                      density="comfortable" hide-details style="max-width: 220px" />
        <v-btn variant="outlined" :loading="envoi" prepend-icon="mdi-clipboard-check-outline" @click="verifierControles">
          Vérifier les contrôles
        </v-btn>
      </div>

      <v-list v-if="controlesCharges" density="compact" class="mb-3">
        <v-list-item v-for="c in controles" :key="c.code">
          <template #prepend>
            <v-icon :icon="c.satisfait ? 'mdi-check-circle' : (c.bloquant ? 'mdi-close-circle' : 'mdi-alert')"
                    :color="c.satisfait ? 'success' : (c.bloquant ? 'error' : 'warning')" />
          </template>
          <v-list-item-title>{{ c.libelle }}<span v-if="!c.bloquant" class="text-caption"> (avertissement)</span></v-list-item-title>
          <v-list-item-subtitle v-if="c.detail" class="texte-libre">{{ c.detail }}</v-list-item-subtitle>
        </v-list-item>
      </v-list>

      <template v-if="estDfin && controlesCharges">
        <v-textarea v-model="motifExercice" label="Motif ou commentaire (facultatif)" variant="outlined"
                    density="comfortable" rows="2" auto-grow class="mb-2" />
        <v-btn color="deep-purple" variant="flat" :disabled="!clotureAutorisee" :loading="envoi"
               prepend-icon="mdi-send" @click="demanderClotureExercice">
          Demander la clôture
        </v-btn>
        <div v-if="!clotureAutorisee" class="text-caption text-error mt-2">
          Les contrôles bloquants doivent être satisfaits avant la demande.
        </div>
      </template>
    </v-card>

    <!-- ── Dérogation (DA, DG) ─────────────────────────────────────────── -->
    <v-card class="classroom-card pa-6 mt-4">
      <div class="text-h6 font-weight-bold mb-1">Validation des pièces par une autre personne</div>
      <p class="text-body-2 mb-3">
        {{ etat?.autoValidationAutorisee
          ? 'Dérogation active : l\'auteur d\'une pièce peut la comptabiliser lui-même.'
          : 'Règle appliquée : celui qui saisit une pièce ne la comptabilise pas lui-même.' }}
      </p>
      <template v-if="estApprobateur">
        <v-text-field v-model="motifDerogation" label="Motif (obligatoire)" variant="outlined" density="comfortable"
                      class="mb-2" />
        <v-btn :color="etat?.autoValidationAutorisee ? 'primary' : 'warning'" variant="outlined" :loading="envoi"
               :disabled="motifDerogation.trim().length < 5" @click="basculerAutoValidation">
          {{ etat?.autoValidationAutorisee ? 'Retirer la dérogation' : 'Autoriser l\'auto-validation' }}
        </v-btn>
      </template>
    </v-card>

    <!-- ── Archives ────────────────────────────────────────────────────── -->
    <v-card v-if="archives.length" class="classroom-card pa-6 mt-4">
      <div class="text-h6 font-weight-bold mb-3">Archives des clôtures</div>
      <div v-for="a in archives" :key="a.id" class="demande">
        <div>
          <div class="font-weight-bold">Exercice clos au {{ fmtDate(a.dateCloture) }}</div>
          <div class="text-caption text-medium-emphasis">
            {{ a.nomFichier }} — {{ Math.round(a.taille / 1024) }} Ko — {{ a.creePar }}, {{ fmtDateHeure(a.creeLe) }}
          </div>
          <div class="text-caption empreinte">SHA-256 {{ a.empreinte }}</div>
        </div>
        <v-btn variant="outlined" size="small" prepend-icon="mdi-download" @click="telechargerArchive(a)">
          Télécharger
        </v-btn>
      </div>
    </v-card>

    <!-- ── Historique ──────────────────────────────────────────────────── -->
    <v-card class="classroom-card pa-6 mt-4">
      <div class="text-h6 font-weight-bold mb-3">Historique</div>
      <div v-if="!(etat?.historique?.length || decidees.length)" class="text-medium-emphasis">Aucun changement enregistré.</div>
      <div v-for="(h, i) in etat?.historique ?? []" :key="'h' + i" class="ligne-historique">
        <strong>{{ libelleType(h.type) }}</strong> :
        {{ fmtDate(h.ancienneDate) }} → {{ fmtDate(h.nouvelleDate) }}
        <span class="text-medium-emphasis">— {{ h.auteur }}, {{ fmtDateHeure(h.le) }}</span>
        <div v-if="h.motif" class="text-caption">{{ h.motif }}</div>
      </div>
      <div v-for="d in decidees" :key="'d' + d.id" class="ligne-historique">
        <v-chip :color="couleurStatut(d.statut)" size="x-small" class="mr-2">{{ d.statut }}</v-chip>
        {{ libelleType(d.type) }} {{ fmtDate(d.dateDemandee) }}
        <span class="text-medium-emphasis">— demandée par {{ d.demandePar }}, décidée par {{ d.decidePar ?? '—' }}</span>
        <div v-if="d.commentaire" class="text-caption">{{ d.commentaire }}</div>
      </div>
    </v-card>

    <v-dialog v-model="dialogDecision" max-width="520">
      <v-card v-if="decision" class="classroom-card pa-6">
        <div class="text-h6 font-weight-bold mb-3">
          {{ decision.approuver ? 'Approuver' : 'Refuser' }} : {{ libelleType(decision.demande.type) }}
          {{ fmtDate(decision.demande.dateDemandee) }}
        </div>
        <p v-if="decision.approuver && decision.demande.type === 'EXERCICE'" class="mb-3">
          L'approbation exécute la clôture : écritures de réévaluation et de clôture, verrou définitif au
          <strong>{{ fmtDate(decision.demande.dateDemandee) }}</strong>, archive des livres. C'est irréversible.
        </p>
        <v-textarea v-model="commentaire" :label="decision.approuver ? 'Commentaire (facultatif)' : 'Motif du refus (obligatoire)'"
                    variant="outlined" rows="2" auto-grow class="mb-3" />
        <div class="d-flex ga-3 justify-end">
          <v-btn variant="text" @click="dialogDecision = false">Annuler</v-btn>
          <v-btn :color="decision.approuver ? 'success' : 'error'" variant="flat" :loading="envoi"
                 :disabled="!decision.approuver && commentaire.trim().length < 5" @click="decider">
            {{ decision.approuver ? 'Approuver' : 'Refuser' }}
          </v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.grille-cloture {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
}
.demande {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}
.ligne-historique {
  padding: 6px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}
.empreinte {
  word-break: break-all;
  font-family: monospace;
}
.texte-libre {
  white-space: normal;
}
</style>
