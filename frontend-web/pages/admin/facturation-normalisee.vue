<script setup lang="ts">
/**
 * Administration › Facture normalisée (DGI RDC) : dispositif e-MCF (mode, adresse, jeton jamais relu),
 * groupes de taxation et suivi des factures à régulariser.
 */
definePageMeta({ roles: ['ADMIN'] })
useHead({ title: 'Facture normalisée' })

interface Groupe { code: string, libelle: string, taux: number, actif: boolean, ordre?: number }
interface Etat {
  actif: boolean, mode: 'SIMULATION' | 'TEST' | 'PRODUCTION', urlBase: string | null, jetonConfigure: boolean, jetonFin: string | null,
  numeroDef: string | null, delaiMs: number, utilisable: boolean, enAttente: number, rejetees: number, groupes: Groupe[]
}
interface Facture {
  id: number, venteId: number, venteReference: string, clientNom: string, totalTtc: number, devise: string, type: string,
  statut: string, tentatives: number, derniereErreur: string | null, prochaineTentative: string | null
}

const api = useApi()
const etat = ref<Etat | null>(null)
const aRegulariser = ref<Facture[]>([])
const chargement = ref(true)
const erreur = ref('')
const succes = ref('')
const action = ref<'' | 'enregistrer' | 'tester' | 'retirer'>('')
const resultatTest = ref<{ ok: boolean, message: string } | null>(null)
const voirJeton = ref(false)
const form = reactive({ mode: 'SIMULATION' as Etat['mode'], urlBase: '', jeton: '', numeroDef: '', delaiMs: 10000 })
const groupes = ref<Groupe[]>([])

const MODES = [
  { title: 'Simulation (essais, sans valeur fiscale)', value: 'SIMULATION' },
  { title: 'Test (e-MCF de test de la DGI)', value: 'TEST' },
  { title: 'Production (e-MCF réel)', value: 'PRODUCTION' },
]

function appliquer(e: Etat) {
  etat.value = e
  form.mode = e.mode
  form.urlBase = e.urlBase ?? ''
  form.numeroDef = e.numeroDef ?? ''
  form.delaiMs = e.delaiMs
  form.jeton = ''
  groupes.value = e.groupes.map(g => ({ ...g }))
}

