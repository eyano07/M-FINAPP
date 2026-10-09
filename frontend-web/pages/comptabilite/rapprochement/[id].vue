<script setup lang="ts">
/**
 * Fiche d'un relevé : pointage des opérations du relevé avec les écritures du compte (automatique puis
 * manuel), régularisation des opérations absentes de la comptabilité, état de rapprochement et validation
 * par le DFIN. Montants dans la devise du compte.
 */
definePageMeta({ module: 'RAPPROCHEMENT', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'CAISSIER'] })

interface Ligne { id: number, ordre: number, dateOperation: string, libelle: string, reference: string | null, entree: number, sortie: number, pointageId: number | null, automatique: boolean, pieceRegularisation: string | null }
interface Ecriture { id: number, date: string, libelle: string, pieceReference: string | null, journal: string | null, entree: number, sortie: number, pointageId: number | null, anterieure: boolean }
interface Etat { soldeReleve: number, lignesNonPointeesEntrees: number, lignesNonPointeesSorties: number, nombreLignesNonPointees: number, soldeComptable: number, ecrituresNonPointeesEntrees: number, ecrituresNonPointeesSorties: number, nombreEcrituresNonPointees: number, ecart: number, ecartReleve: number, validable: boolean }
interface Releve {
  id: number
  etablissement: { id: number, nom: string, type: string, compteNumero: string, compteLibelle: string, devise: string }
  mois: number, annee: number, devise: string, soldeOuverture: number, soldeCloture: number
  statut: 'EN_COURS' | 'VALIDE', source: string | null, creeParNom: string | null, valideParNom: string | null
  dateValidation: string | null, motifDevalidation: string | null
  lignes: Ligne[], ecritures: Ecriture[], etat: Etat, avertissements: string[]
}

const route = useRoute()
const api = useApi()
const auth = useAuthStore()
const parametresStore = useParametresStore()
const id = computed(() => Number(route.params.id))

const r = ref<Releve | null>(null)
const chargement = ref(false)
const action = ref('')
const erreur = ref('')
const succes = ref('')

const enCours = computed(() => r.value?.statut === 'EN_COURS')
const peutEcrire = computed(() => enCours.value && auth.hasAnyRole(['ADMIN', 'DFIN', 'COMPTABLE', 'CAISSIER']))
const peutValider = computed(() => enCours.value && auth.hasAnyRole(['DFIN', 'ADMIN']))
const peutDevalider = computed(() => r.value?.statut === 'VALIDE' && auth.hasAnyRole(['ADMIN']))

const MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']
const periode = computed(() => (r.value ? `${MOIS[r.value.mois - 1]} ${r.value.annee}` : ''))
useHead({ title: computed(() => (r.value ? `Rapprochement ${r.value.etablissement.nom} ${periode.value}` : 'Rapprochement')) })

async function charger() {
  chargement.value = true
  try {
    r.value = await api<Releve>(`/rapprochements/${id.value}`)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Relevé introuvable.')
  } finally {
    chargement.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })

async function executer(cle: string, appel: () => Promise<any>, message = '') {
  action.value = cle
  erreur.value = ''
  succes.value = ''
  try {
    const res = await appel()
    if (res && typeof res === 'object' && 'lignes' in res) r.value = res as Releve
    else await charger()
    succes.value = message
    selLignes.value = []
    selEcritures.value = []
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Action impossible.')
  } finally {
    action.value = ''
  }
}

function pointageAuto() {
  const avant = r.value?.etat.nombreLignesNonPointees ?? 0
  executer('auto', () => api(`/rapprochements/${id.value}/pointage-automatique`, { method: 'POST' })).then(() => {
    const apres = r.value?.etat.nombreLignesNonPointees ?? 0
    succes.value = `${avant - apres} opération(s) pointée(s) automatiquement` + (apres ? ` ; ${apres} restent à pointer ou à régulariser.` : '.')
  })
}

