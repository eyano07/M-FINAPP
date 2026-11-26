<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { CANAUX, DUREES, jourIso, usd } from '~/utils/libelles'

/**
 * Nouvelle réservation : unité, client (existant ou nouveau), durée (nuit, heure, semaine, mois), dates, prix proposé par
 * le tarif (modifiable), occupants, canal. La base refuse toute surréservation (message clair).
 */
const props = defineProps<{ modelValue: boolean, uniteId?: number | null, dateArrivee?: string | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void, (e: 'creee', r: any): void }>()

interface Unite { id: number, etablissementNom: string, typeNom: string, numero: string, horsService: boolean, actif: boolean }
interface Client { id: number, nom: string, prenom: string | null, telephone: string | null }

const api = useApi()
const unites = ref<Unite[]>([])
const clients = ref<Client[]>([])
const recherche = ref('')
const nouveau = ref(false)
const enregistrement = ref(false)
const erreur = ref('')
const prixPropose = ref<number | null>(null)
interface Forfait { id: number, libelle: string, prix: number, dureeHeures: number }
const forfaits = ref<Forfait[]>([])

const demain = () => { const d = new Date(); d.setDate(d.getDate() + 1); return jourIso(d) }
const form = reactive({
  uniteId: null as number | null, clientId: null as number | null,
  nc: { nom: '', prenom: '', telephone: '', email: '' },
  uniteDuree: 'NUIT', dateArrivee: jourIso(new Date()), dateDepart: demain(),
  heureDebut: '', heureFin: '', prixUnitaire: null as number | null, tarifId: null as number | null,
  adultes: 1, enfants: 0, canal: 'RECEPTION', statut: 'CONFIRMEE', notes: '',
})

const ouvert = computed({ get: () => props.modelValue, set: (v: boolean) => emit('update:modelValue', v) })

watch(() => props.modelValue, async (v) => {
  if (!v) return
  erreur.value = ''
  nouveau.value = false
  Object.assign(form, {
    uniteId: props.uniteId ?? null, clientId: null, nc: { nom: '', prenom: '', telephone: '', email: '' },
    uniteDuree: 'NUIT', dateArrivee: props.dateArrivee ?? jourIso(new Date()), heureDebut: '', heureFin: '',
    prixUnitaire: null, adultes: 1, enfants: 0, canal: 'RECEPTION', statut: 'CONFIRMEE', notes: '', tarifId: null,
  })
  const a = new Date(form.dateArrivee + 'T00:00:00')
  a.setDate(a.getDate() + 1)
  form.dateDepart = jourIso(a)
  try {
    unites.value = (await api<Unite[]>('/unites', { query: { etablissementId: useEtablissementStore().filtre } })).filter(u => u.actif)
  } catch (e) { erreur.value = messageErreur(e) }
  chercher('')
}, { immediate: true })

let attente: ReturnType<typeof setTimeout> | null = null
function chercher(q: string) {
  if (attente) clearTimeout(attente)
  attente = setTimeout(async () => {
    try { clients.value = await api<Client[]>('/clients', { query: { q } }) } catch { clients.value = [] }
  }, 250)
}
watch(recherche, q => chercher(q ?? ''))

const optionsUnites = computed(() => unites.value.map(u => ({
  value: u.id, title: `${u.numero} — ${u.typeNom}`, subtitle: u.etablissementNom + (u.horsService ? ' · hors service' : ''),
  props: { disabled: u.horsService },
})))
const optionsClients = computed(() => clients.value.map(c => ({
  value: c.id, title: `${c.prenom ? c.prenom + ' ' : ''}${c.nom}`, subtitle: c.telephone ?? '',
})))

