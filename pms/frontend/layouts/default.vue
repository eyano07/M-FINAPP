<script setup lang="ts">
import { useDisplay } from 'vuetify'
import { useSessionStore } from '~/stores/session'
import { useEtablissementStore } from '~/stores/etablissement'

/**
 * Cadre de l'application (design 2027) : panneau de navigation flottant (établissement de travail en tête, sections,
 * compte et mode d'affichage en pied), barre du haut translucide avec recherche rapide et action principale.
 */
const session = useSessionStore()
const etablissements = useEtablissementStore()
onMounted(() => { if (!etablissements.charge) etablissements.charger() })
const { mdAndUp } = useDisplay()
const menu = ref(true)
watch(mdAndUp, v => { menu.value = v }, { immediate: true })
const { mode, choisir } = useModeAffichage()
const route = useRoute()
// Lien Nuxt résolu une fois : les balises dynamiques (<component :is>) ne connaissent pas les composants par leur nom.
const Lien = resolveComponent('NuxtLink')

interface Entree { titre: string, icone: string, vers: string, roles?: string[], externe?: boolean }
interface Section { titre: string, entrees: Entree[] }
const TOUS = ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'GOUVERNANTE', 'TECHNICIEN', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE']
const EXPLOITATION = ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE']
const SECTIONS: Section[] = [
  { titre: 'Exploitation', entrees: [
    { titre: 'Tableau de bord', icone: 'mdi-view-dashboard-outline', vers: '/', roles: TOUS },
    { titre: 'Calendrier', icone: 'mdi-calendar-month-outline', vers: '/calendrier', roles: EXPLOITATION },
    { titre: 'Réservations', icone: 'mdi-book-open-page-variant-outline', vers: '/reservations', roles: EXPLOITATION },
    { titre: 'Clients', icone: 'mdi-account-group-outline', vers: '/clients', roles: ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'] },
    { titre: 'Motel', icone: 'mdi-car-estate', vers: '/motel', roles: ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'] },
    { titre: 'Baux', icone: 'mdi-file-sign', vers: '/baux', roles: ['RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'] },
  ] },
  { titre: 'Opérations', entrees: [
    { titre: 'Ménage', icone: 'mdi-broom', vers: '/menage', roles: TOUS },
    { titre: 'Maintenance', icone: 'mdi-wrench-outline', vers: '/maintenance', roles: TOUS },
    { titre: 'Mode terrain', icone: 'mdi-cellphone-check', vers: '/terrain', roles: ['GOUVERNANTE', 'TECHNICIEN', 'DIR_HEBERGEMENT', 'RECEPTIONNISTE', 'ADMIN'] },
  ] },
  { titre: 'Distribution', entrees: [
    { titre: 'Canaux de vente', icone: 'mdi-calendar-sync-outline', vers: '/canaux', roles: EXPLOITATION },
    { titre: 'Messages aux clients', icone: 'mdi-email-fast-outline', vers: '/messages', roles: EXPLOITATION },
    { titre: 'Réservation en ligne', icone: 'mdi-web', vers: '/reserver', roles: EXPLOITATION, externe: true },
  ] },
  { titre: 'Gestion', entrees: [
    { titre: 'Rapports', icone: 'mdi-chart-box-outline', vers: '/rapports', roles: ['DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE'] },
    { titre: 'Établissements et tarifs', icone: 'mdi-office-building-cog-outline', vers: '/parametrage', roles: ['DIR_HEBERGEMENT', 'ADMIN'] },
  ] },
]
const sections = computed(() => SECTIONS
  .map(s => ({ ...s, entrees: s.entrees.filter(e => !e.roles || session.a(...e.roles)) }))
  .filter(s => s.entrees.length))
const toutes = SECTIONS.flatMap(s => s.entrees)
const titre = computed(() => toutes.find(e => e.vers === route.path)?.titre
  ?? toutes.find(e => e.vers !== '/' && route.path.startsWith(e.vers))?.titre ?? '')
const actif = (e: Entree) => e.vers === '/' ? route.path === '/' : route.path.startsWith(e.vers)

const ICONES_ETAB: Record<string, string> = { HOTEL: 'mdi-office-building', MOTEL: 'mdi-car-estate', APPARTEMENTS: 'mdi-home-city-outline', MIXTE: 'mdi-domain' }
const TYPES_ETAB: Record<string, string> = { HOTEL: 'Hôtel', MOTEL: 'Motel', APPARTEMENTS: 'Appartements', MIXTE: 'Hôtel et appartements' }
const etab = computed(() => etablissements.courant)
const peutChanger = computed(() => etablissements.liste.length > 1)

const ROLES: Record<string, string> = {
  RECEPTIONNISTE: 'Réception', DIR_HEBERGEMENT: "Direction de l'hébergement", GOUVERNANTE: 'Gouvernante', TECHNICIEN: 'Maintenance',
  ADMIN: 'Administration', DG: 'Direction générale', DFIN: 'Direction financière', DA: 'Direction administrative', COMPTABLE: 'Comptabilité',
}
const role = computed(() => (session.utilisateur?.roles ?? []).map(r => ROLES[r]).filter(Boolean)[0] ?? 'Hôtellerie')
const peutReserver = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))

