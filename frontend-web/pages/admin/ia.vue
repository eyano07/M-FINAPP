<script setup lang="ts">
/**
 * Administration › Intelligence artificielle : clés OpenAI et Anthropic (jamais relues : seule leur fin est
 * affichée), choix du modèle dans la liste du fournisseur une fois la clé enregistrée, test, état du crédit.
 * Règle : Claude pour les analyses, OpenAI pour le reste ; crédit Claude épuisé, OpenAI prend tout.
 */
definePageMeta({ roles: ['ADMIN'] })
useHead({ title: 'Intelligence artificielle' })

interface Fournisseur {
  id: 'openai' | 'anthropic'
  libelle: string
  role: string
  cleConfiguree: boolean
  cleFin: string | null
  viaEnvironnement: boolean
  modele: string
  modeleParDefaut: string
  etat: 'NON_CONFIGURE' | 'ACTIF' | 'CREDIT_EPUISE' | 'CLE_REFUSEE'
  depuis: string | null
}
interface Modeles { modeleActuel: string, modeleParDefaut: string, modeles: string[] }

const api = useApi()
const iaDesactivee = ref(false)
const fournisseurs = ref<Fournisseur[]>([])
const chargement = ref(true)
const erreur = ref('')
const succes = ref('')

const saisie = reactive<Record<string, { cle: string, voir: boolean, modele: string | null }>>({
  openai: { cle: '', voir: false, modele: null },
  anthropic: { cle: '', voir: false, modele: null },
})
const modeles = reactive<Record<string, string[]>>({ openai: [], anthropic: [] })
const action = reactive<Record<string, string>>({ openai: '', anthropic: '' })
const resultatTest = reactive<Record<string, { ok: boolean, message: string } | null>>({ openai: null, anthropic: null })

