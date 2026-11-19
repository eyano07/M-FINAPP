<script setup lang="ts">
useHead({ title: 'Clients' })

interface Client { id: number, nom: string, prenom: string | null, telephone: string | null, email: string | null, entreprise: string | null,
  nationalite: string | null, finappClientId: number | null, sejours: number }

const api = useApi()
const clients = ref<Client[]>([])
const q = ref('')
const chargement = ref(false)
const erreur = ref('')
const nouveau = reactive({ ouvert: false, nom: '', prenom: '', telephone: '', email: '', entreprise: '' })

async function charger() {
  chargement.value = true
  try {
    clients.value = await api<Client[]>('/clients', { query: { q: q.value || undefined } })
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e)
  } finally {
    chargement.value = false
  }
}
onMounted(charger)
let attente: ReturnType<typeof setTimeout> | null = null
watch(q, () => { if (attente) clearTimeout(attente); attente = setTimeout(charger, 250) })

async function creer() {
  try {
    const c = await api<Client>('/clients', { method: 'POST', body: { nom: nouveau.nom, prenom: nouveau.prenom || null, telephone: nouveau.telephone || null,
      email: nouveau.email || null, entreprise: nouveau.entreprise || null } })
    nouveau.ouvert = false
    await navigateTo(`/clients/${c.id}`)
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Exploitation</div>
        <h1 class="page-title">Clients</h1>
        <p class="page-sub">Fiches, préférences et historique des séjours</p>
      </div>
      <v-btn color="primary" prepend-icon="mdi-account-plus-outline" @click="Object.assign(nouveau, { ouvert: true, nom: '', prenom: '', telephone: '', email: '', entreprise: '' })">
        Nouveau client
      </v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>
    <v-text-field v-model="q" prepend-inner-icon="mdi-magnify" label="Rechercher (nom, téléphone, e-mail, pièce, entreprise)" clearable class="mb-4" hide-details />
    <v-card class="classroom-card">
      <v-data-table :items="clients" :loading="chargement" items-per-page="25" density="comfortable" no-data-text="Aucun client."
        :headers="[
          { title: 'Client', key: 'nom' }, { title: 'Téléphone', key: 'telephone' }, { title: 'E-mail', key: 'email' },
          { title: 'Entreprise', key: 'entreprise' }, { title: 'Séjours', key: 'sejours', align: 'end' }, { title: 'M-FINAPP', key: 'finappClientId', align: 'center' },
        ]" @click:row="(_: unknown, { item }: any) => navigateTo(`/clients/${item.id}`)">
        <template #[`item.nom`]="{ item }"><strong>{{ item.prenom ? item.prenom + ' ' : '' }}{{ item.nom }}</strong></template>
        <template #[`item.finappClientId`]="{ item }"><v-icon v-if="item.finappClientId" icon="mdi-link-variant" size="18" color="primary" /></template>
      </v-data-table>
    </v-card>

    <v-dialog v-model="nouveau.ouvert" max-width="520">
      <v-card class="pa-2">
        <v-card-title class="font-weight-bold">Nouveau client</v-card-title>
        <v-card-text>
          <v-row dense>
            <v-col cols="12" sm="6"><v-text-field v-model="nouveau.nom" label="Nom *" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nouveau.prenom" label="Prénom" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nouveau.telephone" label="Téléphone" /></v-col>
            <v-col cols="12" sm="6"><v-text-field v-model="nouveau.email" label="E-mail" /></v-col>
            <v-col cols="12"><v-text-field v-model="nouveau.entreprise" label="Entreprise (séjours facturés)" /></v-col>
          </v-row>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn variant="text" @click="nouveau.ouvert = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" :disabled="!nouveau.nom.trim()" @click="creer">Créer</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
