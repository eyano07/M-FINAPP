<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT', roles: ['RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN'] })

/**
 * Paramètres du module Restaurant.
 *
 * - La devise d'affichage des montants (carte, stock, tableaux de bord).
 *   Elle n'affecte que la présentation — le grand livre reste en USD et
 *   Article.prixVente en FC ; la conversion est appliquée à la lecture par
 *   le store (voir fmtMontant/fmtMontantDepuisFC). L'arrondi est fixe :
 *   francs sans décimale, dollars à 2 décimales.
 * - Pour information seulement, la répartition des sorties sur les lots de
 *   stock, fixée (voir LotStockService.methode côté serveur) : FIFO pour les
 *   boissons, CMP pour les provisions. Un suivi de gestion, sans aucun effet
 *   sur la comptabilité, qui reste au coût moyen pondéré.
 *
 * - Le retrait des plats périmés : manuel (une notification prévient le
 *   responsable, qui retire les portions) ou automatique (la tâche planifiée
 *   les retire d'elle-même, au nom de l'administrateur qui active ce mode).
 *   Réglage de l'administrateur seul.
 *
 * L'enregistrement de la devise est reserve a RESP_RESTAURANT/ADMIN cote
 * serveur (ParametresRestaurantService) : les autres roles autorises sur le
 * module consultent la valeur sans pouvoir la modifier.
 */
const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const devise = ref<'USD' | 'CDF'>('CDF')

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))
const estAdmin = computed(() => auth.hasAnyRole(['ADMIN']))

type ModeRetrait = 'MANUEL' | 'AUTOMATIQUE'
const modeRetrait = ref<ModeRetrait>('MANUEL')          // le choix en cours
const modeEnregistre = ref<ModeRetrait>('MANUEL')       // ce que le serveur a retenu
const retraitPar = ref<string | null>(null)
const retraitParActif = ref(true)
const retraitLe = ref<string | null>(null)
const savingRetrait = ref(false)
const portionsPerimees = ref(0)

const retraitLeTexte = computed(() => {
  if (!retraitLe.value) return ''
  const d = new Date(retraitLe.value)
  return `${d.toLocaleDateString('fr-FR')} à ${d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })}`
})
/** L'administrateur qui répond des retraits automatiques est parti, désactivé ou n'est plus administrateur : la tâche ne retire plus rien. */
const retraitSuspendu = computed(() => modeEnregistre.value === 'AUTOMATIQUE' && !retraitParActif.value)

/**
 * Sans taux du jour, l'affichage en CDF ne peut rien convertir : le store
 * se replie sur les dollars. On le signale avant l'enregistrement plutot que
 * de laisser l'utilisateur decouvrir des montants en dollars sur chaque ecran.
 */
const tauxManquant = computed(() => devise.value === 'CDF' && parametres.tauxChange <= 0)

interface ParametresRestaurant {
  deviseAffichage: 'USD' | 'CDF'
  retraitPerimesMode: ModeRetrait
  retraitPerimesPar: string | null
  retraitPerimesParActif: boolean
  retraitPerimesLe: string | null
}

function appliquerRetrait(p: ParametresRestaurant) {
  modeRetrait.value = modeEnregistre.value = p.retraitPerimesMode ?? 'MANUEL'
  retraitPar.value = p.retraitPerimesPar ?? null
  retraitParActif.value = p.retraitPerimesParActif ?? true
  retraitLe.value = p.retraitPerimesLe ?? null
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const p = await api<ParametresRestaurant>('/restaurant/parametres')
    devise.value = p.deviseAffichage
    appliquerRetrait(p)
    // Pour prévenir l'administrateur qui passe en automatique : des portions périmées sont-elles déjà en stock ?
    const perimes = await api<{ quantitePerimee: number }[]>('/restaurant/plats/perimes').catch(() => [])
    portionsPerimees.value = perimes.reduce((t, x) => t + (x.quantitePerimee || 0), 0)
  } catch (e: any) {
    erreur.value = e?.data?.message || "Impossible de charger les paramètres du restaurant."
  } finally {
    loading.value = false
  }
}

