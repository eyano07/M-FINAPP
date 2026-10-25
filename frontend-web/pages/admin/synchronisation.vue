<script setup lang="ts">
/**
 * Administration › Serveur local et synchronisation (V132-V133).
 * Serveur en ligne : génère la clé d'appairage ; devenu copie d'un serveur local, peut reprendre la main.
 * Serveur local : adresse du serveur en ligne + clé, test, activation (les données viennent du serveur en ligne),
 * synchronisation manuelle, resynchronisation complète, désactivation.
 */
definePageMeta({ roles: ['ADMIN'] })

import { useInstanceStore } from '~/stores/instance'

interface EtatAdmin {
  mode: 'EN_LIGNE' | 'LOCAL' | 'MIROIR' | 'LOCAL_ARRETE'
  adresseDistante: string | null
  cleFin: string | null
  appairageId: string | null
  cleCreeeLe: string | null
  serveurAssocie: string | null
  appaireLe: string | null
  dernierContact: string | null
  derniereSynchro: string | null
  derniereErreur: string | null
  derniereErreurLe: string | null
  versionSchema: number
  enAttente: number
  modifiePar: string | null
  dateMaj: string | null
}
interface Progression {
  enCours: boolean
  etape: string | null
  pourcentage: number
  erreur: string | null
  termine: boolean
  fin: string | null
}
interface Vue { etat: EtatAdmin, activation: Progression, schemaPret: boolean, schemaProbleme: string | null }
interface Test { ok: boolean, message: string, versionDistante: number | null, versionLocale: number, modeDistant: string | null, ecartHorlogeMs: number | null }

const api = useApi()
const instanceStore = useInstanceStore()

const vue = ref<Vue | null>(null)
const chargement = ref(true)
const erreur = ref('')
const succes = ref('')
const occupe = ref('')

const role = ref<'EN_LIGNE' | 'LOCAL'>('EN_LIGNE')
const form = reactive({ adresse: '', cle: '' })
const cleGeneree = ref('')
const test = ref<Test | null>(null)
const bilan = ref('')
const confirmation = ref<null | 'activer' | 'desactiver' | 'reprendre' | 'resynchroniser'>(null)

const etat = computed(() => vue.value?.etat)
const activation = computed(() => vue.value?.activation)
// Le bilan d'une activation reste en mémoire jusqu'au redémarrage du serveur : affiché pendant une heure seulement.
const activationVisible = computed(() => {
  const a = activation.value
  if (!a) return false
  if (a.enCours || a.erreur) return true
  return a.termine && !!a.fin && Date.now() - Date.parse(a.fin) < 3_600_000
})

let suivi: ReturnType<typeof setInterval> | null = null

async function charger() {
  try {
    vue.value = await api<Vue>('/admin/instance')
    const e = vue.value.etat
    if ((e.mode === 'EN_LIGNE' || e.mode === 'LOCAL_ARRETE') && e.adresseDistante && !form.adresse) {
      form.adresse = e.adresseDistante
      role.value = 'LOCAL'
    }
    if (vue.value.activation.enCours && !suivi) {
      suivi = setInterval(suivre, 2000)
    }
  } catch (e) {
    erreur.value = messageErreurApi(e, "Impossible de lire l'état du serveur.")
  } finally {
    chargement.value = false
  }
}

async function suivre() {
  try {
    vue.value = await api<Vue>('/admin/instance')
    if (!vue.value.activation.enCours && suivi) {
      clearInterval(suivi)
      suivi = null
      void instanceStore.charger()
    }
  } catch {
    // L'activation remplace les comptes : la session peut tomber, la page le dira.
  }
}

onMounted(() => {
  oublierActivationModeLocal()
  void charger()
})
onBeforeUnmount(() => { if (suivi) clearInterval(suivi) })

async function agir<T>(cle: string, appel: () => Promise<T>, apres?: (r: T) => void) {
  erreur.value = ''
  succes.value = ''
  occupe.value = cle
  try {
    const r = await appel()
    apres?.(r)
    await charger()
    void instanceStore.charger()
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Opération impossible.')
  } finally {
    occupe.value = ''
  }
}

