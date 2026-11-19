<script setup lang="ts">
/**
 * Administration › Hôtellerie (V152) : relie M-FINAPP à l'application Hôtellerie (PMS), qui tourne dans ses propres
 * conteneurs. On saisit son adresse IP et son port, on génère la clé d'appairage (affichée une seule fois, à coller dans
 * l'écran de premier lancement de l'hôtellerie), puis on teste la connexion.
 */
definePageMeta({ roles: ['ADMIN'], module: 'HOTELLERIE' })
useHead({ title: 'Hôtellerie' })

interface Etat {
  adresse: string | null
  port: number | null
  url: string | null
  cleDefinie: boolean
  cleFin: string | null
  cleCreeLe: string | null
  dernierContact: string | null
}
interface Test { joignable: boolean, message: string, version: string | null }

const api = useApi()
const dialogues = useDialogues()
const etat = ref<Etat | null>(null)
const chargement = ref(true)
const erreur = ref('')
const succes = ref('')
const action = ref<'' | 'enregistrer' | 'cle' | 'tester'>('')
const cleAffichee = ref('')
const copiee = ref(false)
const test = ref<Test | null>(null)
const form = reactive({ adresse: '', port: 8090 as number | null })

function appliquer(e: Etat) {
  etat.value = e
  form.adresse = e.adresse ?? ''
  form.port = e.port ?? 8090
}

async function charger() {
  chargement.value = true
  erreur.value = ''
  try {
    appliquer(await api<Etat>('/hotellerie/integration'))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de lire la connexion à l\'hôtellerie.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  action.value = 'enregistrer'
  erreur.value = ''
  succes.value = ''
  try {
    appliquer(await api<Etat>('/hotellerie/integration', { method: 'PUT', body: { adresse: form.adresse, port: Number(form.port) } }))
    succes.value = 'Adresse enregistrée.'
  } catch (e) {
    erreur.value = messageErreurApi(e, "L'adresse n'a pas pu être enregistrée.")
  } finally {
    action.value = ''
  }
}

async function genererCle() {
  if (etat.value?.cleDefinie && !(await dialogues.confirmer({
    titre: 'Remplacer la clé d\'appairage ?',
    message: "L'hôtellerie cessera de joindre M-FINAPP tant que la nouvelle clé n'y aura pas été collée.",
    bouton: 'Remplacer la clé',
    nature: 'attention',
  }))) return
  action.value = 'cle'
  erreur.value = ''
  try {
    const r = await api<{ cle: string }>('/hotellerie/integration/cle', { method: 'POST' })
    cleAffichee.value = r.cle
    copiee.value = false
    await charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, "La clé n'a pas pu être générée.")
  } finally {
    action.value = ''
  }
}

async function copier() {
  try {
    await navigator.clipboard.writeText(cleAffichee.value)
    copiee.value = true
  } catch {
    copiee.value = false
  }
}