async function enregistrer() {
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const p = await api<ParametresRestaurant>('/restaurant/parametres', {
      method: 'PUT',
      body: { deviseAffichage: devise.value },
    })
    devise.value = p.deviseAffichage
    // La preference est mise en cache par le store des le premier ecran du
    // module : sans rechargement force, les autres pages continueraient
    // d'afficher les montants dans l'ancienne devise jusqu'a la reconnexion.
    await parametres.charger(true)
    succes.value = 'Préférence enregistrée.'
  } catch (e: any) {
    erreur.value = e?.data?.message || "Impossible d'enregistrer la préférence."
  } finally {
    saving.value = false
  }
}

async function enregistrerRetrait() {
  savingRetrait.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const p = await api<ParametresRestaurant>('/restaurant/parametres/retrait-perimes', {
      method: 'PUT',
      body: { mode: modeRetrait.value },
    })
    appliquerRetrait(p)
    await parametres.charger(true)
    succes.value = p.retraitPerimesMode === 'AUTOMATIQUE'
      ? 'Retrait automatique des plats périmés enregistré : les écritures seront passées à votre nom.'
      : 'Retrait manuel des plats périmés enregistré : le responsable est prévenu par notification.'
  } catch (e: any) {
    erreur.value = e?.data?.message || "Impossible d'enregistrer le retrait des plats périmés."
  } finally {
    savingRetrait.value = false
  }
}

