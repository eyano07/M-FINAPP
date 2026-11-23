<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useNotificationsStore, type NotificationItem } from '~/stores/notifications'
import { useParametresStore } from '~/stores/parametres'
import { useInstanceStore } from '~/stores/instance'
import { useExerciceStore, type Exercice } from '~/stores/exercice'
import { estApplicationMobile } from '~/utils/applicationMobile'

defineProps<{ drawerOpen: boolean }>()

// Application Android : le menu du compte n'y propose pas le papier à en-tête.
const applicationMobile = estApplicationMobile()

// Mode d'affichage : clair, nuit ou celui du système (mémorisé sur ce navigateur).
const { mode: modeAffichage, effectif: modeEffectif, choisir: choisirMode } = useModeAffichage()
const MODES_AFFICHAGE = [
  { valeur: 'clair', libelle: 'Clair', icone: 'mdi-white-balance-sunny' },
  { valeur: 'sombre', libelle: 'Nuit', icone: 'mdi-weather-night' },
  { valeur: 'systeme', libelle: 'Comme le système', icone: 'mdi-monitor' },
] as const
const emit = defineEmits<{ (e: 'toggle-drawer'): void }>()

const auth = useAuthStore()
const { logout } = useAuth()
const notif = useNotificationsStore()
const parametresStore = useParametresStore()

// Exercice consulté (19/11/2026) : liste déroulante du site (pas dans l'application Android). Un exercice clôturé se
// consulte en lecture seule ; la page en cours est reconstruite avec les bornes du nouvel exercice (layouts/default.vue).
const exercice = useExerciceStore()
const STATUTS_EXERCICE: Record<Exercice['statut'], { libelle: string, icone: string, couleur: string }> = {
  CLOTURE: { libelle: 'Clôturé · lecture seule', icone: 'mdi-lock-outline', couleur: 'deep-purple' },
  A_CLOTURER: { libelle: 'À clôturer', icone: 'mdi-calendar-alert', couleur: 'warning' },
  EN_COURS: { libelle: 'En cours', icone: 'mdi-calendar-check', couleur: 'success' },
  A_VENIR: { libelle: 'À venir', icone: 'mdi-calendar-arrow-right', couleur: 'info' },
}
const instance = useInstanceStore()