const recherche = ref('')
function chercher() {
  const q = recherche.value.trim()
  if (!q) return
  recherche.value = ''
  navigateTo({ path: '/reservations', query: { q } })
}
const champRecherche = ref<HTMLInputElement | null>(null)
function raccourci(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); champRecherche.value?.focus() }
}
onMounted(() => window.addEventListener('keydown', raccourci))
onBeforeUnmount(() => window.removeEventListener('keydown', raccourci))

function deconnecter() {
  session.fermer()
  navigateTo('/login')
}
</script>

<template>
  <v-app>
    <v-navigation-drawer v-model="menu" class="d-nav" width="284" :permanent="mdAndUp" :temporary="!mdAndUp" floating>
      <div class="d-nav__panneau">
        <NuxtLink to="/" class="d-nav__marque">
          <span class="d-nav__logo"><LogoMfinapp /></span>
          <span>
            <span class="d-nav__nom">M-FINAPP</span>
            <span class="d-nav__produit">Hôtellerie</span>
          </span>
        </NuxtLink>

        <component :is="peutChanger ? Lien : 'div'" class="d-etab" :class="{ 'd-etab--lien': peutChanger }"
          :to="peutChanger ? { path: '/choisir-etablissement', query: { changer: '1', suite: route.fullPath } } : undefined">
          <span class="d-etab__icone"><v-icon :icon="etab ? (ICONES_ETAB[etab.type] ?? 'mdi-domain') : 'mdi-view-grid-outline'" size="20" /></span>
          <span class="d-etab__texte">
            <span class="d-etab__nom">{{ !etablissements.charge ? 'Chargement…' : etablissements.liste.length ? etablissements.nom : 'Aucun établissement' }}</span>
            <span v-if="etablissements.charge" class="d-etab__type">{{ etab ? `${TYPES_ETAB[etab.type] ?? etab.type} · ${etab.unites} unité${etab.unites > 1 ? 's' : ''}` : etablissements.liste.length ? `${etablissements.liste.length} établissements` : 'À créer dans Gestion' }}</span>
          </span>
          <v-icon v-if="peutChanger" icon="mdi-unfold-more-horizontal" size="18" class="d-etab__chevron" />
        </component>

        <nav class="d-nav__liens">
          <div v-for="s in sections" :key="s.titre" class="d-nav__section">
            <div class="d-nav__titre-section">{{ s.titre }}</div>
            <component :is="e.externe ? 'a' : Lien" v-for="e in s.entrees" :key="e.vers" class="d-lien" :class="{ 'd-lien--actif': !e.externe && actif(e) }"
              v-bind="e.externe ? { href: e.vers, target: '_blank', rel: 'noopener' } : { to: e.vers }" @click="!mdAndUp && (menu = false)">
              <span class="d-lien__icone"><v-icon :icon="e.icone" size="19" /></span>
              <span class="d-lien__titre">{{ e.titre }}</span>
              <v-icon v-if="e.externe" icon="mdi-arrow-top-right" size="14" class="d-lien__externe" />
            </component>
          </div>
        </nav>

        <div class="d-compte">
          <div class="d-compte__ligne">
            <span class="d-compte__avatar">{{ session.initiales }}</span>
            <span class="d-compte__texte">
              <span class="d-compte__nom">{{ session.nomComplet }}</span>
              <span class="d-compte__role">{{ role }}</span>
            </span>
            <v-menu location="top end">
              <template #activator="{ props }">
                <v-btn icon="mdi-dots-horizontal" variant="text" size="small" v-bind="props" aria-label="Mon compte" />
              </template>
              <v-list density="compact" min-width="230">
                <v-list-item :subtitle="session.utilisateur?.email" :title="session.nomComplet" />
                <v-divider class="my-1" />
                <v-list-item v-if="session.a('ADMIN', 'DIR_HEBERGEMENT')" prepend-icon="mdi-key-chain-variant" title="Clé d'appairage" to="/premier-lancement" />
                <v-list-item prepend-icon="mdi-logout" title="Se déconnecter" @click="deconnecter" />
              </v-list>
            </v-menu>
          </div>
          <div class="d-mode" role="radiogroup" aria-label="Affichage">
            <button v-for="m in ([{ v: 'clair', i: 'mdi-white-balance-sunny', t: 'Clair' }, { v: 'sombre', i: 'mdi-weather-night', t: 'Nuit' }, { v: 'systeme', i: 'mdi-laptop', t: 'Auto' }] as const)"
              :key="m.v" type="button" role="radio" :aria-checked="mode === m.v" class="d-mode__choix" :class="{ 'd-mode__choix--actif': mode === m.v }" @click="choisir(m.v)">
              <v-icon :icon="m.i" size="15" /><span>{{ m.t }}</span>
            </button>
          </div>
          <div class="d-nav__pied">Hôtellerie · version {{ useRuntimeConfig().public.appVersion }}</div>
        </div>
      </div>
    </v-navigation-drawer>

    <v-app-bar class="d-barre" height="68" elevation="0" flat>
      <v-btn v-if="!mdAndUp" icon="mdi-menu" variant="text" aria-label="Menu" @click="menu = !menu" />
      <div class="d-barre__fil">
        <span class="d-barre__racine">Hôtellerie</span>
        <v-icon icon="mdi-chevron-right" size="16" class="d-barre__sep" />
        <span class="d-barre__titre">{{ titre }}</span>
      </div>
      <v-spacer />
      <form v-if="session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE')" class="d-recherche" role="search" @submit.prevent="chercher">
        <v-icon icon="mdi-magnify" size="18" />
        <input ref="champRecherche" v-model="recherche" type="search" placeholder="Réservation, client, unité…" aria-label="Rechercher une réservation">
        <kbd class="d-recherche__kbd">Ctrl K</kbd>
      </form>
      <v-btn v-if="peutReserver" color="primary" variant="flat" class="d-barre__action" prepend-icon="mdi-plus" to="/reservations?nouvelle=1">
        <span class="d-barre__action-texte">Réservation</span>
      </v-btn>
    </v-app-bar>

    <v-main>
      <div class="d-contenu">
        <slot />
      </div>
    </v-main>
  </v-app>