const genererCle = () => agir('cle', () => api<{ cle: string }>('/admin/instance/cle-appairage', { method: 'POST' }),
  (r) => { cleGeneree.value = r.cle })

async function copierCle() {
  try {
    await navigator.clipboard.writeText(cleGeneree.value)
    succes.value = 'Clé copiée.'
  } catch {
    succes.value = 'Sélectionnez la clé et copiez-la (Ctrl+C).'
  }
}

function enregistrerDistant() {
  const avecCle = !!form.cle.trim()
  return agir('distant', () => api('/admin/instance/serveur-distant', {
    method: 'PUT', body: { adresse: form.adresse, cle: form.cle },
  }), () => {
    form.cle = ''
    test.value = null // le test précédent portait sur l'ancienne adresse ou l'ancienne clé
    succes.value = avecCle ? 'Adresse et clé enregistrées : testez la connexion.' : 'Adresse enregistrée (clé conservée) : testez la connexion.'
  })
}

const tester = () => agir('test', () => api<Test>('/admin/instance/tester', { method: 'POST' }), (r) => { test.value = r })

// Les comptes d'ici sont remplacés par ceux du serveur en ligne : la session tombe en cours de route (useApi renvoie
// à la connexion), la page de connexion l'explique et ramène ici.
const activer = () => agir('activer', () => {
  noterActivationModeLocal()
  return api<Progression>('/admin/instance/activer-mode-local', { method: 'POST' })
}, () => {
  confirmation.value = null
  if (!suivi) suivi = setInterval(suivre, 2000)
})

const synchroniser = () => agir('synchro', () => api<{ ok: boolean, message: string }>('/admin/instance/synchroniser', { method: 'POST' }),
  (r) => { bilan.value = r.message; if (!r.ok) erreur.value = r.message })

const resynchroniser = () => agir('resync', () => api<{ message: string }>('/admin/instance/resynchroniser', { method: 'POST' }),
  (r) => { confirmation.value = null; succes.value = r.message })

const desactiver = () => agir('desactiver', () => api<{ message: string }>('/admin/instance/desactiver-mode-local', { method: 'POST' }),
  (r) => { confirmation.value = null; succes.value = r.message })

const reprendre = () => agir('reprendre', () => api<{ message: string }>('/admin/instance/reprendre-la-main', { method: 'POST' }),
  (r) => { confirmation.value = null; succes.value = r.message })

// Ancien serveur local (mode désactivé, ou le serveur en ligne a repris la main) : il se relie avec une nouvelle clé.
const ancienLocal = computed(() => etat.value?.mode === 'LOCAL_ARRETE')
// Clé saisie ici avec une adresse (une clé générée sur ce serveur quand il était « en ligne » ne compte pas, ni celle d'un
// appairage terminé).
const cleEnregistree = computed(() => etat.value?.mode === 'EN_LIGNE' && !!etat.value?.cleFin && !!etat.value?.adresseDistante)

