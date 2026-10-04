<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Paramètres du module Restaurant.
 *
 * - La devise d'affichage des montants (carte, stock, tableaux de bord).
 *   Elle n'affecte que la présentation — le grand livre reste en USD et
 *   Article.prixVente en FC ; la conversion est appliquée à la lecture par
 *   le store (voir fmtMontant/fmtMontantDepuisFC).
 * - Le nombre de décimales des montants et des quantités affichés.
 * - Pour information seulement, la répartition des sorties sur les lots de
 *   stock, désormais fixée (voir LotStockService.methode côté serveur) :
 *   FIFO pour les boissons, CMP pour les provisions. Un suivi de gestion,
 *   sans aucun effet sur la comptabilité, qui reste au coût moyen pondéré.
 *
 * L'enregistrement est reserve a RESP_RESTAURANT/ADMIN cote serveur
 * (ParametresRestaurantService) : les autres roles autorises sur le module
 * consultent la valeur sans pouvoir la modifier.
 */
const api = useApi()
const auth = useAuthStore()
const parametres = useRestaurantParametresStore()

const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')
const devise = ref<'USD' | 'CDF'>('CDF')
/** 'AUTO' = FC sans décimale, USD à 2 (null côté serveur) ; sinon le nombre de décimales. */
const decimalesMontants = ref<'AUTO' | '0' | '1' | '2' | '3' | '4'>('AUTO')
const decimalesQuantites = ref<'0' | '1' | '2' | '3'>('2')

const canWrite = computed(() => auth.hasAnyRole(['RESP_RESTAURANT', 'ADMIN']))

// Aperçu du réglage en cours de saisie, avant enregistrement : un coût moyen
// de 1,469667 $ (3 233,27 FC au taux de 2 200) et une quantité de 0,6667.
const APERCU_USD = 1.469667
const apercuMontant = computed(() => {
  const d = decimalesMontants.value === 'AUTO' ? (devise.value === 'CDF' ? 0 : 2) : Number(decimalesMontants.value)
  if (devise.value === 'CDF') {
    const taux = parametres.tauxChange > 0 ? parametres.tauxChange : 2200
    return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d }).format(APERCU_USD * taux) + ' FC'
  }
  return new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'USD', minimumFractionDigits: d, maximumFractionDigits: d })
    .format(APERCU_USD)
})
const apercuQuantite = computed(() =>
  new Intl.NumberFormat('fr-FR', { maximumFractionDigits: Number(decimalesQuantites.value) }).format(0.6667))

/**
 * Sans taux du jour, l'affichage en CDF ne peut rien convertir : le store
 * renvoie « — » partout. On le signale avant l'enregistrement plutot que de
 * laisser l'utilisateur decouvrir des montants vides sur chaque ecran.
 */
const tauxManquant = computed(() => devise.value === 'CDF' && parametres.tauxChange <= 0)

interface ParametresRestaurant {
  deviseAffichage: 'USD' | 'CDF'
  decimalesMontants: number | null
  decimalesQuantites: number
}

function appliquer(p: ParametresRestaurant) {
  devise.value = p.deviseAffichage
  decimalesMontants.value = p.decimalesMontants == null ? 'AUTO' : String(p.decimalesMontants) as typeof decimalesMontants.value
  decimalesQuantites.value = String(p.decimalesQuantites ?? 2) as typeof decimalesQuantites.value
}

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    appliquer(await api<ParametresRestaurant>('/restaurant/parametres'))
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
      body: {
        deviseAffichage: devise.value,
        decimalesMontants: decimalesMontants.value === 'AUTO' ? null : Number(decimalesMontants.value),
        decimalesQuantites: Number(decimalesQuantites.value),
      },
    })
    appliquer(p)
    // La preference est mise en cache par le store des le premier ecran du
    // module : sans rechargement force, les autres pages continueraient
    // d'afficher les montants dans l'ancienne devise jusqu'a la reconnexion.
    await parametres.charger(true)
    succes.value = 'Préférences enregistrées.'
  } catch (e: any) {
    erreur.value = e?.data?.message || "Impossible d'enregistrer les préférences."
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([charger(), parametres.charger()])
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
          Aucun taux de change n'est enregistré : en affichage FC, les montants convertis
          depuis l'USD resteront vides. Renseignez le taux du jour avant de basculer.
        </v-alert>
      </v-card-text>
    </v-card>

    <v-card rounded="lg" border flat :loading="loading" class="mb-4">
      <v-card-item>
        <v-card-title class="text-subtitle-1">Arrondi à l'affichage</v-card-title>
        <v-card-subtitle class="text-wrap">
          Combien de chiffres après la virgule afficher, sur tous les écrans du restaurant. Affichage seulement :
          les montants restent enregistrés et comptabilisés avec leur précision, quel que soit ce choix.
        </v-card-subtitle>
      </v-card-item>

      <v-card-text>
        <p class="text-body-2 font-weight-medium mb-2">Montants (prix, coûts, valeurs)</p>
        <v-btn-toggle
          v-model="decimalesMontants"
          mandatory
          density="comfortable"
          variant="outlined"
          rounded="lg"
          :disabled="!canWrite || loading"
        >
          <v-btn value="AUTO">Automatique</v-btn>
          <v-btn value="0">0</v-btn>
          <v-btn value="1">1</v-btn>
          <v-btn value="2">2</v-btn>
          <v-btn value="3">3</v-btn>
          <v-btn value="4">4</v-btn>
        </v-btn-toggle>
        <div class="mt-2 text-body-2 text-medium-emphasis">
          <template v-if="decimalesMontants === 'AUTO'">Francs congolais sans décimale, dollars à 2 décimales. </template>
          Exemple : un coût moyen s'affichera <strong>{{ apercuMontant }}</strong>.
        </div>

        <p class="text-body-2 font-weight-medium mt-5 mb-2">Quantités (bouteilles, kg, portions...)</p>
        <v-btn-toggle
          v-model="decimalesQuantites"
          mandatory
          density="comfortable"
          variant="outlined"
          rounded="lg"
          :disabled="!canWrite || loading"
        >
          <v-btn value="0">0</v-btn>
          <v-btn value="1">1</v-btn>
          <v-btn value="2">2</v-btn>
          <v-btn value="3">3</v-btn>
        </v-btn-toggle>
        <div class="mt-2 text-body-2 text-medium-emphasis">
          Au plus, sans zéros inutiles : 20 bouteilles restent « 20 ».
          Exemple : 0,6667 kg s'affichera <strong>{{ apercuQuantite }}</strong>.
        </div>
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
      peuvent modifier ces préférences.
    </v-alert>
  </div>
</template>
