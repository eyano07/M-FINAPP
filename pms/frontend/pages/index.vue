<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { STATUTS, dateHeure } from '~/utils/libelles'

useHead({ title: 'Tableau de bord' })

interface Ligne { id: number, reference: string, debut: string, fin: string, statut: string, encaissement: string | null, unite: string, client: string }
interface Tableau {
  unitesDisponibles: number, unitesOccupees: number, tauxOccupation: number, presents: number, unitesANettoyer: number,
  unitesHorsService: number, arriveesDuJour: Ligne[], departsDuJour: Ligne[], encaissementsEnAttente: Ligne[],
  demandesEnLigne?: Ligne[], alertesCanaux?: number
}

const api = useApi()
const session = useSessionStore()
const t = ref<Tableau | null>(null)
const erreur = ref('')
let minuterie: ReturnType<typeof setInterval> | null = null

async function charger() {
  try {
    t.value = await api<Tableau>('/tableau-de-bord', { query: { etablissementId: useEtablissementStore().filtre } })
    erreur.value = ''
  } catch (e) {
    erreur.value = messageErreur(e, 'Tableau de bord indisponible.')
  }
}
onMounted(() => {
  charger()
  minuterie = setInterval(charger, 30_000)
})
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

const bonjour = computed(() => {
  const h = new Date().getHours()
  return `${h < 18 ? 'Bonjour' : 'Bonsoir'} ${session.utilisateur?.prenom ?? ''}`.trim()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ bonjour }}</h1>
        <p class="page-sub">{{ new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) }}</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn v-if="session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN')" color="primary" prepend-icon="mdi-plus" to="/reservations?nouvelle=1">
          Nouvelle réservation
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4">{{ erreur }}</v-alert>

    <v-row v-if="t" class="mb-2">
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Occupation</div>
          <div class="kpi-value">{{ t.tauxOccupation }} %</div>
          <div class="text-caption text-medium-emphasis">{{ t.unitesOccupees }} / {{ t.unitesDisponibles }} unités</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Clients présents</div>
          <div class="kpi-value">{{ t.presents }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4" to="/menage">
          <div class="kpi-label">À nettoyer</div>
          <div class="kpi-value">{{ t.unitesANettoyer }}</div>
        </v-card>
      </v-col>
      <v-col cols="6" md="3">
        <v-card class="classroom-card pa-4">
          <div class="kpi-label">Hors service</div>
          <div class="kpi-value">{{ t.unitesHorsService }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-alert v-if="t?.demandesEnLigne?.length" type="info" variant="tonal" class="mb-4">
      <strong>{{ t.demandesEnLigne.length }} demande(s) en ligne à confirmer</strong> (libérées après 48 h sans confirmation) :
      <span v-for="l in t.demandesEnLigne" :key="l.id" class="ml-2"><NuxtLink :to="`/reservations/${l.id}`">{{ l.reference }} · {{ l.client }}</NuxtLink></span>
    </v-alert>
    <v-alert v-if="t?.alertesCanaux" type="error" variant="tonal" class="mb-4">
      <strong>{{ t.alertesCanaux }} conflit(s) avec les canaux de vente</strong> : une réservation reçue tombe sur des dates déjà occupées.
      <NuxtLink to="/canaux" class="ml-1">Voir les canaux</NuxtLink>
    </v-alert>
    <v-alert v-if="t?.encaissementsEnAttente.length" type="warning" variant="tonal" class="mb-4">
      <strong>{{ t.encaissementsEnAttente.length }} départ(s) à encaisser</strong> : M-FINAPP était injoignable au départ.
      <span v-for="l in t.encaissementsEnAttente" :key="l.id" class="ml-2">
        <NuxtLink :to="`/reservations/${l.id}`">{{ l.reference }}</NuxtLink>
      </span>
    </v-alert>

    <v-row v-if="t">
      <v-col v-for="bloc in [
        { titre: 'Arrivées du jour', icone: 'mdi-login-variant', lignes: t.arriveesDuJour, heure: 'debut' },
        { titre: 'Départs du jour', icone: 'mdi-logout-variant', lignes: t.departsDuJour, heure: 'fin' },
      ]" :key="bloc.titre" cols="12" md="6">
        <v-card class="classroom-card">
          <v-card-title class="d-flex align-center ga-2 text-subtitle-1 font-weight-bold">
            <v-icon :icon="bloc.icone" size="20" />{{ bloc.titre }}
            <v-spacer /><v-chip size="small" variant="tonal">{{ bloc.lignes.length }}</v-chip>
          </v-card-title>
          <v-list v-if="bloc.lignes.length" density="comfortable">
            <v-list-item v-for="l in bloc.lignes" :key="l.id" :to="`/reservations/${l.id}`">
              <template #prepend><v-avatar size="38" color="primary" variant="tonal"><strong>{{ l.unite }}</strong></v-avatar></template>
              <v-list-item-title>{{ l.client }}</v-list-item-title>
              <v-list-item-subtitle>{{ l.reference }} · {{ dateHeure((l as any)[bloc.heure]) }}</v-list-item-subtitle>
              <template #append>
                <span class="pastille" :style="{ '--c': STATUTS[l.statut]?.couleur }">{{ STATUTS[l.statut]?.libelle }}</span>
              </template>
            </v-list-item>
          </v-list>
          <v-card-text v-else class="text-medium-emphasis">Rien de prévu.</v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>