</template>

<style scoped>
/* ── Panneau de navigation flottant ─────────────────────────────────────────────────────────────────────────── */
.d-nav { background: transparent !important; border: 0 !important; box-shadow: none !important; }
.d-nav :deep(.v-navigation-drawer__content) { overflow: visible; }
.d-nav__panneau {
  margin: 12px 0 12px 12px; height: calc(100% - 24px); display: flex; flex-direction: column; overflow: hidden;
  border-radius: 24px; background: color-mix(in srgb, var(--d-surface) 88%, transparent);
  backdrop-filter: saturate(160%) blur(18px); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-2);
}
.d-nav__marque { display: flex; align-items: center; gap: 12px; padding: 20px 20px 16px; text-decoration: none; color: inherit; }
.d-nav__logo {
  width: 40px; height: 40px; border-radius: 13px; padding: 8px; display: grid; place-items: center; color: #fff;
  background: var(--d-grad); box-shadow: 0 8px 20px -10px color-mix(in srgb, var(--color-primary) 90%, transparent);
}
.d-nav__nom { display: block; font-weight: 800; letter-spacing: -0.02em; font-size: 1.02rem; line-height: 1.1; }
.d-nav__produit { display: block; font-size: 0.78rem; color: var(--d-ink-faint); font-weight: 600; }