/** Serveur local : état de la synchronisation avec le serveur en ligne (pastille de la barre). */
const synchro = computed(() => {
  if (!instance.estLocal) return null
  const depuis = instance.derniereSynchro ? tempsEcoule(instance.derniereSynchro) : null
  if (instance.horsConnexion) {
    return { icone: 'mdi-cloud-off-outline', couleur: '#d97706', probleme: true,
      texte: `Hors connexion avec le serveur en ligne${instance.enAttente ? ` — ${instance.enAttente} changement(s) en attente` : ''}. `
        + 'Vous pouvez continuer à travailler : tout partira au retour de la connexion.' }
  }
  return { icone: 'mdi-cloud-check-outline', couleur: '#16a34a', probleme: instance.enAttente > 200,
    texte: `Synchronisé avec le serveur en ligne${depuis ? ` (${depuis})` : ''}${instance.enAttente ? ` — ${instance.enAttente} en cours d'envoi` : ''}.` }
})
const rolesStore = useRolesStore()
const route = useRoute()

// Titre de la page (barre sous 960 px). Ordre : titre posé par la page (usePageTitle) > `definePageMeta({ titre })` > titre
// `h1` que la page affiche elle-même (lu dans le DOM : une seule source, aucun titre à répéter dans chaque page).
const titreExplicite = usePageTitle()
const titreH1 = ref('')
let observateur: MutationObserver | undefined
let lecturePlanifiee = 0
function lireTitreH1() {
  const h1 = document.querySelector('.v-main h1.page-title') ?? document.querySelector('.v-main h1')
  titreH1.value = (h1?.textContent ?? '').replace(/\s+/g, ' ').trim()
}
function planifierLecture() {
  if (lecturePlanifiee) return
  lecturePlanifiee = requestAnimationFrame(() => { lecturePlanifiee = 0; lireTitreH1() })
}
const titrePage = computed(() => {
  const explicite = titreExplicite.value.chemin === route.path ? titreExplicite.value.titre : ''
  return explicite || (route.meta.titre as string | undefined) || titreH1.value
})
watch(() => route.path, () => nextTick(lireTitreH1))

onMounted(() => {
  notif.charger()
  notif.connecter()
  parametresStore.charger()
  usePreferencesStore().charger()
  lireTitreH1()
  const principal = document.querySelector('.v-main')
  if (principal) {
    observateur = new MutationObserver(planifierLecture)
    observateur.observe(principal, { childList: true, subtree: true, characterData: true })
  }
})
onUnmounted(() => {
  notif.deconnecter()
  observateur?.disconnect()
  if (lecturePlanifiee) cancelAnimationFrame(lecturePlanifiee)
})

const TYPE_META: Record<string, { icon: string; color: string }> = {
  NOTE_SOUMISE:   { icon: 'mdi-send-circle-outline',   color: '#7c3aed' },
  NOTE_VERIFIEE:  { icon: 'mdi-check-circle-outline',  color: '#f97316' },
  NOTE_VALIDEE:   { icon: 'mdi-check-decagram-outline', color: '#0891b2' },
  NOTE_REJETEE:   { icon: 'mdi-close-circle-outline',  color: '#dc2626' },
  NOTE_TRANSMISE: { icon: 'mdi-cash-fast',             color: 'var(--color-primary)' },
  NOTE_PAYEE:     { icon: 'mdi-cash-check',            color: '#047857' },
  NOTE_ANNULEE:   { icon: 'mdi-cancel',                color: '#6b7280' },
  ENCAISSEMENT_CAISSE: { icon: 'mdi-cash-plus',        color: 'var(--color-primary)' },
  IA_CREDIT_EPUISE: { icon: 'mdi-robot-confused-outline', color: '#b45309' },
  PLATS_PERIMES:  { icon: 'mdi-clock-alert-outline',   color: '#b45309' },
  PLATS_PERIMES_RETIRES: { icon: 'mdi-delete-clock-outline', color: '#6b7280' },
  // Circuit des budgets : soumission au DA, approbation, rejet, mise en exécution, dépense validée hors budget.
  BUDGET_SOUMIS:  { icon: 'mdi-file-send-outline',     color: '#2563eb' },
  BUDGET_APPROUVE: { icon: 'mdi-check-decagram-outline', color: '#16a34a' },
  BUDGET_REJETE:  { icon: 'mdi-close-octagon-outline', color: '#dc2626' },
  BUDGET_EN_EXECUTION: { icon: 'mdi-play-circle-outline', color: '#0d9488' },
  BUDGET_DEPASSE: { icon: 'mdi-alert-octagon-outline', color: '#dc2626' },
  // Retour de fonds sur une note payée : attendu à la caisse (caissiers), retiré par l'émetteur, reçu en caisse (note
  // réduite), ou refusé par le caissier.
  RETOUR_FONDS_A_RECEVOIR: { icon: 'mdi-cash-clock', color: '#d97706' },
  RETOUR_FONDS_RETIRE: { icon: 'mdi-cash-off', color: '#6b7280' },
  RETOUR_FONDS_VALIDE: { icon: 'mdi-cash-refund', color: '#0d9488' },
  RETOUR_FONDS_REFUSE: { icon: 'mdi-cash-remove', color: '#dc2626' },
  // Action faite sur la copie en ligne (validation, nouvelle note...) appliquée ou refusée par le serveur local.
  ACTION_EN_LIGNE_APPLIQUEE: { icon: 'mdi-cloud-check-outline', color: '#0d9488' },
  ACTION_EN_LIGNE_REFUSEE: { icon: 'mdi-cloud-alert-outline', color: '#dc2626' },
  RETOUR_VENTE_A_APPROUVER: { icon: 'mdi-keyboard-return', color: '#d97706' },
  RETOUR_VENTE_APPROUVE: { icon: 'mdi-check-decagram-outline', color: '#2563eb' },
  RETOUR_VENTE_REFUSE: { icon: 'mdi-close-octagon-outline', color: '#dc2626' },
  RETOUR_VENTE_REMBOURSE: { icon: 'mdi-cash-refund', color: '#0d9488' },
  // Circuit des pièces comptables : saisie ou restauration à valider (DFIN), annulation par le comptable (DFIN),
  // comptabilisation et comptabilisation rétablie (comptable).
  PIECE_A_COMPTABILISER: { icon: 'mdi-file-document-edit-outline', color: '#2563eb' },
  PIECE_ANNULEE: { icon: 'mdi-file-cancel-outline', color: '#dc2626' },
  PIECE_COMPTABILISEE: { icon: 'mdi-file-check-outline', color: '#16a34a' },
  PIECE_RESTAUREE: { icon: 'mdi-file-restore-outline', color: '#0d9488' },
}
const typeMeta = (type: string) => TYPE_META[type] ?? { icon: 'mdi-bell-outline', color: '#6b7280' }

function tempsEcoule(iso: string): string {
  const secondes = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000)
  if (secondes < 60) return "à l'instant"
  const minutes = Math.floor(secondes / 60)
  if (minutes < 60) return `il y a ${minutes} min`
  const heures = Math.floor(minutes / 60)
  if (heures < 24) return `il y a ${heures} h`
  const jours = Math.floor(heures / 24)
  if (jours < 7) return `il y a ${jours} j`
  return new Date(iso).toLocaleDateString('fr-FR')
}