const adresseNonChiffree = computed(() => /^\s*http:\/\//i.test(form.adresse) || /^\s*\d{1,3}(\.\d{1,3}){3}/.test(form.adresse))

const MODES: Record<string, { libelle: string, icone: string, couleur: string, texte: string }> = {
  EN_LIGNE: { libelle: 'Serveur principal (fonctionnement habituel)', icone: 'mdi-server', couleur: '#16a34a',
    texte: "Toutes les saisies se font ici. Pour travailler sans dépendre d'Internet, installez un serveur sur une machine du réseau de l'entreprise et activez-y le mode local." },
  LOCAL: { libelle: "Serveur local principal (réseau de l'entreprise)", icone: 'mdi-server-network', couleur: '#2563eb',
    texte: 'Toutes les saisies se font ici, même sans Internet. Dès que la connexion est là, tout est recopié vers le serveur en ligne, et les actions faites en ligne sont appliquées ici.' },
  MIROIR: { libelle: "Copie en ligne d'un serveur local", icone: 'mdi-cloud-sync-outline', couleur: '#7c3aed',
    texte: "Les données viennent du serveur local. Ici : consultation, création et validation des notes de frais, activation des comptes ; tout est appliqué au serveur local à la synchronisation suivante." },
  LOCAL_ARRETE: { libelle: 'Ancien serveur local (lecture seule)', icone: 'mdi-server-off', couleur: '#d97706',
    texte: "Ce serveur n'est plus le serveur principal : le serveur en ligne a repris la main ou le mode local a été désactivé." },
}
const modeMeta = computed(() => MODES[etat.value?.mode ?? 'EN_LIGNE'])

const date = (iso: string | null | undefined) => iso
  ? new Date(iso).toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
  : '—'
</script>

<template>
  <div class="sync-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Serveur local et synchronisation</h1>
        <p class="page-sub">Travailler sur le réseau de l'entreprise même sans Internet, avec une copie en ligne à jour</p>
      </div>
      <v-btn variant="outlined" color="primary" rounded="lg" prepend-icon="mdi-refresh" :loading="chargement" @click="charger">
        Actualiser
      </v-btn>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>

    <v-skeleton-loader v-if="chargement && !vue" type="card, card" />

    <template v-else-if="etat">
      <!-- Mode actuel -->
      <v-card class="classroom-card pa-5 mb-4">
        <div class="sync-mode">
          <span class="sync-mode__icone" :style="{ background: modeMeta.couleur + '1a', color: modeMeta.couleur }">
            <v-icon :icon="modeMeta.icone" size="26" />
          </span>
          <div class="sync-mode__texte">
            <p class="sync-mode__libelle">{{ modeMeta.libelle }}</p>
            <p class="sync-mode__explication">{{ modeMeta.texte }}</p>
          </div>
        </div>
        <v-alert v-if="!vue?.schemaPret" type="warning" variant="tonal" rounded="lg" density="compact" class="mt-4">
          Base pas encore prête pour la synchronisation : {{ vue?.schemaProbleme }}
        </v-alert>
      </v-card>

      <!-- Activation en cours -->
      <v-card v-if="activation && activationVisible" class="classroom-card pa-5 mb-4">
        <p class="sync-section">Activation du mode local</p>
        <template v-if="activation.enCours">
          <p class="mb-2">{{ activation.etape }}</p>
          <v-progress-linear :model-value="activation.pourcentage" color="primary" height="10" rounded />
          <p class="sync-aide mt-2">
            Ne fermez pas le serveur pendant l'opération. La page se met à jour seule ; quand les comptes du serveur en ligne
            arrivent, elle vous demande de vous reconnecter.
          </p>
        </template>
        <v-alert v-else-if="activation.termine" type="success" variant="tonal" rounded="lg">
          {{ activation.etape }} Ce serveur est maintenant le serveur principal ; les comptes sont ceux du serveur en ligne.
        </v-alert>
        <v-alert v-else-if="activation.erreur" type="error" variant="tonal" rounded="lg">{{ activation.erreur }}</v-alert>
      </v-card>

      <v-card v-if="etat.mode === 'LOCAL_ARRETE'" class="classroom-card pa-5 mb-4">
        <p class="sync-section">Relier de nouveau ce serveur</p>
        <p class="sync-aide">{{ etat.derniereErreur || "Le mode local a été désactivé : utilisez le serveur en ligne." }}</p>
        <p class="sync-aide mb-0">
          Pour en refaire le serveur local : générez une nouvelle clé sur le serveur en ligne (même page), saisissez-la
          ci-dessous, testez la connexion puis réactivez le mode local. Les données de ce serveur seront remplacées par
          celles du serveur en ligne.
        </p>
      </v-card>

      <!-- Serveur en fonctionnement habituel : choisir son rôle ; ancien serveur local : le relier de nouveau -->
      <template v-if="(etat.mode === 'EN_LIGNE' || ancienLocal) && !activation?.enCours">
        <v-btn-toggle v-if="!ancienLocal" v-model="role" mandatory color="primary" rounded="lg" class="mb-4 sync-role">
          <v-btn value="EN_LIGNE" prepend-icon="mdi-cloud-outline">Ce serveur est le serveur en ligne</v-btn>
          <v-btn value="LOCAL" prepend-icon="mdi-lan">Ce serveur est le serveur local</v-btn>
        </v-btn-toggle>

        <v-card v-if="!ancienLocal && role === 'EN_LIGNE'" class="classroom-card pa-5 mb-4">
          <p class="sync-section">Clé d'appairage</p>
          <p class="sync-aide">
            Générez une clé ici, puis recopiez-la sur le serveur local (même page, « Ce serveur est le serveur local »). Elle
            signe chaque échange entre les deux serveurs ; n'importe qui connaissant seulement l'adresse ne peut rien envoyer.
          </p>
          <p v-if="etat.cleFin && !cleGeneree" class="sync-aide">
            Clé actuelle : …{{ etat.cleFin }} (créée le {{ date(etat.cleCreeeLe) }}). En générer une nouvelle annule l'ancienne.
          </p>
          <div v-if="cleGeneree" class="sync-cle">
            <code class="sync-cle__texte">{{ cleGeneree }}</code>
            <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-content-copy" @click="copierCle">Copier</v-btn>
            <p class="sync-aide mt-2 mb-0">Affichée une seule fois : copiez-la maintenant.</p>
          </div>
          <v-btn color="primary" rounded="lg" prepend-icon="mdi-key-plus" class="mt-3" :loading="occupe === 'cle'" @click="genererCle">
            {{ etat.cleFin ? 'Générer une nouvelle clé' : 'Générer une clé d\'appairage' }}
          </v-btn>
        </v-card>

        <v-card v-else class="classroom-card pa-5 mb-4">
          <p class="sync-section">Serveur en ligne à joindre</p>
          <v-text-field
            v-model="form.adresse"
            label="Adresse du serveur en ligne"
            placeholder="https://erp.exemple.com ou http://192.0.2.10:9090"
            prepend-inner-icon="mdi-web"
            variant="outlined"
            rounded="lg"
            hide-details="auto"
            class="mb-3"
          />
          <v-alert v-if="adresseNonChiffree" type="info" variant="tonal" density="compact" rounded="lg" class="mb-3">
            Adresse non chiffrée (http) : la clé ne circule jamais, mais les données oui. Préférez une adresse en https quand c'est possible.
          </v-alert>
          <v-textarea
            v-model="form.cle"
            label="Clé d'appairage (générée sur le serveur en ligne)"
            placeholder="MBSC1.…"
            :hint="cleEnregistree ? `Clé enregistrée : …${etat.cleFin} (laissez le champ vide pour la garder)` : ''"
            persistent-hint
            prepend-inner-icon="mdi-key-outline"
            variant="outlined"
            rounded="lg"
            rows="2"
            auto-grow
            hide-details="auto"
            class="mb-3"
          />
          <div class="sync-actions">
            <v-btn color="primary" rounded="lg" prepend-icon="mdi-content-save-outline" :loading="occupe === 'distant'"
              :disabled="!form.adresse || (!form.cle && !cleEnregistree)" @click="enregistrerDistant">Enregistrer</v-btn>
            <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-lan-connect" :loading="occupe === 'test'"
              :disabled="!etat.adresseDistante" @click="tester">Tester la connexion</v-btn>
          </div>
          <v-alert v-if="test" :type="test.ok ? 'success' : 'error'" variant="tonal" rounded="lg" class="mt-3">
            {{ test.message }}
            <span v-if="test.ecartHorlogeMs !== null && Math.abs(test.ecartHorlogeMs) > 60000">
              (Attention : horloges décalées de {{ Math.round(test.ecartHorlogeMs / 1000) }} s.)
            </span>
          </v-alert>
          <v-divider class="my-4" />
          <p class="sync-section">{{ ancienLocal ? 'Réactiver le mode local' : 'Activer le mode local' }}</p>
          <p class="sync-aide">
            Ce serveur télécharge toutes les données et pièces jointes du serveur en ligne puis devient le serveur principal ;
            le serveur en ligne devient une copie consultable. Les clés de l'IA, la messagerie et la facture normalisée sont
            propres à chaque serveur : ressaisissez-les ici ensuite.
          </p>
          <v-btn color="primary" rounded="lg" prepend-icon="mdi-swap-horizontal-bold" :disabled="!test?.ok || !vue?.schemaPret"
            @click="confirmation = 'activer'">{{ ancienLocal ? 'Réactiver le mode local…' : 'Activer le mode local…' }}</v-btn>
          <p v-if="!test?.ok" class="sync-aide mt-2 mb-0">Testez d'abord la connexion (elle doit réussir).</p>
        </v-card>
      </template>

      <!-- Serveur local principal -->
      <v-card v-if="etat.mode === 'LOCAL'" class="classroom-card pa-5 mb-4">
        <p class="sync-section">Synchronisation avec {{ etat.adresseDistante }}</p>
        <div class="sync-grille">
          <div class="sync-tuile"><span>Dernière synchronisation</span><strong>{{ date(etat.derniereSynchro) }}</strong></div>
          <div class="sync-tuile"><span>Changements en attente</span><strong>{{ etat.enAttente }}</strong></div>
          <div class="sync-tuile"><span>Mode local depuis</span><strong>{{ date(etat.appaireLe) }}</strong></div>
        </div>
        <v-alert v-if="etat.derniereErreur" type="warning" variant="tonal" rounded="lg" class="mt-3">
          {{ etat.derniereErreur }} <span class="sync-aide">({{ date(etat.derniereErreurLe) }})</span>
        </v-alert>
        <v-alert v-if="bilan" type="info" variant="tonal" rounded="lg" class="mt-3" closable @click:close="bilan = ''">{{ bilan }}</v-alert>
        <div class="sync-actions mt-4">
          <v-btn color="primary" rounded="lg" prepend-icon="mdi-sync" :loading="occupe === 'synchro'" @click="synchroniser">
            Synchroniser maintenant
          </v-btn>
          <v-btn variant="tonal" color="primary" rounded="lg" prepend-icon="mdi-database-sync-outline" :loading="occupe === 'resync'"
            @click="confirmation = 'resynchroniser'">Resynchroniser entièrement</v-btn>
          <v-btn variant="outlined" color="error" rounded="lg" prepend-icon="mdi-power" :loading="occupe === 'desactiver'"
            @click="confirmation = 'desactiver'">Désactiver le mode local…</v-btn>
        </div>
      </v-card>

      <!-- Copie en ligne -->
      <v-card v-if="etat.mode === 'MIROIR'" class="classroom-card pa-5 mb-4">
        <p class="sync-section">Copie de « {{ etat.serveurAssocie }} »</p>
        <div class="sync-grille">
          <div class="sync-tuile"><span>Appairé le</span><strong>{{ date(etat.appaireLe) }}</strong></div>
          <div class="sync-tuile"><span>Dernier contact</span><strong>{{ date(etat.dernierContact) }}</strong></div>
          <div class="sync-tuile"><span>Dernières données reçues</span><strong>{{ date(etat.derniereSynchro) }}</strong></div>
        </div>
        <v-divider class="my-4" />
        <p class="sync-section">En cas de panne durable du serveur local</p>
        <p class="sync-aide">
          « Reprendre la main » refait de ce serveur le serveur principal, avec les données reçues jusque-là. Le serveur local,
          s'il revient, passera en lecture seule. Les changements qu'il n'a pas encore envoyés seraient alors perdus pour ce serveur.
        </p>
        <v-btn variant="outlined" color="error" rounded="lg" prepend-icon="mdi-account-switch-outline" :loading="occupe === 'reprendre'"
          @click="confirmation = 'reprendre'">Reprendre la main…</v-btn>
      </v-card>
    </template>

    <!-- Confirmations -->
    <v-dialog :model-value="!!confirmation" max-width="520" @update:model-value="v => { if (!v) confirmation = null }">
      <v-card class="pa-5" rounded="xl">
        <template v-if="confirmation === 'activer'">
          <p class="sync-section">Activer le mode local ?</p>
          <ul class="sync-liste">
            <li>Le serveur en ligne passe aussitôt en copie : on n'y saisit plus (sauf notes de frais et comptes).</li>
            <li>Les données de <strong>ce</strong> serveur sont remplacées par celles du serveur en ligne.</li>
            <li>Les comptes deviennent eux aussi ceux du serveur en ligne : la page de connexion s'affiche en cours de route,
              connectez-vous avec votre compte du serveur en ligne.</li>
          </ul>
        </template>
        <template v-else-if="confirmation === 'desactiver'">
          <p class="sync-section">Désactiver le mode local ?</p>
          <p class="sync-aide">Une dernière synchronisation complète est faite, puis le serveur en ligne redevient le serveur principal.
            Ce serveur passe en lecture seule.</p>
        </template>
        <template v-else-if="confirmation === 'reprendre'">
          <p class="sync-section">Reprendre la main ?</p>
          <p class="sync-aide">Ce serveur redevient principal avec les données reçues jusqu'ici ; le serveur local ne pourra plus rien
            envoyer. Les actions faites en ligne pas encore appliquées seront à refaire.</p>
        </template>
        <template v-else-if="confirmation === 'resynchroniser'">
          <p class="sync-section">Resynchroniser entièrement ?</p>
          <p class="sync-aide">Toutes les données recopiées du serveur en ligne sont remplacées par celles de ce serveur
            (utile après une mise à jour ou une intervention directe sur une base).</p>
        </template>
        <div class="sync-actions mt-4 justify-end">
          <v-btn variant="text" rounded="lg" @click="confirmation = null">Annuler</v-btn>
          <v-btn v-if="confirmation === 'activer'" color="primary" rounded="lg" :loading="occupe === 'activer'" @click="activer">Activer</v-btn>
          <v-btn v-if="confirmation === 'desactiver'" color="error" rounded="lg" :loading="occupe === 'desactiver'" @click="desactiver">Désactiver</v-btn>
          <v-btn v-if="confirmation === 'reprendre'" color="error" rounded="lg" :loading="occupe === 'reprendre'" @click="reprendre">Reprendre la main</v-btn>
          <v-btn v-if="confirmation === 'resynchroniser'" color="primary" rounded="lg" :loading="occupe === 'resync'" @click="resynchroniser">Resynchroniser</v-btn>
        </div>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.sync-mode { display: flex; align-items: flex-start; gap: 14px; }
.sync-mode__icone { width: 52px; height: 52px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.sync-mode__texte { min-width: 0; }
.sync-mode__libelle { margin: 0 0 4px; font-weight: 800; font-size: 1.05rem; color: #0f172a; }
.sync-mode__explication { margin: 0; color: #475569; font-size: 0.9rem; line-height: 1.5; }
.sync-section { margin: 0 0 8px; font-weight: 700; color: #0f172a; }
.sync-aide { color: #64748b; font-size: 0.86rem; line-height: 1.5; }
.sync-role { max-width: 100%; }
.sync-cle { margin-top: 8px; padding: 12px; border: 1px dashed #93c5fd; border-radius: 12px; background: #eff6ff; }
.sync-cle__texte { display: block; margin-bottom: 8px; font-size: 0.85rem; word-break: break-all; color: #1e3a8a; }
.sync-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.sync-grille { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.sync-tuile { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border-radius: 12px; background: #f6f8fa; }
.sync-tuile span { font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px; color: #94a3b8; }
.sync-tuile strong { font-size: 0.95rem; color: #0f172a; }
.sync-liste { margin: 0; padding-left: 18px; color: #475569; font-size: 0.9rem; line-height: 1.6; }
</style>