async function charger() {
  chargement.value = true
  try {
    const r = await api<{ iaDesactivee: boolean, fournisseurs: Fournisseur[] }>('/admin/ia')
    iaDesactivee.value = r.iaDesactivee
    fournisseurs.value = r.fournisseurs
    for (const f of r.fournisseurs) {
      saisie[f.id].modele = f.modele
      if (f.cleConfiguree) await chargerModeles(f.id, false)
    }
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les paramètres de l’IA.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

async function chargerModeles(id: string, signaler = true) {
  action[id] = 'modeles'
  try {
    const r = await api<Modeles>(`/admin/ia/${id}/modeles`)
    modeles[id] = r.modeles
  } catch (e) {
    modeles[id] = []
    if (signaler) erreur.value = messageErreurApi(e, 'Impossible de lire la liste des modèles.')
  } finally {
    action[id] = ''
  }
}

async function enregistrer(f: Fournisseur) {
  const s = saisie[f.id]
  erreur.value = ''
  succes.value = ''
  resultatTest[f.id] = null
  const corps: { cle?: string, modele?: string } = {}
  if (s.cle.trim()) corps.cle = s.cle.trim()
  if (s.modele && s.modele !== f.modele) corps.modele = s.modele
  if (!corps.cle && !corps.modele) {
    erreur.value = 'Saisissez une clé, ou choisissez un autre modèle.'
    return
  }
  action[f.id] = 'enregistrer'
  try {
    const r = await api<{ iaDesactivee: boolean, fournisseurs: Fournisseur[] }>(`/admin/ia/${f.id}`, { method: 'PUT', body: corps })
    fournisseurs.value = r.fournisseurs
    s.cle = ''
    s.voir = false
    const maj = r.fournisseurs.find(x => x.id === f.id)!
    s.modele = maj.modele
    succes.value = corps.cle
      ? `Clé ${f.libelle} vérifiée et enregistrée. Choisissez maintenant le modèle à utiliser.`
      : `Modèle ${f.libelle} enregistré : ${maj.modele}.`
    await chargerModeles(f.id)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    action[f.id] = ''
  }
}

async function retirer(f: Fournisseur) {
  if (!confirm(`Retirer la clé ${f.libelle} ? ${f.id === 'anthropic' ? 'Les analyses passeront sur OpenAI.' : 'Les fonctions IA courantes n’auront plus de fournisseur (hors clé Anthropic).'}`)) return
  action[f.id] = 'retirer'
  erreur.value = ''
  try {
    const r = await api<{ iaDesactivee: boolean, fournisseurs: Fournisseur[] }>(`/admin/ia/${f.id}/cle`, { method: 'DELETE' })
    fournisseurs.value = r.fournisseurs
    modeles[f.id] = []
    resultatTest[f.id] = null
    succes.value = `Clé ${f.libelle} retirée.`
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de retirer la clé.')
  } finally {
    action[f.id] = ''
  }
}

async function tester(f: Fournisseur) {
  action[f.id] = 'tester'
  erreur.value = ''
  resultatTest[f.id] = null
  try {
    resultatTest[f.id] = await api<{ ok: boolean, message: string }>(`/admin/ia/${f.id}/tester`, { method: 'POST' })
    const r = await api<{ iaDesactivee: boolean, fournisseurs: Fournisseur[] }>('/admin/ia')
    fournisseurs.value = r.fournisseurs
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Le test n’a pas pu être lancé.')
  } finally {
    action[f.id] = ''
  }
}

const ETAT = {
  ACTIF: { libelle: 'Actif', couleur: 'success', icone: 'mdi-check-circle' },
  NON_CONFIGURE: { libelle: 'Non configuré', couleur: 'grey', icone: 'mdi-circle-outline' },
  CREDIT_EPUISE: { libelle: 'Crédit épuisé', couleur: 'warning', icone: 'mdi-alert-circle' },
  CLE_REFUSEE: { libelle: 'Clé refusée', couleur: 'error', icone: 'mdi-key-alert' },
} as const
const fmtDate = (d?: string | null) => (d ? new Date(d).toLocaleString('fr-FR') : '')
const anthropic = computed(() => fournisseurs.value.find(f => f.id === 'anthropic'))
</script>

<template>
  <div class="ia-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Intelligence artificielle</h1>
        <p class="page-sub">Clés des fournisseurs et modèles utilisés par l'application</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert v-if="iaDesactivee" type="warning" variant="tonal" class="mb-4">
      L'IA est désactivée au niveau du serveur (<code>IA_ENABLED=false</code>) : les clés enregistrées ici restent sans effet tant qu'elle n'est pas réactivée.
    </v-alert>
    <v-alert v-if="anthropic?.etat === 'CREDIT_EPUISE'" type="warning" variant="tonal" class="mb-4" icon="mdi-alert-circle">
      Le crédit Anthropic est épuisé depuis le {{ fmtDate(anthropic.depuis) }} : toutes les analyses utilisent OpenAI en attendant.
      Rechargez le crédit chez Anthropic puis cliquez sur « Tester » ; une nouvelle tentative est aussi faite automatiquement chaque heure.
    </v-alert>
    <v-alert v-if="anthropic?.etat === 'CLE_REFUSEE'" type="error" variant="tonal" class="mb-4" icon="mdi-key-alert">
      La clé Anthropic est refusée depuis le {{ fmtDate(anthropic.depuis) }} : les analyses utilisent OpenAI. Saisissez une clé valide.
    </v-alert>

    <v-alert type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-information-outline">
      <strong>Claude</strong> réalise les analyses (états financiers, restaurant, proposition de budget) ;
      <strong>OpenAI</strong> assure toutes les autres fonctions IA. Si le crédit Claude est épuisé, OpenAI prend tout en charge, sans interruption.
    </v-alert>

    <v-skeleton-loader v-if="chargement" type="card, card" />

    <div v-else class="ia-grille">
      <v-card v-for="f in fournisseurs" :key="f.id" class="classroom-card pa-5">
        <div class="ia-tete">
          <div>
            <h2 class="ia-titre">{{ f.libelle }}</h2>
            <p class="ia-role">{{ f.role }}</p>
          </div>
          <v-chip :color="ETAT[f.etat].couleur" variant="tonal" size="small" :prepend-icon="ETAT[f.etat].icone">{{ ETAT[f.etat].libelle }}</v-chip>
        </div>

        <!-- Clé -->
        <div class="ia-bloc">
          <label class="ia-label" :for="`cle-${f.id}`">Clé d'API</label>
          <p v-if="f.cleConfiguree" class="ia-cle">
            <v-icon icon="mdi-key-variant" size="16" /> ••••••••{{ f.cleFin }}
            <span v-if="f.viaEnvironnement" class="ia-env">fournie par le serveur (variable d'environnement)</span>
          </p>
          <v-text-field :id="`cle-${f.id}`" v-model="saisie[f.id].cle" :type="saisie[f.id].voir ? 'text' : 'password'" autocomplete="off"
            :placeholder="f.cleConfiguree ? 'Saisir une nouvelle clé pour la remplacer' : (f.id === 'openai' ? 'sk-...' : 'sk-ant-...')"
            variant="outlined" density="comfortable" hide-details
            :append-inner-icon="saisie[f.id].voir ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="saisie[f.id].voir = !saisie[f.id].voir" />
          <p class="ia-aide">La clé est vérifiée auprès du fournisseur, puis chiffrée. Elle n'est jamais affichée de nouveau.</p>
        </div>

        <!-- Modèle -->
        <div class="ia-bloc">
          <label class="ia-label">Modèle</label>
          <v-select v-if="f.cleConfiguree" v-model="saisie[f.id].modele" :items="modeles[f.id]" :loading="action[f.id] === 'modeles'"
            variant="outlined" density="comfortable" hide-details :no-data-text="'Aucun modèle lu : cliquez sur « Actualiser la liste »'" />
          <p v-else class="ia-aide">Enregistrez d'abord la clé : la liste des modèles disponibles s'affichera ensuite.</p>
          <p class="ia-aide">Par défaut : <code>{{ f.modeleParDefaut }}</code>
            <v-btn v-if="f.cleConfiguree && saisie[f.id].modele !== f.modeleParDefaut" variant="text" size="x-small" class="ml-1"
              @click="saisie[f.id].modele = f.modeleParDefaut">Rétablir</v-btn>
            <v-btn v-if="f.cleConfiguree" variant="text" size="x-small" prepend-icon="mdi-refresh" :loading="action[f.id] === 'modeles'" @click="chargerModeles(f.id)">Actualiser la liste</v-btn>
          </p>
        </div>

        <v-alert v-if="resultatTest[f.id]" :type="resultatTest[f.id]!.ok ? 'success' : 'error'" variant="tonal" density="compact" class="mb-3">
          {{ resultatTest[f.id]!.message }}
        </v-alert>

        <div class="ia-actions">
          <v-btn color="primary" prepend-icon="mdi-content-save-outline" :loading="action[f.id] === 'enregistrer'" @click="enregistrer(f)">Enregistrer</v-btn>
          <v-btn v-if="f.cleConfiguree" variant="tonal" prepend-icon="mdi-lightning-bolt-outline" :loading="action[f.id] === 'tester'" @click="tester(f)">Tester</v-btn>
          <v-spacer />
          <v-btn v-if="f.cleConfiguree && !f.viaEnvironnement" variant="text" color="error" prepend-icon="mdi-delete-outline" :loading="action[f.id] === 'retirer'" @click="retirer(f)">Retirer la clé</v-btn>
        </div>
      </v-card>
    </div>
  </div>
</template>

<style scoped>
.ia-page { max-width: 1100px; }
.ia-grille { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.ia-tete { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.ia-titre { font-size: 1.05rem; font-weight: 700; margin: 0; }
.ia-role { font-size: 0.8rem; color: #4b5563; margin: 4px 0 0; }
.ia-bloc { margin-bottom: 16px; }
.ia-label { display: block; font-size: 0.8rem; font-weight: 700; margin-bottom: 6px; }
.ia-cle { display: flex; align-items: center; gap: 6px; font-family: ui-monospace, monospace; font-size: 0.85rem; margin: 0 0 8px; flex-wrap: wrap; }
.ia-env { font-family: inherit; font-size: 0.74rem; color: #4b5563; }
.ia-aide { font-size: 0.75rem; color: #4b5563; margin: 6px 0 0; }
.ia-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
@media (max-width: 900px) { .ia-grille { grid-template-columns: 1fr; } }
</style>