// Le panneau reste ouvert pour « Tout marquer comme lu » (close-on-content-click désactivé) ; il se referme quand une
// notification ouvre sa page, sinon il masquerait la page qu'on vient d'ouvrir.
const panneauNotifications = ref(false)
async function ouvrirNotification(n: NotificationItem) {
  await notif.marquerLue(n.id)
  if (n.lien) {
    panneauNotifications.value = false
    await navigateTo(n.lien)
  }
}
</script>

<template>
  <v-app-bar class="modern-appbar" height="60" elevation="0">
    <v-app-bar-nav-icon
      aria-label="Menu"
      class="modern-appbar__nav-icon"
      @click="emit('toggle-drawer')"
    />

    <div v-if="!drawerOpen || $vuetify.display.mobile" class="modern-appbar__brand">
      <div class="modern-appbar__logo logo-principal"><LogoMfinapp /></div>
      <span class="modern-appbar__name">{{ parametresStore.parametres.nom }}</span>
    </div>

    <!-- Téléphone et tablette (< 960 px) : le titre de la page, sous une miniature du logo et du nom de l'entreprise. -->
    <div class="modern-appbar__page" :class="{ 'modern-appbar__page--sans-titre': !titrePage }">
      <span class="modern-appbar__marque">
        <span class="modern-appbar__logo modern-appbar__logo--mini logo-principal"><LogoMfinapp /></span>
        <span class="modern-appbar__marque-nom">{{ parametresStore.parametres.nom }}</span>
      </span>
      <span v-if="titrePage" class="modern-appbar__titre" aria-hidden="true">{{ titrePage }}</span>
    </div>

    <v-spacer class="modern-appbar__espace" />

    <v-menu v-if="!applicationMobile && exercice.liste.length > 0" location="bottom end" max-height="420">
      <template #activator="{ props }">
        <v-btn v-bind="props" variant="tonal" size="small" rounded="lg" class="modern-appbar__exercice"
          :color="exercice.lectureSeule ? 'deep-purple' : exercice.enConsultation ? 'warning' : undefined"
          :class="{ 'modern-appbar__exercice--courant': !exercice.enConsultation }"
          :aria-label="`Exercice comptable ${exercice.anneeChoisie} : changer d'exercice`">
          <v-icon :icon="exercice.lectureSeule ? 'mdi-lock-outline' : 'mdi-calendar-blank-outline'" size="18" class="mr-1" />
          <span class="modern-appbar__exercice-libelle">Exercice&nbsp;</span>{{ exercice.anneeChoisie }}
          <v-icon icon="mdi-chevron-down" size="18" class="ml-1" />
        </v-btn>
      </template>
      <v-list density="compact" class="exercice-menu" aria-label="Exercices comptables">
        <v-list-subheader>Exercices comptables</v-list-subheader>
        <v-list-item v-for="e in exercice.liste" :key="e.annee" :active="e.annee === exercice.anneeChoisie"
          color="primary" @click="exercice.choisir(e.annee)">
          <template #prepend>
            <v-icon :icon="STATUTS_EXERCICE[e.statut].icone" :color="STATUTS_EXERCICE[e.statut].couleur" size="20" />
          </template>
          <v-list-item-title>Exercice {{ e.annee }}</v-list-item-title>
          <v-list-item-subtitle>{{ STATUTS_EXERCICE[e.statut].libelle }}</v-list-item-subtitle>
        </v-list-item>
      </v-list>
    </v-menu>

    <v-menu location="bottom end">
      <template #activator="{ props }">
        <v-btn icon variant="text" size="small" class="modern-appbar__action" v-bind="props"
          :aria-label="`Affichage : ${modeEffectif === 'sombre' ? 'nuit' : 'clair'}`">
          <v-icon :icon="modeEffectif === 'sombre' ? 'mdi-weather-night' : 'mdi-white-balance-sunny'" size="20" />
        </v-btn>
      </template>
      <v-list density="compact" class="mode-menu" aria-label="Mode d'affichage">
        <v-list-item v-for="m in MODES_AFFICHAGE" :key="m.valeur" :prepend-icon="m.icone" :title="m.libelle"
          :active="modeAffichage === m.valeur" color="primary" @click="choisirMode(m.valeur)" />
      </v-list>
    </v-menu>

    <v-btn icon variant="text" size="small" aria-label="Aide" class="modern-appbar__action modern-appbar__action--pc" to="/aide">
      <v-icon icon="mdi-help-circle-outline" size="20" />
    </v-btn>

    <v-btn icon variant="text" size="small" aria-label="Parametres" class="modern-appbar__action modern-appbar__action--pc" to="/profil">
      <v-icon icon="mdi-cog-outline" size="20" />
    </v-btn>

    <v-tooltip v-if="synchro" location="bottom" max-width="320" :text="synchro.texte">
      <template #activator="{ props }">
        <v-btn
          icon
          variant="text"
          size="small"
          :aria-label="synchro.texte"
          class="modern-appbar__action modern-appbar__synchro"
          :class="{ 'modern-appbar__synchro--probleme': synchro.probleme }"
          v-bind="props"
        >
          <v-icon :icon="synchro.icone" size="20" :color="synchro.couleur" />
        </v-btn>
      </template>
    </v-tooltip>

    <v-menu v-model="panneauNotifications" location="bottom end" :close-on-content-click="false">
      <template #activator="{ props }">
        <v-btn icon variant="text" size="small" aria-label="Notifications" class="modern-appbar__action" v-bind="props">
          <v-badge
            :model-value="notif.nonLues > 0"
            :content="notif.nonLues > 9 ? '9+' : notif.nonLues"
            color="error"
            floating
          >
            <v-icon icon="mdi-bell-outline" size="20" />
          </v-badge>
        </v-btn>
      </template>

      <div class="notif-panel">
        <div class="notif-panel__head">
          <span class="notif-panel__title">Notifications</span>
          <button
            v-if="notif.nonLues > 0"
            class="notif-panel__mark-all"
            @click="notif.marquerToutesLues()"
          >
            Tout marquer comme lu
          </button>
        </div>

        <div v-if="notif.notifications.length === 0" class="notif-panel__empty">
          <v-icon icon="mdi-bell-sleep-outline" size="26" color="#d1d5db" />
          <p>Aucune notification pour l'instant.</p>
        </div>

        <div v-else class="notif-panel__list">
          <button
            v-for="n in notif.notifications"
            :key="n.id"
            class="notif-item"
            :class="{ 'notif-item--non-lue': !n.lue }"
            @click="ouvrirNotification(n)"
          >
            <span class="notif-item__icon" :style="{ background: typeMeta(n.type).color + '1a', color: typeMeta(n.type).color }">
              <v-icon :icon="typeMeta(n.type).icon" size="16" />
            </span>
            <span class="notif-item__body">
              <span class="notif-item__titre">{{ n.titre }}</span>
              <span class="notif-item__message">{{ n.message }}</span>
              <span class="notif-item__date">{{ tempsEcoule(n.dateCreation) }}</span>
            </span>
            <span v-if="!n.lue" class="notif-item__dot" />
          </button>
        </div>
      </div>
    </v-menu>

    <v-menu location="bottom end">
      <template #activator="{ props }">
        <button v-bind="props" class="modern-appbar__avatar" aria-label="Compte">
          <img v-if="auth.photoObjectUrl" :src="auth.photoObjectUrl" alt="">
          <template v-else>{{ auth.initials }}</template>
        </button>
      </template>

      <div class="user-menu">
        <div class="user-menu__header">
          <div class="user-menu__avatar">
            <img v-if="auth.photoObjectUrl" :src="auth.photoObjectUrl" alt="">
            <template v-else>{{ auth.initials }}</template>
          </div>
          <div>
            <p class="user-menu__name">{{ auth.fullName || auth.user?.email }}</p>
            <p class="user-menu__email">{{ auth.user?.email }}</p>
          </div>
        </div>
        <div class="user-menu__roles">
          <span v-for="r in auth.roles" :key="r" class="user-menu__role">{{ rolesStore.libelle(r) }}</span>
        </div>
        <div class="user-menu__divider" />
        <NuxtLink to="/profil" class="user-menu__profile-link">
          <v-icon icon="mdi-account-cog-outline" size="16" class="mr-2" />
          Mon profil
        </NuxtLink>
        <!-- Papier à en-tête : pas dans l'application Android (impression depuis un ordinateur). -->
        <NuxtLink v-if="!applicationMobile" to="/papier-entete" class="user-menu__profile-link">
          <v-icon icon="mdi-printer-outline" size="16" class="mr-2" />
          Papier à en-tête
        </NuxtLink>
        <button class="user-menu__logout" @click="logout">
          <v-icon icon="mdi-logout" size="16" class="mr-2" />
          Se déconnecter
        </button>
      </div>
    </v-menu>
  </v-app-bar>
