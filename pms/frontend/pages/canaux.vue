<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { dateHeure, dateCourte } from '~/utils/libelles'

/**
 * Canaux de vente (iCal) : pour chaque unité, l'adresse de SON calendrier à donner à Booking.com, Airbnb ou Expedia/VRBO,
 * et les calendriers de ces canaux à importer (lus toutes les 10 minutes). Les conflits de dates sont signalés ici.
 */
useHead({ title: 'Canaux de vente' })

interface Canal { id: number, uniteId: number, uniteNumero: string, etablissementNom: string, canal: string, url: string, actif: boolean,
  dernierImport: string | null, dernierStatut: string | null, dernierMessage: string | null, nbEvenements: number | null }
interface Export { uniteId: number, uniteNumero: string, typeNom: string, etablissementNom: string, jeton: string }
interface Alerte { id: number, canal: string, uniteNumero: string, debut: string, fin: string, message: string, creeLe: string }

const NOMS: Record<string, string> = { BOOKING: 'Booking.com', AIRBNB: 'Airbnb', EXPEDIA: 'Expedia / VRBO', AUTRE: 'Autre' }
const api = useApi()
const session = useSessionStore()
const direction = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))
const canaux = ref<Canal[]>([])
const exports = ref<Export[]>([])
const alertes = ref<Alerte[]>([])
const erreur = ref('')
const succes = ref('')
const copie = ref<number | null>(null)
const action = ref<number | null>(null)