async function proposerPrix() {
  prixPropose.value = null
  if (!form.uniteId) return
  if (form.uniteDuree === 'CRENEAU') {
    // Forfaits de l'unité pour l'heure d'arrivée choisie ; le premier (plage couverte) est proposé.
    try {
      const arrivee = new Date(`${form.dateArrivee}T${form.heureDebut || '12:00'}:00`).toISOString()
      forfaits.value = await api<Forfait[]>(`/unites/${form.uniteId}/forfaits`, { query: { arrivee } })
      if (!forfaits.value.some(f => f.id === form.tarifId)) form.tarifId = forfaits.value[0]?.id ?? null
    } catch { forfaits.value = [] }
    prixPropose.value = forfaits.value.find(f => f.id === form.tarifId)?.prix ?? null
    return
  }
  try {
    const r = await api<{ prix: number | null }>('/tarifs/propose', {
      query: { uniteId: form.uniteId, uniteDuree: form.uniteDuree, date: form.dateArrivee },
    })
    prixPropose.value = r.prix
  } catch { prixPropose.value = null }
}
watch(() => [form.uniteId, form.uniteDuree, form.dateArrivee, form.heureDebut, form.tarifId], proposerPrix)

const aLHeure = computed(() => form.uniteDuree === 'HEURE' || form.uniteDuree === 'CRENEAU')
const creneau = computed(() => form.uniteDuree === 'CRENEAU')

function enIso(jour: string, heure: string) {
  return new Date(`${jour}T${heure || '00:00'}:00`).toISOString()
}

async function enregistrer() {
  erreur.value = ''
  if (!form.uniteId) { erreur.value = "Choisissez l'unité."; return }
  if (!nouveau.value && !form.clientId) { erreur.value = 'Choisissez le client ou créez-le.'; return }
  if (nouveau.value && !form.nc.nom.trim()) { erreur.value = 'Saisissez le nom du client.'; return }
  enregistrement.value = true
  try {
    const corps: Record<string, unknown> = {
      uniteId: form.uniteId,
      clientId: nouveau.value ? null : form.clientId,
      nouveauClient: nouveau.value ? { ...form.nc } : null,
      uniteDuree: form.uniteDuree,
      prixUnitaire: form.prixUnitaire ?? undefined,
      adultes: form.adultes, enfants: form.enfants, canal: form.canal, statut: form.statut, notes: form.notes || null,
    }
    if (creneau.value) {
      if (!form.tarifId) { erreur.value = 'Choisissez le forfait.'; enregistrement.value = false; return }
      corps.debut = enIso(form.dateArrivee, form.heureDebut)
      corps.tarifId = form.tarifId
    } else if (aLHeure.value) {
      corps.debut = enIso(form.dateArrivee, form.heureDebut)
      corps.fin = enIso(form.dateArrivee, form.heureFin)
    } else {
      corps.dateArrivee = form.dateArrivee
      corps.dateDepart = form.dateDepart
    }
    const r = await api('/reservations', { method: 'POST', body: corps })
    emit('creee', r)
    ouvert.value = false
  } catch (e) {
    erreur.value = messageErreur(e, "La réservation n'a pas pu être enregistrée.")
  } finally {
    enregistrement.value = false
  }
}
</script>