</template>

<style scoped>
/* ── App Bar ─────────────────────────────────────────────── */
.modern-appbar {
  /* Verre (21/11/2026) : la page défile floutée sous la barre. */
  background: color-mix(in srgb, var(--canvas) 55%, transparent) !important;
  -webkit-backdrop-filter: blur(22px) saturate(170%);
  backdrop-filter: blur(22px) saturate(170%);
  border-bottom: 1px solid var(--verre-bord) !important;
}

.modern-appbar__nav-icon {
  color: var(--ink-soft) !important;
}

.modern-appbar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: 4px;
}

.modern-appbar__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--color-primary);
  flex-shrink: 0;
  overflow: hidden;
}
.modern-appbar__logo--image { background: var(--surface); border: 1px solid var(--hairline); }
.modern-appbar__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }

.modern-appbar__name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--ink);
  letter-spacing: -0.01em;
}
.modern-appbar__name strong {
  font-weight: 700;
  color: var(--ink);
}

.modern-appbar__action {
  color: var(--ink-soft) !important;
}

/* Titre de la page : visible seulement sous 960 px (téléphone et tablette) ; au-dessus, la marque reste à gauche
   et le grand titre est dans la page. */
.modern-appbar__page { display: none; }
@media screen and (max-width: 959px) {
  .modern-appbar__brand,
  .modern-appbar__exercice-libelle { display: none; }
  .modern-appbar__action--pc,
  .modern-appbar__espace { display: none !important; }

  .modern-appbar__page {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    justify-content: center;
    gap: 2px;
    min-width: 0;
    margin-left: 2px;
  }
  .modern-appbar__marque {
    display: flex;
    align-items: center;
    gap: 5px;
    min-width: 0;
  }
  .modern-appbar__logo--mini { width: 14px; height: 14px; border-radius: 4px; }
  .modern-appbar__logo--mini img { padding: 1px; }
  .modern-appbar__marque-nom {
    font-size: 0.64rem;
    font-weight: 600;
    line-height: 1.2;
    letter-spacing: 0.4px;
    text-transform: uppercase;
    color: var(--ink-faint);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .modern-appbar__titre {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    overflow-wrap: anywhere;
    font-size: 1rem;
    font-weight: 700;
    line-height: 1.15;
    letter-spacing: -0.1px;
    color: var(--ink);
  }
  /* Titre pas encore connu (page qui se charge) : la marque garde sa taille habituelle. */
  .modern-appbar__page--sans-titre .modern-appbar__marque { gap: 10px; }
  .modern-appbar__page--sans-titre .modern-appbar__logo--mini { width: 30px; height: 30px; border-radius: 8px; }
  .modern-appbar__page--sans-titre .modern-appbar__logo--mini img { padding: 3px; }
  .modern-appbar__page--sans-titre .modern-appbar__marque-nom {
    font-size: 0.9375rem;
    font-weight: 400;
    letter-spacing: -0.1px;
    text-transform: none;
    color: var(--ink-2);
  }

  /* Cibles tactiles de 44 px. */
  .modern-appbar .modern-appbar__action { width: 44px !important; height: 44px !important; }
  .modern-appbar .modern-appbar__avatar { width: 40px; height: 40px; margin-left: 4px; margin-right: 4px; }
}

.modern-appbar__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--color-primary-darker);
  color: #fff;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.02em;
  border: none;
  cursor: pointer;
  margin-left: 6px;
  transition: opacity 0.15s;
}
.modern-appbar__avatar:hover { opacity: 0.85; }
.modern-appbar__avatar img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; }

