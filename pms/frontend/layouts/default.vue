<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useSessionStore } from '~/stores/session'
import { useEtablissementStore } from '~/stores/etablissement'

const session = useSessionStore()
const etablissements = useEtablissementStore()
onMounted(() => { if (!etablissements.charge) etablissements.charger() })
const { mobile } = useDisplay()
const menu = ref(!mobile.value)
const { mode, effectif, choisir } = useModeAffichage()
const route = useRoute()

interface Entree { titre: string, icone: string, vers: string, roles?: string[] }
const TOUS = ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'GOUVERNANTE', 'TECHNICIEN', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE']
const EXPLOITATION = ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE']
const ENTREES: Entree[] = [
  { titre: 'Tableau de bord', icone: 'mdi-view-dashboard-outline', vers: '/', roles: TOUS },
  { titre: 'Calendrier', icone: 'mdi-calendar-month-outline', vers: '/calendrier', roles: EXPLOITATION },
  { titre: 'Réservations', icone: 'mdi-book-open-page-variant-outline', vers: '/reservations', roles: EXPLOITATION },
  { titre: 'Clients', icone: 'mdi-account-group-outline', vers: '/clients', roles: ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'] },
  { titre: 'Ménage', icone: 'mdi-broom', vers: '/menage', roles: TOUS },
  { titre: 'Canaux de vente', icone: 'mdi-calendar-sync-outline', vers: '/canaux', roles: EXPLOITATION },
  { titre: 'Messages aux clients', icone: 'mdi-email-fast-outline', vers: '/messages', roles: EXPLOITATION },
  { titre: 'Établissements et tarifs', icone: 'mdi-office-building-cog-outline', vers: '/parametrage', roles: ['DIR_HEBERGEMENT', 'ADMIN'] },
]
const entrees = computed(() => ENTREES.filter(e => !e.roles || session.a(...e.roles)))
const titre = computed(() => ENTREES.find(e => e.vers === route.path)?.titre ?? '')

function deconnecter() {
  session.fermer()
  navigateTo('/login')
}
</script>

<template>
  <v-app>
    <v-navigation-drawer v-model="menu" class="modern-drawer" width="264">
      <div class="marque">
        <span class="marque__logo logo-principal"><LogoMfinapp /></span>
        <div>
          <div class="marque__nom">M-FINAPP</div>
          <div class="marque__sous">Hôtellerie</div>
        </div>
      </div>
      <v-list nav density="comfortable" class="px-2">
        <v-list-item v-for="e in entrees" :key="e.vers" :to="e.vers" :prepend-icon="e.icone" :title="e.titre"
          rounded="lg" exact @click="mobile && (menu = false)" />
      </v-list>
      <template #append>
        <v-list nav density="compact" class="px-2">
          <v-list-item prepend-icon="mdi-web" title="Page de réservation en ligne" href="/reserver" target="_blank" rounded="lg" />
        </v-list>
        <div class="pied">M-FINAPP Hôtellerie · version {{ useRuntimeConfig().public.appVersion }}</div>
      </template>
    </v-navigation-drawer>

    <v-app-bar class="modern-appbar" height="60" elevation="0">
      <v-app-bar-nav-icon aria-label="Menu" @click="menu = !menu" />
      <span class="barre__titre">{{ titre }}</span>
      <v-spacer />
      <v-btn v-if="etablissements.liste.length > 1" variant="tonal" size="small" class="barre__etab" prepend-icon="mdi-domain"
        :to="{ path: '/choisir-etablissement', query: { changer: '1', suite: route.fullPath } }" aria-label="Changer d'établissement">
        <span class="barre__etab-nom">{{ etablissements.nom }}</span><v-icon icon="mdi-swap-horizontal" size="16" class="ml-1" />
      </v-btn>
      <v-menu location="bottom end">
        <template #activator="{ props }">
          <v-btn icon variant="text" size="small" v-bind="props" :aria-label="`Affichage : ${effectif === 'sombre' ? 'nuit' : 'clair'}`">
            <v-icon :icon="effectif === 'sombre' ? 'mdi-weather-night' : 'mdi-white-balance-sunny'" size="20" />
          </v-btn>
        </template>
        <v-list density="compact">
          <v-list-item prepend-icon="mdi-white-balance-sunny" title="Clair" :active="mode === 'clair'" @click="choisir('clair')" />
          <v-list-item prepend-icon="mdi-weather-night" title="Nuit" :active="mode === 'sombre'" @click="choisir('sombre')" />
          <v-list-item prepend-icon="mdi-theme-light-dark" title="Système" :active="mode === 'systeme'" @click="choisir('systeme')" />
        </v-list>
      </v-menu>
      <v-menu location="bottom end">
        <template #activator="{ props }">
          <v-btn icon variant="text" v-bind="props" aria-label="Mon compte">
            <v-avatar size="34" color="primary"><span class="text-caption font-weight-bold">{{ session.initiales }}</span></v-avatar>
          </v-btn>
        </template>
        <v-card min-width="260" class="pa-2">
          <v-card-text class="pb-2">
            <div class="font-weight-bold">{{ session.nomComplet }}</div>
            <div class="text-caption text-medium-emphasis">{{ session.utilisateur?.email }}</div>
            <div class="text-caption mt-1">{{ session.utilisateur?.roles.join(' · ') }}</div>
          </v-card-text>
          <v-list density="compact">
            <v-list-item prepend-icon="mdi-key-chain-variant" title="Clé d'appairage" to="/premier-lancement"
              v-if="session.a('ADMIN', 'DIR_HEBERGEMENT')" />
            <v-list-item prepend-icon="mdi-logout" title="Se déconnecter" @click="deconnecter" />
          </v-list>
        </v-card>
      </v-menu>
    </v-app-bar>

    <v-main style="background: var(--canvas);">
      <v-container fluid class="pa-3 pa-sm-4 pa-md-6">
        <slot />
      </v-container>
    </v-main>
  </v-app>
</template>

<style scoped>
.marque { display: flex; align-items: center; gap: 12px; padding: 20px 18px 14px; }
.marque__logo { width: 38px; height: 38px; border-radius: 10px; display: inline-flex; padding: 6px; }
.marque__nom { font-weight: 800; letter-spacing: 0.3px; }
.marque__sous { font-size: 0.78rem; opacity: 0.75; }
.pied { padding: 14px 18px; font-size: 0.72rem; opacity: 0.6; }
.barre__titre { font-weight: 700; font-size: 1.02rem; margin-left: 4px; }
.barre__etab { text-transform: none; letter-spacing: 0; margin-right: 6px; color: var(--color-primary) !important; max-width: 46vw; }
.barre__etab-nom { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