<template>
  <v-dialog v-model="ouvert" max-width="720" scrollable>
    <v-card class="pa-2">
      <v-card-title class="text-h6 font-weight-bold">Nouvelle réservation</v-card-title>
      <v-card-text>
        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-4">{{ erreur }}</v-alert>
        <v-row dense>
          <v-col cols="12" sm="6">
            <v-autocomplete v-model="form.uniteId" :items="optionsUnites" label="Unité (chambre, appartement…)" item-title="title"
              item-value="value" prepend-inner-icon="mdi-bed-outline">
              <template #item="{ props: p, item }"><v-list-item v-bind="p" :subtitle="item.raw.subtitle" /></template>
            </v-autocomplete>
          </v-col>
          <v-col cols="12" sm="6">
            <v-select v-model="form.uniteDuree" :items="DUREES" label="Durée" />
          </v-col>
          <template v-if="!aLHeure">
            <v-col cols="6"><v-text-field v-model="form.dateArrivee" type="date" label="Arrivée" /></v-col>
            <v-col cols="6"><v-text-field v-model="form.dateDepart" type="date" label="Départ" /></v-col>
          </template>
          <template v-else>
            <v-col cols="12" sm="4"><v-text-field v-model="form.dateArrivee" type="date" label="Jour" /></v-col>
            <v-col cols="6" sm="4"><v-text-field v-model="form.heureDebut" type="time" label="Arrivée" /></v-col>
            <v-col v-if="!creneau" cols="6" sm="4"><v-text-field v-model="form.heureFin" type="time" label="Départ" /></v-col>
            <v-col v-else cols="12">
              <v-select v-model="form.tarifId" :items="forfaits.map(f => ({ value: f.id, title: `${f.libelle} — ${f.dureeHeures} h · ${usd(f.prix)}` }))"
                label="Forfait" :no-data-text="'Aucun forfait pour ce type d’unité (Établissements et tarifs)'" />
            </v-col>
          </template>
        </v-row>

        <div class="d-flex align-center justify-space-between mt-2 mb-2">
          <span class="text-subtitle-2">Client</span>
          <v-btn size="small" variant="text" :prepend-icon="nouveau ? 'mdi-magnify' : 'mdi-account-plus-outline'" @click="nouveau = !nouveau">
            {{ nouveau ? 'Choisir un client existant' : 'Nouveau client' }}
          </v-btn>
        </div>
        <v-autocomplete v-if="!nouveau" v-model="form.clientId" v-model:search="recherche" :items="optionsClients" no-filter
          label="Rechercher (nom, téléphone, pièce…)" item-title="title" item-value="value" prepend-inner-icon="mdi-account-search-outline">
          <template #item="{ props: p, item }"><v-list-item v-bind="p" :subtitle="item.raw.subtitle" /></template>
        </v-autocomplete>
        <v-row v-else dense>
          <v-col cols="12" sm="6"><v-text-field v-model="form.nc.nom" label="Nom *" /></v-col>
          <v-col cols="12" sm="6"><v-text-field v-model="form.nc.prenom" label="Prénom" /></v-col>
          <v-col cols="12" sm="6"><v-text-field v-model="form.nc.telephone" label="Téléphone" inputmode="tel" /></v-col>
          <v-col cols="12" sm="6"><v-text-field v-model="form.nc.email" label="E-mail" type="email" /></v-col>
        </v-row>

        <v-row dense>
          <v-col cols="12" sm="4">
            <v-text-field v-model.number="form.prixUnitaire" type="number" min="0" label="Prix (USD HT)"
              :placeholder="prixPropose != null ? String(prixPropose) : ''"
              :hint="prixPropose != null ? `Tarif : ${usd(prixPropose)} — laissez vide pour l'appliquer` : 'Aucun tarif : saisissez le prix'"
              persistent-hint />
          </v-col>
          <v-col cols="6" sm="2"><v-text-field v-model.number="form.adultes" type="number" min="0" label="Adultes" /></v-col>
          <v-col cols="6" sm="2"><v-text-field v-model.number="form.enfants" type="number" min="0" label="Enfants" /></v-col>
          <v-col cols="12" sm="4"><v-select v-model="form.canal" :items="CANAUX" label="Provenance" /></v-col>
          <v-col cols="12" sm="4">
            <v-select v-model="form.statut" :items="[{ value: 'CONFIRMEE', title: 'Confirmée' }, { value: 'OPTION', title: 'Option (à confirmer)' }]" label="Statut" />
          </v-col>
          <v-col cols="12" sm="8"><v-text-field v-model="form.notes" label="Notes (demandes, heure d'arrivée…)" /></v-col>
        </v-row>
      </v-card-text>
      <v-card-actions class="px-6 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="enregistrement" @click="ouvert = false">Annuler</v-btn>
        <v-btn color="primary" variant="flat" :loading="enregistrement" prepend-icon="mdi-check" @click="enregistrer">Réserver</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