.d-etab {
  display: flex; align-items: center; gap: 12px; margin: 0 14px 10px; padding: 10px 12px; border-radius: 16px;
  text-decoration: none; color: inherit; background: var(--d-surface-2); border: 1px solid var(--d-line);
  transition: border-color 0.18s, background 0.18s;
}
.d-etab--lien:hover { border-color: color-mix(in srgb, var(--color-primary) 35%, transparent); background: color-mix(in srgb, var(--color-primary) 5%, var(--d-surface)); }
.d-etab__icone {
  width: 36px; height: 36px; border-radius: 11px; flex: none; display: grid; place-items: center;
  color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 12%, var(--d-surface));
}
.d-etab__texte { min-width: 0; flex: 1; }
.d-etab__nom { display: block; font-weight: 700; font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-etab__type { display: block; font-size: 0.74rem; color: var(--d-ink-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-etab__chevron { color: var(--d-ink-faint); }

.d-nav__liens { flex: 1; overflow-y: auto; padding: 4px 10px 10px; }
.d-nav__section + .d-nav__section { margin-top: 10px; }
.d-nav__titre-section {
  padding: 10px 12px 6px; font-size: 0.68rem; font-weight: 700; letter-spacing: 0.1em; text-transform: uppercase; color: var(--d-ink-faint);
}
.d-lien {
  position: relative; display: flex; align-items: center; gap: 12px; height: 42px; padding: 0 10px; border-radius: 12px;
  text-decoration: none; color: var(--d-ink-2); font-size: 0.9rem; font-weight: 560; transition: background 0.16s, color 0.16s;
}
.d-lien:hover { background: var(--d-surface-3); color: var(--d-ink); }
.d-lien__icone { width: 30px; height: 30px; border-radius: 9px; display: grid; place-items: center; color: var(--d-ink-soft); transition: all 0.2s; }
.d-lien__titre { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-lien__externe { color: var(--d-ink-faint); }
.d-lien--actif { color: var(--d-ink); font-weight: 680; background: color-mix(in srgb, var(--color-primary) 9%, var(--d-surface)); }
.d-lien--actif .d-lien__icone { color: #fff; background: var(--d-grad); box-shadow: 0 6px 14px -7px color-mix(in srgb, var(--color-primary) 90%, transparent); }
.d-lien--actif::after {
  content: ''; position: absolute; right: 10px; width: 6px; height: 6px; border-radius: 50%; background: var(--color-primary);
}

.d-compte { padding: 12px 14px 14px; border-top: 1px solid var(--d-line); }
.d-compte__ligne { display: flex; align-items: center; gap: 10px; }
.d-compte__avatar {
  width: 38px; height: 38px; border-radius: 50%; flex: none; display: grid; place-items: center; font-size: 0.78rem; font-weight: 800;
  color: #fff; background: var(--d-grad); box-shadow: 0 0 0 3px var(--d-surface), 0 0 0 4px color-mix(in srgb, var(--color-primary) 25%, transparent);
}
.d-compte__texte { min-width: 0; flex: 1; }
.d-compte__nom { display: block; font-weight: 700; font-size: 0.86rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-compte__role { display: block; font-size: 0.74rem; color: var(--d-ink-faint); }
.d-mode { display: grid; grid-template-columns: repeat(3, 1fr); gap: 3px; margin-top: 12px; padding: 3px; border-radius: 12px; background: var(--d-surface-3); }
.d-mode__choix {
  display: inline-flex; align-items: center; justify-content: center; gap: 5px; height: 30px; border-radius: 9px; border: 0; cursor: pointer;
  font: inherit; font-size: 0.76rem; font-weight: 600; color: var(--d-ink-soft); background: transparent; transition: all 0.16s;
}
.d-mode__choix--actif { color: var(--d-ink); background: var(--d-surface); box-shadow: var(--d-shadow-1); }
.d-nav__pied { margin-top: 10px; font-size: 0.68rem; color: var(--d-ink-faint); text-align: center; }

/* ── Barre du haut translucide ──────────────────────────────────────────────────────────────────────────────── */
.d-barre {
  background: color-mix(in srgb, var(--d-canvas) 72%, transparent) !important;
  backdrop-filter: saturate(170%) blur(16px); border-bottom: 1px solid transparent !important; padding: 0 12px 0 8px;
}
.d-barre__fil { display: flex; align-items: center; gap: 4px; margin-left: 12px; min-width: 0; }
.d-barre__racine { font-size: 0.86rem; color: var(--d-ink-faint); font-weight: 560; }
.d-barre__sep { color: var(--d-ink-faint); }
.d-barre__titre { font-size: 0.92rem; font-weight: 700; letter-spacing: -0.01em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.d-recherche {
  display: flex; align-items: center; gap: 8px; width: min(340px, 34vw); height: 40px; padding: 0 8px 0 14px; margin-right: 10px;
  border-radius: 12px; background: var(--d-surface); border: 1px solid var(--d-line); box-shadow: var(--d-shadow-1); color: var(--d-ink-faint);
  transition: border-color 0.18s, box-shadow 0.18s;
}
.d-recherche:focus-within { border-color: color-mix(in srgb, var(--color-primary) 50%, transparent); box-shadow: 0 0 0 4px color-mix(in srgb, var(--color-primary) 12%, transparent); }
.d-recherche input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; font: inherit; font-size: 0.88rem; color: var(--d-ink); }
.d-recherche input::placeholder { color: var(--d-ink-faint); }
.d-recherche__kbd {
  font: inherit; font-size: 0.66rem; font-weight: 700; padding: 3px 6px; border-radius: 6px; color: var(--d-ink-faint);
  background: var(--d-surface-3); border: 1px solid var(--d-line);
}
.d-barre__action { height: 40px !important; }

/* ── Contenu ───────────────────────────────────────────────────────────────────────────────────────────────── */
.d-contenu { max-width: 1480px; margin: 0 auto; padding: 18px 28px 48px; }

@media (max-width: 959px) {
  .d-nav__panneau { margin: 10px; height: calc(100% - 20px); }
  .d-barre { padding: 0 6px; }
  .d-barre__fil { margin-left: 2px; }
  .d-barre__racine, .d-barre__sep { display: none; }
  .d-recherche { display: none; }
  .d-contenu { padding: 12px 14px 40px; }
}
@media (max-width: 599px) {
  .d-barre__action-texte { display: none; }
  .d-barre__action :deep(.v-btn__prepend) { margin: 0; }
}
</style>