// ---------------------------------------------------------------------------------------------------
// Pointage manuel
// ---------------------------------------------------------------------------------------------------
const selLignes = ref<number[]>([])
const selEcritures = ref<number[]>([])
const somme = (xs: { entree: number, sortie: number }[]) => Math.round(xs.reduce((s, x) => s + Number(x.entree) - Number(x.sortie), 0) * 100) / 100
const totalSelLignes = computed(() => somme((r.value?.lignes || []).filter(l => selLignes.value.includes(l.id))))
const totalSelEcritures = computed(() => somme((r.value?.ecritures || []).filter(e => selEcritures.value.includes(e.id))))
const selectionValide = computed(() => selLignes.value.length > 0 && selEcritures.value.length > 0 && totalSelLignes.value === totalSelEcritures.value)

function basculer(liste: typeof selLignes, idElt: number) {
  liste.value = liste.value.includes(idElt) ? liste.value.filter(x => x !== idElt) : [...liste.value, idElt]
}

function pointerSelection() {
  executer('pointer', () => api(`/rapprochements/${id.value}/pointages`, { method: 'POST', body: { ligneIds: selLignes.value, ecritureIds: selEcritures.value } }),
    'Opérations pointées.')
}

function depointer(pointageId: number) {
  executer(`dep-${pointageId}`, () => api(`/rapprochements/${id.value}/pointages/${pointageId}`, { method: 'DELETE' }), 'Pointage défait.')
}

/** Couleur d'un pointage, pour repérer d'un coup d'œil la ligne et l'écriture rapprochées ensemble. */
const PALETTE = ['#0e7490', '#7c3aed', '#b45309', '#be185d', '#15803d', '#1d4ed8', '#9a3412', '#4d7c0f']
const couleurPointage = (p: number | null) => (p ? PALETTE[p % PALETTE.length] : 'transparent')

// ---------------------------------------------------------------------------------------------------
// Régularisation
// ---------------------------------------------------------------------------------------------------
const regul = reactive({ ouvert: false, ligne: null as Ligne | null, compte: null as string | null, libelle: '', suggestion: '' })

/** Compte proposé d'après le libellé du relevé (règles simples), sinon par l'IA. */
function compteParRegle(l: Ligne): string | null {
  const t = l.libelle.toLowerCase()
  const mobile = r.value?.etablissement.type === 'MOBILE_MONEY'
  if (/agio|int[ée]r[êe]ts? d[ée]biteur|d[ée]couvert/.test(t)) return '6748'
  if (/int[ée]r[êe]ts? cr[ée]diteur|r[ée]mun[ée]ration/.test(t) && l.entree > 0) return '7713'
  if (/frais|commission|tenue de compte|cotisation carte|abonnement|sms|tva sur frais|timbre/.test(t)) return mobile ? '6317' : '6318'
  return null
}

async function ouvrirRegularisation(l: Ligne) {
  Object.assign(regul, { ouvert: true, ligne: l, compte: compteParRegle(l), libelle: `${l.libelle} (${l.dateOperation})`, suggestion: '' })
  if (!regul.compte) {
    try {
      const s = await api<{ compteNumero: string, compteLibelle: string, genereParIa: boolean }>('/ia/suggestion-compte', {
        method: 'POST', body: { description: l.libelle, sens: l.entree > 0 ? 'ENCAISSEMENT' : 'DECAISSEMENT' },
      })
      if (s?.compteNumero && !regul.compte) {
        regul.compte = s.compteNumero
        regul.suggestion = `${s.genereParIa ? 'Proposé par l’IA' : 'Proposé'} : ${s.compteNumero} ${s.compteLibelle}`
      }
    } catch { /* suggestion facultative */ }
    if (!regul.compte) regul.compte = l.entree > 0 ? '4712' : '4711'
  }
}

function regulariser() {
  if (!regul.ligne || !regul.compte) return
  const ligneId = regul.ligne.id
  executer('regul', () => api(`/rapprochements/${id.value}/lignes/${ligneId}/regularisation`, {
    method: 'POST', body: { compteContrepartie: regul.compte, libelle: regul.libelle },
  }), 'Écriture créée et pointée.').then(() => { if (!erreur.value) regul.ouvert = false })
}