/* ── Notifications ───────────────────────────────────────── */
.notif-panel {
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 14px;
  width: min(340px, calc(100vw - 16px));
  max-height: min(420px, calc(100dvh - 80px));
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,0.10);
  overflow: hidden;
}
.notif-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 16px 10px;
  flex-shrink: 0;
}
.notif-panel__title { font-size: 0.9rem; font-weight: 700; color: var(--ink); }
.notif-panel__mark-all {
  font-size: 0.72rem;
  font-weight: 600;
  color: var(--color-primary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 4px;
}
.notif-panel__mark-all:hover { text-decoration: underline; }

.notif-panel__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 20px 36px;
  text-align: center;
  color: var(--ink-faint);
  font-size: 0.82rem;
}
.notif-panel__empty p { margin: 0; }

.notif-panel__list {
  overflow-y: auto;
  padding: 4px;
}
.notif-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 10px 8px;
  background: none;
  border: none;
  border-radius: 10px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}
.notif-item:hover { background: var(--surface-2); }
.notif-item--non-lue { background: var(--color-primary-lighter); }
.notif-item--non-lue:hover { background: var(--color-primary-light); }

.notif-item__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  flex-shrink: 0;
  margin-top: 1px;
}
.notif-item__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.notif-item__titre {
  font-size: 0.82rem;
  font-weight: 700;
  color: var(--ink);
}
.notif-item__message {
  font-size: 0.78rem;
  color: var(--ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.notif-item__date {
  font-size: 0.68rem;
  color: var(--ink-faint);
  margin-top: 2px;
}
.notif-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
  flex-shrink: 0;
  margin-top: 6px;
}

/* ── User Menu ───────────────────────────────────────────── */
.user-menu {
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: 14px;
  padding: 6px;
  min-width: 260px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.10);
}