async function charger() {
  try {
    const [c, a] = await Promise.all([api<Canal[]>('/canaux'), api<Alerte[]>('/canaux/alertes')])
    // Établissement de travail : ses unités seulement (tous pour « Tous les établissements »).
    const nom = useEtablissementStore().courant?.nom
    canaux.value = nom ? c.filter(x => x.etablissementNom === nom) : c
    alertes.value = a
    if (direction.value) {
      const ex = await api<Export[]>('/canaux/exports')
      exports.value = nom ? ex.filter(x => x.etablissementNom === nom) : ex
    }
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
onMounted(charger)

const adresseFlux = (jeton: string) => `${window.location.origin}/api/ical/${jeton}.ics`
async function copier(e: Export) {
  try {
    await navigator.clipboard.writeText(adresseFlux(e.jeton))
    copie.value = e.uniteId
    setTimeout(() => { copie.value = null }, 2000)
  } catch { erreur.value = "Copie impossible : sélectionnez l'adresse à la main." }
}
async function renouveler(e: Export) {
  if (!(await useDialogues().confirmer({ titre: `Changer l'adresse du calendrier de ${e.uniteNumero} ?`,
    message: "L'ancienne adresse cessera de fonctionner : redonnez la nouvelle aux canaux.", bouton: 'Changer', nature: 'attention' }))) return
  await api(`/unites/${e.uniteId}/ical/renouveler`, { method: 'POST' })
  await charger()
}

const form = reactive({ ouvert: false, id: null as number | null, uniteId: null as number | null, canal: 'BOOKING', url: '', actif: true })
function ouvrir(c?: Canal) {
  Object.assign(form, c ? { ouvert: true, id: c.id, uniteId: c.uniteId, canal: c.canal, url: c.url, actif: c.actif }
    : { ouvert: true, id: null, uniteId: exports.value[0]?.uniteId ?? null, canal: 'BOOKING', url: '', actif: true })
}
async function enregistrer() {
  try {
    await api(form.id ? `/canaux/${form.id}` : '/canaux', { method: form.id ? 'PUT' : 'POST',
      body: { uniteId: form.uniteId, canal: form.canal, url: form.url, actif: form.actif } })
    form.ouvert = false
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
async function supprimer(c: Canal) {
  if (!(await useDialogues().confirmer({ titre: `Retirer le calendrier ${NOMS[c.canal]} de l'unité ${c.uniteNumero} ?`,
    message: 'Les réservations déjà importées restent ; elles ne seront plus mises à jour.', bouton: 'Retirer', nature: 'danger' }))) return
  await api(`/canaux/${c.id}`, { method: 'DELETE' })
  await charger()
}
async function synchroniser(c: Canal) {
  action.value = c.id
  erreur.value = ''
  try {
    const b = await api<{ crees: number, deplaces: number, annules: number, conflits: number, evenements: number }>(`/canaux/${c.id}/synchroniser`, { method: 'POST' })
    succes.value = `${NOMS[c.canal]} / ${c.uniteNumero} : ${b.evenements} événement(s), ${b.crees} créé(s), ${b.deplaces} déplacé(s), ${b.annules} annulé(s)`
      + (b.conflits ? `, ${b.conflits} conflit(s).` : '.')
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    action.value = null
    await charger()
  }
}
async function traitee(a: Alerte) {
  await api(`/canaux/alertes/${a.id}/traitee`, { method: 'POST' })
  await charger()
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Canaux de vente</h1>
        <p class="page-sub">Calendriers iCal échangés avec Booking.com, Airbnb et Expedia / VRBO</p>
      </div>
      <v-btn v-if="direction" color="primary" prepend-icon="mdi-plus" :disabled="!exports.length" @click="ouvrir()">Calendrier à importer</v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert type="info" variant="tonal" density="compact" class="mb-4">
      Le calendrier iCal échange seulement les <strong>dates occupées</strong> : ni le prix ni le nom du client. Une réservation reçue d'un canal arrive
      au nom de « Client Booking.com » (ou autre) avec un prix à compléter ; ouvrez-la pour saisir le vrai client. Les canaux relisent votre calendrier
      à leur propre rythme (de quelques minutes à quelques heures).
    </v-alert>

    <v-card v-if="alertes.length" class="classroom-card mb-4">
      <v-card-title class="text-subtitle-1 font-weight-bold text-error"><v-icon icon="mdi-alert-outline" /> Conflits à traiter ({{ alertes.length }})</v-card-title>
      <v-list density="comfortable">
        <v-list-item v-for="a in alertes" :key="a.id" :title="`${NOMS[a.canal]} · unité ${a.uniteNumero} · ${dateCourte(a.debut)} → ${dateCourte(a.fin)}`" :subtitle="a.message">
          <template #append><v-btn size="small" variant="tonal" @click="traitee(a)">Traité</v-btn></template>
        </v-list-item>
      </v-list>
    </v-card>

    <v-card class="classroom-card mb-4">
      <v-card-title class="text-subtitle-1 font-weight-bold">Calendriers importés</v-card-title>
      <v-table density="comfortable">
        <thead><tr><th>Unité</th><th>Canal</th><th>Dernière lecture</th><th>Résultat</th><th /></tr></thead>
        <tbody>
          <tr v-for="c in canaux" :key="c.id">
            <td><strong>{{ c.uniteNumero }}</strong> <span class="text-caption text-medium-emphasis">{{ c.etablissementNom }}</span></td>
            <td>{{ NOMS[c.canal] }}<v-chip v-if="!c.actif" size="x-small" class="ml-1">désactivé</v-chip></td>
            <td>{{ dateHeure(c.dernierImport) }}</td>
            <td><v-chip v-if="c.dernierStatut" size="x-small" variant="tonal" :color="c.dernierStatut === 'OK' ? 'success' : c.dernierStatut === 'CONFLIT' ? 'warning' : 'error'">{{ c.dernierStatut }}</v-chip>
              <span class="text-caption ml-1">{{ c.dernierMessage }}</span></td>
            <td class="text-right text-no-wrap">
              <v-btn size="small" variant="text" prepend-icon="mdi-sync" :loading="action === c.id" @click="synchroniser(c)">Lire</v-btn>
              <template v-if="direction">
                <v-btn size="small" variant="text" icon="mdi-pencil" aria-label="Modifier" @click="ouvrir(c)" />
                <v-btn size="small" variant="text" icon="mdi-delete-outline" color="error" aria-label="Retirer" @click="supprimer(c)" />
              </template>
            </td>
          </tr>
          <tr v-if="!canaux.length"><td colspan="5" class="text-medium-emphasis">Aucun calendrier importé.</td></tr>
        </tbody>
      </v-table>
    </v-card>

    <v-card v-if="direction" class="classroom-card">
      <v-card-title class="text-subtitle-1 font-weight-bold">Calendriers de vos unités (à donner aux canaux)</v-card-title>
      <v-table density="comfortable">
        <thead><tr><th>Unité</th><th>Adresse du calendrier</th><th /></tr></thead>
        <tbody>
          <tr v-for="e in exports" :key="e.uniteId">
            <td><strong>{{ e.uniteNumero }}</strong> <span class="text-caption text-medium-emphasis">{{ e.typeNom }} · {{ e.etablissementNom }}</span></td>
            <td><code class="flux">{{ adresseFlux(e.jeton) }}</code></td>
            <td class="text-right text-no-wrap">
              <v-btn size="small" variant="tonal" :prepend-icon="copie === e.uniteId ? 'mdi-check' : 'mdi-content-copy'" @click="copier(e)">{{ copie === e.uniteId ? 'Copiée' : 'Copier' }}</v-btn>
              <v-btn size="small" variant="text" icon="mdi-refresh" aria-label="Changer l'adresse" @click="renouveler(e)" />
            </td>
          </tr>
        </tbody>
      </v-table>
      <v-card-text class="text-caption text-medium-emphasis">
        Booking.com : Extranet › Tarifs et disponibilités › Synchroniser les calendriers. Airbnb : Calendrier › Disponibilités › Connecter un calendrier.
        Le serveur de l'hôtellerie doit être joignable depuis Internet pour que les canaux lisent ces adresses.
      </v-card-text>
    </v-card>

    <v-dialog v-model="form.ouvert" max-width="560">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Calendrier à importer</v-card-title>
        <v-card-text>
          <v-select v-model="form.uniteId" :items="exports.map(e => ({ value: e.uniteId, title: `${e.uniteNumero} — ${e.typeNom} (${e.etablissementNom})` }))" label="Unité" :disabled="!!form.id" />
          <v-select v-model="form.canal" :items="Object.entries(NOMS).map(([value, title]) => ({ value, title }))" label="Canal" />
          <v-text-field v-model="form.url" label="Adresse iCal fournie par le canal (https://…)" />
          <v-switch v-model="form.actif" label="Lecture automatique (toutes les 10 minutes)" color="primary" hide-details inset />
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="form.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!form.uniteId || !form.url" @click="enregistrer">Enregistrer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.flux { font-size: 0.75rem; word-break: break-all; }
</style>