// ---------------------------------------------------------------------------------------------------
// Validation, suppression
// ---------------------------------------------------------------------------------------------------
function valider() {
  if (!confirm(`Valider le rapprochement de ${r.value?.etablissement.nom} pour ${periode.value} ? Le compte sera verrouillé jusqu'à la fin du mois.`)) return
  executer('valider', () => api(`/rapprochements/${id.value}/valider`, { method: 'POST' }), 'Rapprochement validé : le mois est verrouillé sur ce compte.')
}

function devalider() {
  const motif = prompt('Motif de la dé-validation (obligatoire) :')
  if (!motif) return
  executer('devalider', () => api(`/rapprochements/${id.value}/devalider`, { method: 'POST', body: { commentaire: motif } }), 'Rapprochement rouvert.')
}

async function supprimer() {
  if (!confirm('Supprimer ce relevé ? Les pointages seront défaits ; les écritures de régularisation déjà passées restent en comptabilité.')) return
  action.value = 'supprimer'
  try {
    await api(`/rapprochements/${id.value}`, { method: 'DELETE' })
    await navigateTo('/comptabilite/rapprochement')
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Suppression impossible.')
  } finally {
    action.value = ''
  }
}

const imprimer = () => window.print()
const dateImpression = new Date().toLocaleDateString('fr-FR')
const fmtDate = (d?: string | null) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
const filtre = ref<'tout' | 'apointer'>('tout')
const lignesAffichees = computed(() => (r.value?.lignes || []).filter(l => filtre.value === 'tout' || !l.pointageId))
const ecrituresAffichees = computed(() => (r.value?.ecritures || []).filter(e => filtre.value === 'tout' || !e.pointageId))
const suspens = computed(() => (r.value?.ecritures || []).filter(e => !e.pointageId))
</script>