// Rechargement force du store : le taux affiche ici est toujours celui du
// jour, meme si un ecran precedent l'a lu pendant une coupure du serveur.
onMounted(async () => {
  await Promise.all([charger(), parametres.charger(true)])
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Paramètres</h1>
        <p class="page-sub">Préférences d'affichage du module Restaurant</p>
      </div>
      <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-view-dashboard-outline" to="/restaurant">
        Tableau de bord
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-card rounded="lg" border flat :loading="loading" class="mb-4">
      <v-card-item>
        <v-card-title class="text-subtitle-1">Devise d'affichage</v-card-title>
        <v-card-subtitle>
          Choisit la devise dans laquelle les montants sont présentés sur la carte,
          le stock et les tableaux de bord. Le stockage reste inchangé.
        </v-card-subtitle>
      </v-card-item>

      <v-card-text>
        <v-btn-toggle
          v-model="devise"
          mandatory
          density="comfortable"
          variant="outlined"
          rounded="lg"
          :disabled="!canWrite || loading"
        >
          <v-btn value="USD" prepend-icon="mdi-currency-usd">Dollar (USD)</v-btn>
          <v-btn value="CDF" prepend-icon="mdi-cash">Franc congolais (FC)</v-btn>
        </v-btn-toggle>

        <div class="mt-4 text-body-2 text-medium-emphasis">
          <v-icon icon="mdi-swap-horizontal" size="16" class="mr-1" />
          Taux du jour :
          <strong v-if="parametres.tauxChange > 0">
            1 USD = {{ new Intl.NumberFormat('fr-FR').format(parametres.tauxChange) }} FC
          </strong>
          <strong v-else>non défini</strong>
        </div>

        <v-alert v-if="tauxManquant" type="warning" variant="tonal" rounded="lg" class="mt-4" density="comfortable">
          Aucun taux de change n'est enregistré : tant qu'il manque, les montants restent
          affichés en dollars. Renseignez le taux du jour avant de basculer.
        </v-alert>
      </v-card-text>

      <v-card-actions v-if="canWrite" class="px-4 pb-4">
        <v-spacer />
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-content-save-outline"
          :loading="saving"
          :disabled="loading"
          @click="enregistrer"
        >
          Enregistrer
        </v-btn>
      </v-card-actions>
    </v-card>

    <v-card rounded="lg" border flat :loading="loading" class="mb-4 retrait-perimes">
      <v-card-item>
        <v-card-title class="text-subtitle-1">Retrait des plats périmés</v-card-title>
        <v-card-subtitle class="text-wrap">
          Décide ce qui se passe quand des portions dépassent leur date d'expiration (durée de conservation de la fiche
          technique) sans avoir été vendues. Seul l'administrateur peut modifier ce réglage.
        </v-card-subtitle>
      </v-card-item>

      <v-card-text>
        <v-radio-group v-model="modeRetrait" hide-details :disabled="!estAdmin || loading || savingRetrait">
          <v-radio value="MANUEL" class="retrait-option">
            <template #label>
              <div>
                <strong>Manuel, avec notification</strong>
                <div class="text-body-2 text-medium-emphasis">
                  Une notification prévient le responsable du restaurant et les administrateurs, une fois par jour tant
                  qu'il reste des plats périmés. Le responsable les retire lui-même depuis Sorties de plats.
                </div>
              </div>
            </template>
          </v-radio>
          <v-radio value="AUTOMATIQUE" class="retrait-option mt-2">
            <template #label>
              <div>
                <strong>Automatique</strong>
                <div class="text-body-2 text-medium-emphasis">
                  Les plats périmés sont retirés du stock sans intervention, avec la même écriture qu'un retrait manuel
                  (perte au coût moyen), puis une notification prévient. Les écritures sont passées au nom de
                  l'administrateur qui active ce mode.
                </div>
              </div>
            </template>
          </v-radio>
        </v-radio-group>

        <div class="mt-4 text-body-2 text-medium-emphasis">
          <v-icon icon="mdi-clock-outline" size="16" class="mr-1" />
          Vérification toutes les 30 minutes. Une portion est périmée dès le lendemain de sa date d'expiration
          (à 02 h, heure de Lubumbashi).
        </div>

        <div v-if="modeEnregistre === 'AUTOMATIQUE' && retraitPar" class="mt-2 text-body-2 retrait-auteur">
          <v-icon icon="mdi-account-check-outline" size="16" class="mr-1" />
          Retrait automatique activé par <strong>{{ retraitPar }}</strong><span v-if="retraitLeTexte"> le {{ retraitLeTexte }}</span> :
          les retraits sont passés à son nom.
        </div>

        <v-alert v-if="retraitSuspendu" type="warning" variant="tonal" rounded="lg" class="mt-4 retrait-suspendu" density="comfortable">
          <template v-if="retraitPar">
            L'administrateur qui a activé le retrait automatique ({{ retraitPar }}) n'est plus en mesure d'en répondre
            (compte désactivé, ou qui n'est plus administrateur).
          </template>
          <template v-else>L'administrateur qui a activé le retrait automatique n'existe plus.</template>
          Rien n'est retiré automatiquement : la notification prévient comme en mode manuel. Un administrateur doit
          enregistrer à nouveau le mode automatique pour en reprendre la responsabilité.
        </v-alert>

        <v-alert
          v-if="estAdmin && modeRetrait === 'AUTOMATIQUE' && modeEnregistre !== 'AUTOMATIQUE' && portionsPerimees > 0"
          type="info" variant="tonal" rounded="lg" class="mt-4 retrait-deja-perimes" density="comfortable"
        >
          {{ new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(portionsPerimees) }} portion(s) périmée(s)
          sont déjà en stock : la prochaine vérification, dans 30 minutes au plus, les retirera et passera
          l'écriture à votre nom.
        </v-alert>
      </v-card-text>

      <v-card-actions v-if="estAdmin" class="px-4 pb-4">
        <v-spacer />
        <v-btn
          color="primary"
          variant="flat"
          rounded="lg"
          prepend-icon="mdi-content-save-outline"
          class="retrait-enregistrer"
          :loading="savingRetrait"
          :disabled="loading"
          @click="enregistrerRetrait"
        >
          Enregistrer
        </v-btn>
      </v-card-actions>
    </v-card>

    <v-card rounded="lg" border flat class="mb-4">
      <v-card-item>
        <v-card-title class="text-subtitle-1">Sortie des lots de stock</v-card-title>
        <v-card-subtitle class="text-wrap">
          Comment une vente, une casse ou une péremption se répartit sur les lots (date d'achat, fournisseur, prix)
          des pages de stock. Fixé, non modifiable ; suivi de gestion uniquement, la comptabilité reste au coût moyen pondéré.
        </v-card-subtitle>
      </v-card-item>
      <v-card-text class="text-body-2">
        <p class="mb-2">
          <v-icon icon="mdi-bottle-wine-outline" size="16" class="mr-1" /><strong>Boissons : FIFO</strong> —
          premier entré, premier sorti : la livraison la plus ancienne part d'abord, les lots restent en bouteilles entières.
        </p>
        <p class="mb-0">
          <v-icon icon="mdi-sack" size="16" class="mr-1" /><strong>Provisions de cuisine : CMP</strong> —
          coût moyen pondéré : chaque sortie prélève sur tous les lots au prorata, les quantités par lot peuvent donc être décimales.
        </p>
      </v-card-text>
    </v-card>

    <v-alert v-if="!canWrite" type="info" variant="tonal" rounded="lg" density="comfortable">
      Consultation seule : seuls le responsable restaurant et l'administrateur
      peuvent modifier cette préférence.
    </v-alert>
  </div>
</template>
