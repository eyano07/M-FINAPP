<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { DUREES, STATUTS, dateHeure, usd } from '~/utils/libelles'

useHead({ title: 'Réservations' })

interface Resa { id: number, reference: string, etablissementNom: string, uniteNumero: string, typeNom: string, clientNom: string,
  clientTelephone: string | null, debut: string, fin: string, uniteDuree: string, prixUnitaire: number, statut: string,
  canal: string, encaissement: string | null, venteReference: string | null }

const api = useApi()
const session = useSessionStore()
const route = useRoute()
const resas = ref<Resa[]>([])
const chargement = ref(false)
const erreur = ref('')
const q = ref(typeof route.query.q === 'string' ? route.query.q : '')
const statut = ref<string | null>(null)
const periode = ref<'avenir' | 'present' | 'toutes'>(route.query.q ? 'toutes' : 'avenir')
const nouvelle = ref(route.query.nouvelle === '1')

async function charger() {
  chargement.value = true
  try {
    const maintenant = new Date()
    const query: Record<string, string | number | undefined> = { q: q.value || undefined, statut: statut.value ?? undefined,
      etablissementId: useEtablissementStore().filtre }
    if (periode.value === 'avenir') query.du = new Date(maintenant.getTime() - 86_400_000).toISOString()
    if (periode.value === 'present') {
      query.du = maintenant.toISOString()
      query.au = new Date(maintenant.getTime() + 1000).toISOString()
    }
    resas.value = await api<Resa[]>('/reservations', { query })
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, 'Réservations indisponibles.')
  } finally {
    chargement.value = false
  }
}
onMounted(charger)
let attente: ReturnType<typeof setTimeout> | null = null
watch([q, statut, periode], () => { if (attente) clearTimeout(attente); attente = setTimeout(charger, 250) })
// Recherche lancée depuis la barre du haut alors que la page est déjà ouverte.
watch(() => route.query.q, v => { if (typeof v === 'string' && v) { q.value = v; periode.value = 'toutes' } })

const entetes = [
  { title: 'Référence', key: 'reference' },
  { title: 'Client', key: 'clientNom' },
  { title: 'Unité', key: 'uniteNumero' },
  { title: 'Arrivée', key: 'debut' },
  { title: 'Départ', key: 'fin' },
  { title: 'Durée', key: 'uniteDuree', sortable: false },
  { title: 'Prix', key: 'prixUnitaire', align: 'end' as const },
  { title: 'Statut', key: 'statut' },
]
const libDuree = (d: string) => DUREES.find(x => x.value === d)?.title ?? d
</script>

<template>
  <div class="d-apparait">
    <div class="page-head">
      <div>
        <div class="page-eyebrow">Exploitation</div>
        <h1 class="page-title">Réservations</h1>
        <p class="page-sub">Toutes les réservations et séjours de vos établissements</p>
      </div>
      <v-btn v-if="session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN')" color="primary" prepend-icon="mdi-plus" @click="nouvelle = true">
        Nouvelle réservation
      </v-btn>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <div class="d-flex flex-wrap ga-3 mb-4">
      <v-text-field v-model="q" prepend-inner-icon="mdi-magnify" label="Rechercher (référence, client, unité)" hide-details clearable
        style="min-width: 260px; flex: 1" />
      <v-select v-model="statut" :items="[{ value: null, title: 'Tous les statuts' }, ...Object.entries(STATUTS).map(([value, s]) => ({ value, title: s.libelle }))]"
        label="Statut" hide-details style="max-width: 220px" />
      <v-btn-toggle v-model="periode" mandatory variant="outlined" divided density="comfortable">
        <v-btn value="avenir">À venir</v-btn><v-btn value="present">Présents</v-btn><v-btn value="toutes">Toutes</v-btn>
      </v-btn-toggle>
    </div>

    <v-card class="classroom-card">
      <v-data-table :headers="entetes" :items="resas" :loading="chargement" items-per-page="25" density="comfortable"
        no-data-text="Aucune réservation." @click:row="(_: unknown, { item }: any) => navigateTo(`/reservations/${item.id}`)">
        <template #[`item.clientNom`]="{ item }">
          <div class="font-weight-medium">{{ item.clientNom }}</div>
          <div class="text-caption text-medium-emphasis">{{ item.clientTelephone }}</div>
        </template>
        <template #[`item.uniteNumero`]="{ item }">{{ item.uniteNumero }} <span class="text-caption text-medium-emphasis">{{ item.typeNom }}</span></template>
        <template #[`item.debut`]="{ item }">{{ dateHeure(item.debut) }}</template>
        <template #[`item.fin`]="{ item }">{{ dateHeure(item.fin) }}</template>
        <template #[`item.uniteDuree`]="{ item }">{{ libDuree(item.uniteDuree) }}</template>
        <template #[`item.prixUnitaire`]="{ item }">{{ usd(item.prixUnitaire) }}</template>
        <template #[`item.statut`]="{ item }">
          <span class="pastille" :style="{ '--c': STATUTS[item.statut]?.couleur }">{{ STATUTS[item.statut]?.libelle }}</span>
          <v-chip v-if="item.encaissement === 'EN_ATTENTE'" size="x-small" color="warning" variant="tonal" class="ml-1">à encaisser</v-chip>
        </template>
      </v-data-table>
    </v-card>

    <ReservationNouvelle v-model="nouvelle" @creee="(r: any) => navigateTo(`/reservations/${r.id}`)" />
  </div>
</template>