<template>
  <div>
    <!-- En-tête imprimé (papier à en-tête du thème) -->
    <div class="etat-print-header print-entete">
      <div class="etat-print-header__brand print-entete__marque">
        <div class="etat-print-header__logo print-entete__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-bank-check" size="16" color="white" />
        </div>
        <div>
          <span class="etat-print-header__company print-entete__nom">{{ parametresStore.parametres.nom }}</span>
          <span class="etat-print-header__doc">État de rapprochement bancaire</span>
        </div>
      </div>
      <div class="etat-print-header__meta print-entete__meta">
        <span>Compte : <strong>{{ r?.etablissement.nom }} — {{ r?.etablissement.compteNumero }}</strong></span>
        <span>Période : <strong>{{ periode }}</strong> · {{ r?.devise }}</span>
        <span>Imprimé le : {{ dateImpression }}</span>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 no-print" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-skeleton-loader v-if="chargement && !r" type="heading, card, table" />

    <template v-if="r">
      <div class="page-head no-print">
        <div style="min-width: 0">
          <h1 class="page-title">{{ r.etablissement.nom }} — {{ periode }}</h1>
          <p class="page-sub">
            Compte {{ r.etablissement.compteNumero }} · montants en {{ r.devise }} · source : {{ r.source || '—' }}
            <span v-if="r.creeParNom"> · importé par {{ r.creeParNom }}</span>
          </p>
        </div>
        <div class="d-flex flex-wrap ga-2 justify-end align-center">
          <v-chip :color="r.statut === 'VALIDE' ? 'success' : 'warning'" variant="tonal" :prepend-icon="r.statut === 'VALIDE' ? 'mdi-check-decagram' : 'mdi-progress-clock'">
            {{ r.statut === 'VALIDE' ? `Validé par ${r.valideParNom || '—'} le ${fmtDate(r.dateValidation)}` : 'En cours' }}
          </v-chip>
          <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/comptabilite/rapprochement">Rapprochements</v-btn>
        </div>
      </div>

      <v-card class="classroom-card pa-3 mb-4 no-print rb-actions">
        <v-btn v-if="peutEcrire" color="primary" prepend-icon="mdi-auto-fix" :loading="action === 'auto'" @click="pointageAuto">Pointage automatique</v-btn>
        <v-btn v-if="peutValider" color="success" prepend-icon="mdi-check-decagram" :disabled="!r.etat.validable" :loading="action === 'valider'" @click="valider">Valider</v-btn>
        <v-btn v-if="peutDevalider" color="warning" variant="tonal" prepend-icon="mdi-lock-open-variant-outline" :loading="action === 'devalider'" @click="devalider">Dé-valider</v-btn>
        <v-btn v-if="peutEcrire" color="error" variant="text" prepend-icon="mdi-delete-outline" :loading="action === 'supprimer'" @click="supprimer">Supprimer</v-btn>
        <v-spacer />
        <v-btn-toggle v-model="filtre" mandatory density="compact" variant="outlined" color="primary">
          <v-btn value="tout" size="small">Tout</v-btn>
          <v-btn value="apointer" size="small">À pointer</v-btn>
        </v-btn-toggle>
        <v-btn variant="tonal" prepend-icon="mdi-printer" @click="imprimer">Imprimer l'état</v-btn>
      </v-card>

      <v-alert v-for="(a, i) in r.avertissements" :key="i" type="warning" variant="tonal" density="compact" class="mb-2 no-print">{{ a }}</v-alert>
      <v-alert v-if="r.motifDevalidation" type="info" variant="tonal" density="compact" class="mb-2 no-print">Dernière dé-validation : {{ r.motifDevalidation }}</v-alert>

      <!-- État de rapprochement -->
      <v-card class="classroom-card pa-4 mb-4 rb-etat-carte">
        <div class="rb-titre">État de rapprochement au {{ new Date(r.annee, r.mois, 0).toLocaleDateString('fr-FR') }} ({{ r.devise }})</div>
        <div class="rb-etat-grille">
          <div class="rb-etat-col">
            <div class="rb-l"><span>Solde selon le relevé</span><strong>{{ fmtMontant(r.etat.soldeReleve) }}</strong></div>
            <div class="rb-l"><span>− opérations du relevé non pointées (entrées)</span><span>{{ fmtMontant(r.etat.lignesNonPointeesEntrees) }}</span></div>
            <div class="rb-l"><span>+ opérations du relevé non pointées (sorties)</span><span>{{ fmtMontant(r.etat.lignesNonPointeesSorties) }}</span></div>
          </div>
          <div class="rb-etat-col">
            <div class="rb-l"><span>Solde selon la comptabilité</span><strong>{{ fmtMontant(r.etat.soldeComptable) }}</strong></div>
            <div class="rb-l"><span>− écritures non au relevé (entrées : remises non créditées)</span><span>{{ fmtMontant(r.etat.ecrituresNonPointeesEntrees) }}</span></div>
            <div class="rb-l"><span>+ écritures non au relevé (sorties : chèques non débités)</span><span>{{ fmtMontant(r.etat.ecrituresNonPointeesSorties) }}</span></div>
          </div>
        </div>
        <div class="rb-ecart" :class="Number(r.etat.ecart) === 0 ? 'rb-ecart--ok' : 'rb-ecart--ko'" role="status">
          Écart de rapprochement : <strong>{{ fmtMontant(r.etat.ecart) }} {{ r.devise }}</strong>
          <span v-if="Number(r.etat.ecart) === 0 && !r.etat.nombreLignesNonPointees"> — rapproché</span>
          <span v-else-if="r.etat.nombreLignesNonPointees"> — {{ r.etat.nombreLignesNonPointees }} opération(s) du relevé à pointer ou à régulariser</span>
        </div>
        <p v-if="Number(r.etat.ecartReleve) !== 0" class="rb-alerte">
          Le relevé est incohérent : ouverture ({{ fmtMontant(r.soldeOuverture) }}) + opérations − clôture = {{ fmtMontant(r.etat.ecartReleve) }}.
        </p>
      </v-card>

      <!-- Barre de pointage manuel -->
      <div v-if="peutEcrire && (selLignes.length || selEcritures.length)" class="rb-barre no-print" role="region" aria-label="Pointage de la sélection">
        <span>Relevé : <strong>{{ fmtMontant(totalSelLignes) }}</strong> ({{ selLignes.length }})</span>
        <span>Écritures : <strong>{{ fmtMontant(totalSelEcritures) }}</strong> ({{ selEcritures.length }})</span>
        <span :class="selectionValide ? 'text-success' : 'text-error'">{{ selectionValide ? 'Montants égaux' : `Écart ${fmtMontant(totalSelLignes - totalSelEcritures)}` }}</span>
        <v-spacer />
        <v-btn variant="text" size="small" @click="selLignes = []; selEcritures = []">Effacer</v-btn>
        <v-btn color="primary" size="small" :disabled="!selectionValide" :loading="action === 'pointer'" @click="pointerSelection">Pointer la sélection</v-btn>
      </div>

      <!-- Deux colonnes -->
      <div class="rb-colonnes no-print">
        <v-card class="classroom-card">
          <div class="rb-col-tete">
            <span class="rb-titre">Relevé de la banque</span>
            <span class="text-caption">{{ r.lignes.length }} opérations · ouverture {{ fmtMontant(r.soldeOuverture) }} · clôture {{ fmtMontant(r.soldeCloture) }}</span>
          </div>
          <div v-for="l in lignesAffichees" :key="l.id" class="rb-op" :class="{ 'rb-op--pointee': l.pointageId, 'rb-op--sel': selLignes.includes(l.id) }"
            :style="{ borderLeftColor: couleurPointage(l.pointageId) }">
            <span class="rb-op__sel">
              <input v-if="peutEcrire && !l.pointageId" type="checkbox" :checked="selLignes.includes(l.id)"
                :aria-label="`Sélectionner ${l.libelle}`" @change="basculer(selLignes, l.id)">
              <v-icon v-else-if="l.pointageId" icon="mdi-check-circle" size="18" :color="couleurPointage(l.pointageId)" />
            </span>
            <div class="rb-op__texte">
              <span class="rb-op__lib">{{ l.libelle }}</span>
              <span class="rb-op__meta">{{ fmtDate(l.dateOperation) }}<template v-if="l.reference"> · réf. {{ l.reference }}</template>
                <template v-if="l.pointageId"> · {{ l.pieceRegularisation ? `régularisé (${l.pieceRegularisation})` : (l.automatique ? 'pointé auto.' : 'pointé') }}</template>
              </span>
            </div>
            <span class="rb-op__montant" :class="l.entree > 0 ? 'rb-entree' : 'rb-sortie'">{{ l.entree > 0 ? '+' : '−' }}{{ fmtMontant(l.entree > 0 ? l.entree : l.sortie) }}</span>
            <div class="rb-op__actions">
              <v-btn v-if="peutEcrire && !l.pointageId" size="x-small" variant="tonal" color="purple" @click="ouvrirRegularisation(l)">Régulariser</v-btn>
              <v-btn v-if="peutEcrire && l.pointageId" size="x-small" variant="text" icon="mdi-link-off" :aria-label="`Dépointer ${l.libelle}`"
                :title="'Dépointer'" :loading="action === `dep-${l.pointageId}`" @click="depointer(l.pointageId)" />
            </div>
          </div>
          <p v-if="!lignesAffichees.length" class="rb-vide-col">Rien à afficher.</p>
        </v-card>

        <v-card class="classroom-card">
          <div class="rb-col-tete">
            <span class="rb-titre">Comptabilité — compte {{ r.etablissement.compteNumero }}</span>
            <span class="text-caption">{{ r.ecritures.length }} écritures (dont suspens des mois précédents)</span>
          </div>
          <div v-for="e in ecrituresAffichees" :key="e.id" class="rb-op" :class="{ 'rb-op--pointee': e.pointageId, 'rb-op--sel': selEcritures.includes(e.id) }"
            :style="{ borderLeftColor: couleurPointage(e.pointageId) }">
            <span class="rb-op__sel">
              <input v-if="peutEcrire && !e.pointageId" type="checkbox" :checked="selEcritures.includes(e.id)"
                :aria-label="`Sélectionner ${e.libelle}`" @change="basculer(selEcritures, e.id)">
              <v-icon v-else-if="e.pointageId" icon="mdi-check-circle" size="18" :color="couleurPointage(e.pointageId)" />
            </span>
            <div class="rb-op__texte">
              <span class="rb-op__lib">{{ e.libelle }}</span>
              <span class="rb-op__meta">{{ fmtDate(e.date) }} · {{ e.pieceReference || '—' }}<template v-if="e.anterieure"> · suspens antérieur</template></span>
            </div>
            <span class="rb-op__montant" :class="e.entree > 0 ? 'rb-entree' : 'rb-sortie'">{{ e.entree > 0 ? '+' : '−' }}{{ fmtMontant(e.entree > 0 ? e.entree : e.sortie) }}</span>
            <div class="rb-op__actions" />
          </div>
          <p v-if="!ecrituresAffichees.length" class="rb-vide-col">Rien à afficher.</p>
        </v-card>
      </div>

      <!-- Suspens (impression) -->
      <v-card class="classroom-card pa-4 mb-4 rb-print-seul">
        <div class="rb-titre mb-2">Écritures en suspens (non encore au relevé)</div>
        <table class="rb-print-table">
          <thead><tr><th>Date</th><th>Pièce</th><th>Libellé</th><th class="num">Entrée</th><th class="num">Sortie</th></tr></thead>
          <tbody>
            <tr v-for="e in suspens" :key="e.id"><td>{{ fmtDate(e.date) }}</td><td>{{ e.pieceReference }}</td><td>{{ e.libelle }}</td>
              <td class="num">{{ e.entree ? fmtMontant(e.entree) : '' }}</td><td class="num">{{ e.sortie ? fmtMontant(e.sortie) : '' }}</td></tr>
            <tr v-if="!suspens.length"><td colspan="5">Aucune.</td></tr>
          </tbody>
        </table>
        <div class="rb-signatures">
          <div><span>Établi par</span><strong>{{ r.creeParNom || '' }}</strong><div class="rb-signatures__ligne" /></div>
          <div><span>Validé par (DFIN)</span><strong>{{ r.valideParNom || '' }}</strong><div class="rb-signatures__ligne" /></div>
        </div>
      </v-card>
    </template>

    <!-- Régularisation -->
    <v-dialog v-model="regul.ouvert" max-width="560">
      <v-card v-if="regul.ligne">
        <v-card-title>Régulariser une opération du relevé</v-card-title>
        <v-card-text>
          <p class="text-body-2 mb-3">
            <strong>{{ regul.ligne.libelle }}</strong> — {{ fmtDate(regul.ligne.dateOperation) }} —
            {{ regul.ligne.entree > 0 ? 'entrée' : 'sortie' }} de {{ fmtMontant(regul.ligne.entree > 0 ? regul.ligne.entree : regul.ligne.sortie) }} {{ r?.devise }}
          </p>
          <ComptabiliteSelecteurCompte v-model="regul.compte" label="Compte de contrepartie *" />
          <p v-if="regul.suggestion" class="text-caption mt-1">{{ regul.suggestion }}</p>
          <p class="text-caption text-medium-emphasis mt-1">
            Repères : frais bancaires 6318 (mobile money 6317), agios 6748, intérêts reçus 7713, opération à identifier 4711 / 4712.
          </p>
          <v-text-field v-model="regul.libelle" label="Libellé de l'écriture" class="mt-3" />
          <p class="text-caption">
            Écriture au journal {{ r?.etablissement.type === 'MOBILE_MONEY' ? 'Mobile money' : 'Banque' }} datée du {{ fmtDate(regul.ligne.dateOperation) }} :
            {{ regul.ligne.entree > 0 ? `débit ${r?.etablissement.compteNumero}, crédit ${regul.compte || '…'}` : `débit ${regul.compte || '…'}, crédit ${r?.etablissement.compteNumero}` }}.
          </p>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn variant="text" @click="regul.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!regul.compte" :loading="action === 'regul'" @click="regulariser">Créer l'écriture</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.rb-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.rb-titre { font-weight: 700; font-size: 0.95rem; }