async function charger() {
  chargement.value = true
  try {
    appliquer(await api<Etat>('/admin/facturation-normalisee'))
    aRegulariser.value = await api<Facture[]>('/ventes/factures-a-regulariser').catch(() => [])
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les paramètres.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  erreur.value = ''
  succes.value = ''
  resultatTest.value = null
  action.value = 'enregistrer'
  try {
    appliquer(await api<Etat>('/admin/facturation-normalisee', {
      method: 'PUT',
      body: {
        mode: form.mode, urlBase: form.urlBase.trim() || null, jeton: form.jeton || null,
        numeroDef: form.numeroDef.trim() || null, delaiMs: Number(form.delaiMs) || 10000,
        groupes: groupes.value.map(g => ({ code: g.code.trim().toUpperCase(), libelle: g.libelle.trim(), taux: Number(g.taux) || 0, actif: g.actif })),
      },
    }))
    succes.value = 'Paramètres enregistrés.'
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Enregistrement impossible.')
  } finally {
    action.value = ''
  }
}

async function tester() {
  action.value = 'tester'
  resultatTest.value = null
  try {
    resultatTest.value = await api<{ ok: boolean, message: string }>('/admin/facturation-normalisee/tester', { method: 'POST' })
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Le test n’a pas pu être lancé.')
  } finally {
    action.value = ''
  }
}

async function retirerJeton() {
  if (!confirm('Retirer le jeton enregistré ?')) return
  action.value = 'retirer'
  try {
    appliquer(await api<Etat>('/admin/facturation-normalisee/jeton', { method: 'DELETE' }))
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Impossible de retirer le jeton.')
  } finally {
    action.value = ''
  }
}

function ajouterGroupe() {
  const pris = new Set(groupes.value.map(g => g.code))
  const code = 'CDEFGHIJKLMNOPQRSTUVWXYZ'.split('').find(c => !pris.has(c)) ?? 'ZZ'
  groupes.value.push({ code, libelle: '', taux: 0, actif: true })
}

const fmtDate = (d?: string | null) => (d ? new Date(d).toLocaleString('fr-FR') : '')
const fmtMontant = (n: number, d: string) => `${Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 })} ${d}`
</script>

<template>
  <div class="fn-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Facture normalisée (DGI)</h1>
        <p class="page-sub">Connexion au dispositif de facturation e-MCF de la DGI (RDC)</p>
      </div>
      <v-chip v-if="etat" :color="etat.actif ? (etat.mode === 'SIMULATION' ? 'warning' : 'success') : 'grey'" variant="tonal">
        {{ etat.actif ? (etat.mode === 'SIMULATION' ? 'Actif en simulation' : `Actif (${etat.mode.toLowerCase()})`) : 'Désactivé' }}
      </v-chip>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" class="mb-4" closable @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert type="info" variant="tonal" density="compact" class="mb-4" icon="mdi-information-outline">
      Chaque vente validée est transmise au e-MCF, qui renvoie l'UID, la signature, la date fiscale et le code QR imprimés sur la facture.
      Une panne du dispositif ne bloque jamais la vente : la facture est retransmise automatiquement. Les noms exacts des échanges
      (adresse, champs) doivent être confirmés avec la spécification technique publiée par la DGI avant la mise en production.
    </v-alert>
    <v-alert v-if="etat && (etat.enAttente || etat.rejetees)" type="warning" variant="tonal" class="mb-4" icon="mdi-clock-alert-outline">
      {{ etat.enAttente }} facture(s) en attente de certification, {{ etat.rejetees }} refusée(s) par le dispositif.
    </v-alert>

    <v-skeleton-loader v-if="chargement" type="card, card" />
    <template v-else>
      <v-card class="classroom-card pa-5 mb-4">
        <v-alert :type="etat?.actif ? 'success' : 'warning'" variant="tonal" density="compact" class="mb-4"
          :icon="etat?.actif ? 'mdi-check-circle-outline' : 'mdi-power-plug-off-outline'">
          La facture normalisée est <strong>{{ etat?.actif ? 'activée' : 'désactivée' }}</strong>. Elle s'active ou se coupe dans
          <NuxtLink to="/admin/modules">Administration › Modules</NuxtLink> (Ventes › Facture normalisée).
        </v-alert>
        <div class="fn-grille">
          <v-select v-model="form.mode" :items="MODES" label="Mode" variant="outlined" density="comfortable" hide-details="auto" />
          <v-text-field v-model="form.numeroDef" label="Numéro du dispositif (DEF / NIM)" variant="outlined" density="comfortable" hide-details="auto" />
          <v-text-field v-model="form.urlBase" label="Adresse du e-MCF" placeholder="http://192.168.1.50:8080" variant="outlined" density="comfortable"
            prepend-inner-icon="mdi-server-network-outline" hide-details="auto" :disabled="form.mode === 'SIMULATION'" class="fn-large"
            hint="Le e-MCF est téléchargeable gratuitement sur edef.dgirdc.cd ; indiquez l'adresse où il est installé." persistent-hint />
          <div>
            <v-text-field v-model="form.jeton" label="Jeton d'accès" :type="voirJeton ? 'text' : 'password'" autocomplete="new-password"
              :placeholder="etat?.jetonConfigure ? `••••••••${etat.jetonFin ?? ''} (laisser vide pour le conserver)` : ''"
              variant="outlined" density="comfortable" hide-details="auto" prepend-inner-icon="mdi-key-variant" :disabled="form.mode === 'SIMULATION'"
              :append-inner-icon="voirJeton ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="voirJeton = !voirJeton" />
            <v-btn v-if="etat?.jetonConfigure" variant="text" size="x-small" color="error" class="mt-1" :loading="action === 'retirer'" @click="retirerJeton">
              Retirer le jeton enregistré
            </v-btn>
          </div>
          <v-text-field v-model.number="form.delaiMs" type="number" label="Délai d'attente (ms)" variant="outlined" density="comfortable" hide-details="auto" />
        </div>
        <v-alert v-if="resultatTest" :type="resultatTest.ok ? 'success' : 'error'" variant="tonal" density="compact" class="mt-4">{{ resultatTest.message }}</v-alert>
        <div class="fn-actions mt-4">
          <v-btn color="primary" prepend-icon="mdi-content-save-outline" :loading="action === 'enregistrer'" @click="enregistrer">Enregistrer</v-btn>
          <v-btn variant="tonal" prepend-icon="mdi-lightning-bolt-outline" :loading="action === 'tester'" @click="tester">Tester le dispositif</v-btn>
        </div>
      </v-card>

      <v-card class="classroom-card pa-5 mb-4">
        <h2 class="fn-titre">Groupes de taxation</h2>
        <p class="fn-aide">Les groupes A (exonéré et hors champ) et B (taxable) sont obligatoires. Ajoutez les autres groupes de la DGI avec leur taux.
          Un article utilise B s'il est soumis à la TVA, A sinon, sauf groupe imposé.</p>
        <div v-for="g in groupes" :key="g.code" class="fn-groupe">
          <v-text-field v-model="g.code" label="Code" variant="outlined" density="compact" hide-details style="max-width: 90px" :disabled="g.code === 'A' || g.code === 'B'" />
          <v-text-field v-model="g.libelle" label="Libellé" variant="outlined" density="compact" hide-details />
          <v-text-field v-model.number="g.taux" label="Taux %" type="number" variant="outlined" density="compact" hide-details style="max-width: 110px" />
          <v-switch v-model="g.actif" color="primary" hide-details density="compact" label="Actif" :disabled="g.code === 'A' || g.code === 'B'" />
        </div>
        <v-btn variant="text" size="small" prepend-icon="mdi-plus" class="mt-2" @click="ajouterGroupe">Ajouter un groupe</v-btn>
      </v-card>

      <v-card v-if="aRegulariser.length" class="classroom-card pa-5">
        <h2 class="fn-titre">Factures à régulariser</h2>
        <v-table density="compact">
          <thead><tr><th>Vente</th><th>Client</th><th class="text-right">Montant</th><th>Statut</th><th>Tentatives</th><th>Motif</th></tr></thead>
          <tbody>
            <tr v-for="f in aRegulariser" :key="f.id">
              <td><NuxtLink :to="`/ventes/${f.venteId}`">{{ f.venteReference }}</NuxtLink> <small v-if="f.type === 'AVOIR'">(avoir)</small></td>
              <td>{{ f.clientNom }}</td>
              <td class="text-right">{{ fmtMontant(f.totalTtc, f.devise) }}</td>
              <td><v-chip size="x-small" :color="f.statut === 'REJETEE' ? 'error' : 'warning'" variant="tonal">{{ f.statut === 'REJETEE' ? 'Refusée' : 'En attente' }}</v-chip></td>
              <td>{{ f.tentatives }}<small v-if="f.prochaineTentative"> · prochaine {{ fmtDate(f.prochaineTentative) }}</small></td>
              <td>{{ f.derniereErreur }}</td>
            </tr>
          </tbody>
        </v-table>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.fn-page { max-width: 960px; }
.fn-grille { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.fn-large { grid-column: 1 / -1; }
.fn-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.fn-titre { font-size: 1.02rem; font-weight: 700; margin: 0 0 4px; }
.fn-aide { font-size: 0.78rem; color: #4b5563; margin: 0 0 12px; }
.fn-groupe { display: flex; gap: 10px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
@media (max-width: 700px) { .fn-grille { grid-template-columns: 1fr; } }
</style>