async function tester() {
  action.value = 'tester'
  test.value = null
  try {
    test.value = await api<Test>('/hotellerie/integration/test', { method: 'POST' })
    await charger()
  } catch (e) {
    test.value = { joignable: false, message: messageErreurApi(e, 'Test impossible.'), version: null }
  } finally {
    action.value = ''
  }
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' }) : '—')
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Hôtellerie</h1>
        <p class="page-sub">Connexion à M-FINAPP Hôtellerie — hôtel, motel et appartements</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>

    <v-row>
      <v-col cols="12" md="7">
        <v-card class="classroom-card pa-6 mb-4" :loading="chargement">
          <div class="kpi-label mb-1">1. Adresse de l'hôtellerie</div>
          <p class="text-body-2 text-medium-emphasis mb-4">
            L'application Hôtellerie tourne dans ses propres conteneurs. Saisissez l'adresse IP (ou le nom) de son serveur
            et son port, par exemple <code>192.0.2.10</code> et <code>8090</code>.
          </p>
          <v-row dense>
            <v-col cols="12" sm="8">
              <v-text-field v-model="form.adresse" label="Adresse IP ou nom du serveur" placeholder="192.0.2.10"
                variant="outlined" density="comfortable" prepend-inner-icon="mdi-ip-network-outline" />
            </v-col>
            <v-col cols="12" sm="4">
              <v-text-field v-model.number="form.port" type="number" label="Port" placeholder="8090" variant="outlined"
                density="comfortable" />
            </v-col>
          </v-row>
          <v-btn color="primary" rounded="lg" prepend-icon="mdi-content-save-outline" :loading="action === 'enregistrer'"
            :disabled="!form.adresse || !form.port" @click="enregistrer">
            Enregistrer
          </v-btn>
        </v-card>

        <v-card class="classroom-card pa-6 mb-4">
          <div class="kpi-label mb-1">2. Clé d'appairage</div>
          <p class="text-body-2 text-medium-emphasis mb-4">
            Elle prouve à M-FINAPP que les appels viennent bien de votre hôtellerie. Générez-la, puis collez-la dans l'écran
            de premier lancement de l'hôtellerie. Elle n'est affichée qu'une fois.
          </p>
          <div v-if="etat?.cleDefinie && !cleAffichee" class="text-body-2 mb-4">
            <v-icon icon="mdi-key-variant" size="18" class="mr-1" />Clé en place, se terminant par
            <strong>…{{ etat.cleFin }}</strong>, créée le {{ fmt(etat.cleCreeLe) }}.
          </div>
          <v-alert v-if="cleAffichee" type="warning" variant="tonal" class="mb-4">
            <div class="mb-2">Copiez cette clé maintenant : elle ne sera plus affichée.</div>
            <code class="cle-appairage">{{ cleAffichee }}</code>
            <div class="mt-3">
              <v-btn size="small" variant="tonal" :prepend-icon="copiee ? 'mdi-check' : 'mdi-content-copy'" @click="copier">
                {{ copiee ? 'Copiée' : 'Copier' }}
              </v-btn>
            </div>
          </v-alert>
          <v-btn color="primary" variant="tonal" rounded="lg" prepend-icon="mdi-key-plus" :loading="action === 'cle'"
            @click="genererCle">
            {{ etat?.cleDefinie ? 'Générer une nouvelle clé' : 'Générer la clé' }}
          </v-btn>
        </v-card>
      </v-col>

      <v-col cols="12" md="5">
        <v-card class="classroom-card pa-6 mb-4">
          <div class="kpi-label mb-1">3. Connexion</div>
          <div class="text-body-2 mb-1">Adresse : <strong>{{ etat?.url || '—' }}</strong></div>
          <div class="text-body-2 mb-4">Dernier appel reçu de l'hôtellerie : <strong>{{ fmt(etat?.dernierContact ?? null) }}</strong></div>
          <v-btn color="primary" rounded="lg" prepend-icon="mdi-lan-connect" :loading="action === 'tester'" :disabled="!etat?.url"
            @click="tester">
            Tester la connexion
          </v-btn>
          <v-alert v-if="test" :type="test.joignable ? 'success' : 'error'" variant="tonal" class="mt-4">
            {{ test.message }}<template v-if="test.version"> (version {{ test.version }})</template>
          </v-alert>
        </v-card>
        <v-card class="classroom-card pa-6">
          <div class="kpi-label mb-2">Ce que fait M-FINAPP pour l'hôtellerie</div>
          <ul class="text-body-2 liste">
            <li>Comptes, rôles et connexion : réceptionniste, gouvernante, technicien, directeur de l'hébergement.</li>
            <li>Folios encaissés par la réception : vente, TVA, caisse, banque ou mobile money, analytique 986.</li>
            <li>Acomptes (4191) et cautions (1651), imputés au départ.</li>
            <li>Stock des extras (minibar…), notes de frais, paie et comptabilité restent dans M-FINAPP.</li>
          </ul>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<style scoped>
.cle-appairage {
  display: block;
  word-break: break-all;
  font-size: 0.85rem;
  padding: 8px 10px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-primary) 8%, transparent);
}
.liste { padding-left: 18px; line-height: 1.6; }
</style>