.rb-etat-grille { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; margin-top: 10px; }
.rb-l { display: flex; justify-content: space-between; gap: 12px; font-size: 0.86rem; padding: 3px 0; font-variant-numeric: tabular-nums; }
.rb-l span:first-child { color: #4b5563; }
.rb-ecart { margin-top: 12px; padding: 10px 12px; border-radius: 10px; font-size: 0.92rem; }
.rb-ecart--ok { background: #dcfce7; color: #166534; }
.rb-ecart--ko { background: #fef3c7; color: #92400e; }
.rb-alerte { color: #b91c1c; font-size: 0.82rem; margin: 8px 0 0; }
.rb-barre { position: sticky; top: 72px; z-index: 3; display: flex; flex-wrap: wrap; gap: 14px; align-items: center; background: #fff; border: 1px solid var(--color-primary-mid); border-radius: 12px; padding: 8px 12px; margin-bottom: 12px; box-shadow: 0 4px 14px rgba(15, 23, 42, 0.08); font-size: 0.85rem; }
.rb-colonnes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-bottom: 16px; }
.rb-col-tete { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border-bottom: 1px solid #eef0f3; }
.rb-op { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 6px; padding: 7px 10px; border-top: 1px solid #f3f4f6; border-left: 4px solid transparent; }
.rb-op--pointee { background: #fafbfc; }
.rb-op--pointee .rb-op__lib { color: #4b5563; }
.rb-op--sel { background: var(--color-primary-lighter); }
.rb-op__sel { display: inline-flex; align-items: center; justify-content: center; width: 28px; }
.rb-op__sel input { width: 18px; height: 18px; accent-color: var(--color-primary); cursor: pointer; }
.rb-op__texte { display: flex; flex-direction: column; min-width: 0; }
.rb-op__lib { font-size: 0.84rem; overflow-wrap: anywhere; }
.rb-op__meta { font-size: 0.72rem; color: #4b5563; }
.rb-op__montant { font-weight: 700; font-size: 0.86rem; font-variant-numeric: tabular-nums; white-space: nowrap; }
.rb-op__actions { display: flex; justify-content: flex-end; min-width: 30px; }
.rb-entree { color: #15803d; }
.rb-sortie { color: #b91c1c; }
.rb-vide-col { color: #4b5563; font-size: 0.85rem; text-align: center; padding: 16px; margin: 0; }
.rb-print-seul { display: none; }
@media (max-width: 960px) {
  .rb-colonnes, .rb-etat-grille { grid-template-columns: 1fr; }
  .rb-op { grid-template-columns: auto minmax(0, 1fr) auto; }
  .rb-op__actions { grid-column: 2 / -1; justify-content: flex-start; }
}
@media print {
  .rb-print-seul { display: block; }
  .rb-print-table { width: 100%; border-collapse: collapse; font-size: 0.72rem; }
  .rb-print-table th, .rb-print-table td { border-bottom: 1px solid #e5e7eb; padding: 3px 4px; text-align: left; }
  .rb-print-table .num { text-align: right; }
  .rb-signatures { display: grid; grid-template-columns: 1fr 1fr; gap: 40px; margin-top: 24px; }
  .rb-signatures span { display: block; font-weight: 700; font-size: 0.75rem; }
  .rb-signatures__ligne { border-bottom: 1px solid #9ca3af; height: 46px; }
}
</style>