.user-menu__header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 10px 10px;
}

.user-menu__avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-dark));
  color: #fff;
  font-size: 0.875rem;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
}
.user-menu__avatar img { width: 100%; height: 100%; object-fit: cover; }

.user-menu__name {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--ink);
  margin: 0 0 2px;
}
.user-menu__email {
  font-size: 0.75rem;
  color: var(--ink-faint);
  margin: 0;
}

.user-menu__roles {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px 10px 10px;
}
.user-menu__role {
  font-size: 0.68rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 100px;
  background: var(--color-primary-light);
  color: var(--color-primary);
}

.user-menu__divider {
  height: 1px;
  background: var(--surface-2);
  margin: 0 6px;
}

.user-menu__profile-link,
.user-menu__logout {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 10px 10px;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--ink-soft);
  background: none;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-top: 4px;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}
.user-menu__profile-link:hover {
  background: var(--color-primary-lighter);
  color: var(--color-primary);
}
.user-menu__logout:hover {
  background: #fef2f2;
  color: #dc2626;
}

/* Pastille de synchronisation (serveur local) : sur téléphone, seulement quand il y a quelque chose à signaler. */
@media screen and (max-width: 599px) {
  .modern-appbar__synchro:not(.modern-appbar__synchro--probleme) { display: none; }
}
</style>

<style>
/* Thème nuit (généré le 17/11) : fonds pastel fixes remplacés par un fond sombre teinté. */
html[data-theme='dark'] .user-menu__logout:hover { background: color-mix(in srgb, #f19a9a 11%, var(--surface)) !important; color: #f19a9a !important; }
.modern-appbar__exercice {
  text-transform: none;
  letter-spacing: 0;
  font-weight: 600;
  margin-right: 6px;
  min-width: 0;
  padding: 0 10px !important;
}
.exercice-menu { min-width: 240px; }
/* Exercice en cours : couleur du thème choisi (variable --color-primary), pas le vert fixe de Vuetify. */
.modern-appbar__exercice--courant {
  color: var(--color-primary) !important;
  background: color-mix(in srgb, var(--color-primary) 12%, transparent) !important;
}
.modern-appbar__exercice--courant :deep(.v-btn__overlay) { background: var(--color-primary); }
</style>
