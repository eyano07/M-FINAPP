<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { dateHeure } from '~/utils/libelles'

/**
 * Messages automatiques aux clients, par e-mail (messagerie de M-FINAPP) : confirmation, rappel la veille de l'arrivée,
 * code d'accès à l'arrivée, remerciement après le départ. Un message par séjour et par type ; les clients sans e-mail
 * n'en reçoivent pas.
 */
useHead({ title: 'Messages aux clients' })

interface Modele { type: string, actif: boolean, sujet: string, corps: string, majLe: string | null, majPar: string | null }
interface Envoi { id: number, reservationId: number, reference: string, type: string, destinataire: string, statut: string, detail: string | null, envoyeLe: string }

const TYPES: Record<string, { titre: string, quand: string, icone: string }> = {
  CONFIRMATION: { titre: 'Confirmation', quand: 'dès que la réservation est confirmée', icone: 'mdi-check-decagram-outline' },
  RAPPEL_ARRIVEE: { titre: "Rappel d'arrivée", quand: "la veille de l'arrivée", icone: 'mdi-bell-ring-outline' },
  CODE_ACCES: { titre: "Code d'accès", quand: "à l'arrivée, si un code ou une clé est saisi", icone: 'mdi-key-outline' },
  REMERCIEMENT: { titre: 'Remerciement', quand: 'après le départ', icone: 'mdi-heart-outline' },
}
const api = useApi()
const session = useSessionStore()
const direction = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN'))
const modeles = ref<Modele[]>([])
const envois = ref<Envoi[]>([])
const erreur = ref('')
const succes = ref('')
const edition = reactive<Record<string, { sujet: string, corps: string, actif: boolean }>>({})

async function charger() {
  try {
    const [m, e] = await Promise.all([api<Modele[]>('/messages/modeles'), api<Envoi[]>('/messages/envois')])
    modeles.value = m
    envois.value = e
    for (const x of m) edition[x.type] = { sujet: x.sujet, corps: x.corps, actif: x.actif }
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
onMounted(charger)

async function enregistrer(type: string) {
  try {
    await api(`/messages/modeles/${type}`, { method: 'PUT', body: edition[type] })
    succes.value = `Modèle « ${TYPES[type].titre} » enregistré.`
    await charger()
  } catch (e) {
    erreur.value = messageErreur(e)
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Messages aux clients</h1>
        <p class="page-sub">E-mails automatiques envoyés par la messagerie de M-FINAPP</p>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" closable class="mb-4" @click:close="succes = ''">{{ succes }}</v-alert>
    <v-alert type="info" variant="tonal" density="compact" class="mb-4">
      Variables : <code>{client}</code> <code>{reference}</code> <code>{etablissement}</code> <code>{unite}</code> <code>{arrivee}</code>
      <code>{depart}</code> <code>{code}</code>. La messagerie se règle dans M-FINAPP (Administration › Messagerie) ; WhatsApp et SMS viendront
      quand un fournisseur sera choisi.
    </v-alert>

    <v-row>
      <v-col v-for="m in modeles" :key="m.type" cols="12" md="6">
        <v-card class="classroom-card pa-5">
          <div class="d-flex align-center mb-1">
            <v-icon :icon="TYPES[m.type]?.icone" class="mr-2" color="primary" />
            <strong>{{ TYPES[m.type]?.titre }}</strong>
            <v-spacer />
            <v-switch v-if="edition[m.type]" v-model="edition[m.type].actif" :disabled="!direction" color="primary" hide-details inset density="compact" />
          </div>
          <div class="text-caption text-medium-emphasis mb-3">Envoyé {{ TYPES[m.type]?.quand }}.</div>
          <template v-if="edition[m.type]">
            <v-text-field v-model="edition[m.type].sujet" label="Objet" :readonly="!direction" variant="outlined" density="comfortable" />
            <v-textarea v-model="edition[m.type].corps" label="Message" rows="4" auto-grow :readonly="!direction" variant="outlined" density="comfortable" />
          </template>
          <v-btn v-if="direction" color="primary" variant="tonal" @click="enregistrer(m.type)">Enregistrer</v-btn>
        </v-card>
      </v-col>
    </v-row>

    <v-card class="classroom-card mt-4">
      <v-card-title class="text-subtitle-1 font-weight-bold">Derniers envois</v-card-title>
      <v-data-table :items="envois" items-per-page="25" density="comfortable" no-data-text="Aucun message envoyé."
        :headers="[{ title: 'Date', key: 'envoyeLe' }, { title: 'Séjour', key: 'reference' }, { title: 'Message', key: 'type' },
                   { title: 'Destinataire', key: 'destinataire' }, { title: 'Statut', key: 'statut' }]"
        @click:row="(_: unknown, { item }: any) => navigateTo(`/reservations/${item.reservationId}`)">
        <template #[`item.envoyeLe`]="{ item }">{{ dateHeure(item.envoyeLe) }}</template>
        <template #[`item.type`]="{ item }">{{ TYPES[item.type]?.titre ?? item.type }}</template>
        <template #[`item.statut`]="{ item }">
          <v-chip size="small" variant="tonal" :color="item.statut === 'ENVOYE' ? 'success' : 'error'">{{ item.statut === 'ENVOYE' ? 'Envoyé' : 'Échec' }}</v-chip>
          <div v-if="item.detail" class="text-caption text-error">{{ item.detail }}</div>
        </template>
      </v-data-table>
    </v-card>
  </div>
</template>
