<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useNotificationsStore, type NotificationItem } from '~/stores/notifications'
import { useParametresStore } from '~/stores/parametres'

defineProps<{ drawerOpen: boolean }>()
const emit = defineEmits<{ (e: 'toggle-drawer'): void }>()

const auth = useAuthStore()
const { logout } = useAuth()
const notif = useNotificationsStore()
const parametresStore = useParametresStore()

onMounted(() => {
  notif.charger()
  notif.connecter()
  parametresStore.charger()
})
onUnmounted(() => {
  notif.deconnecter()
})

const TYPE_META: Record<string, { icon: string; color: string }> = {
  NOTE_SOUMISE:   { icon: 'mdi-send-circle-outline',   color: '#7c3aed' },
  NOTE_VERIFIEE:  { icon: 'mdi-check-circle-outline',  color: '#f97316' },
  NOTE_VALIDEE:   { icon: 'mdi-check-decagram-outline', color: '#0891b2' },
  NOTE_REJETEE:   { icon: 'mdi-close-circle-outline',  color: '#dc2626' },
  NOTE_TRANSMISE: { icon: 'mdi-cash-fast',             color: '#16a34a' },
  NOTE_PAYEE:     { icon: 'mdi-cash-check',            color: '#047857' },
  NOTE_ANNULEE:   { icon: 'mdi-cancel',                color: '#6b7280' },
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

async function ouvrirNotification(n: NotificationItem) {
  await notif.marquerLue(n.id)
  if (n.lien) await navigateTo(n.lien)
}
</script>

<template>
  <v-app-bar class="modern-appbar" height="60" elevation="0">
    <v-app-bar-nav-icon
      aria-label="Menu"
      class="modern-appbar__nav-icon"
      @click="emit('toggle-drawer')"
    />

    <div class="modern-appbar__brand">
      <div class="modern-appbar__logo" :class="{ 'modern-appbar__logo--image': parametresStore.parametres.logoUrl }">
        <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
        <v-icon v-else icon="mdi-finance" size="16" color="white" />
      </div>
      <span class="modern-appbar__name">{{ parametresStore.parametres.nom }}</span>
    </div>

    <v-spacer />

    <v-btn icon variant="text" size="small" aria-label="Aide" class="modern-appbar__action">
      <v-icon icon="mdi-help-circle-outline" size="20" />
    </v-btn>

    <v-btn icon variant="text" size="small" aria-label="Parametres" class="modern-appbar__action" to="/profil">
      <v-icon icon="mdi-cog-outline" size="20" />
    </v-btn>

    <v-menu location="bottom end" :close-on-content-click="false">
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
          <span v-for="r in auth.roles" :key="r" class="user-menu__role">{{ r }}</span>
        </div>
        <div class="user-menu__divider" />
        <NuxtLink to="/profil" class="user-menu__profile-link">
          <v-icon icon="mdi-account-cog-outline" size="16" class="mr-2" />
          Mon profil
        </NuxtLink>
        <NuxtLink to="/papier-entete" class="user-menu__profile-link">
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
  background: #ffffff !important;
  border-bottom: 1px solid #f0f0f0 !important;
}

.modern-appbar__nav-icon {
  color: #6b7280 !important;
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
  background: #16a34a;
  flex-shrink: 0;
  overflow: hidden;
}
.modern-appbar__logo--image { background: #fff; border: 1px solid #f0f0f0; }
.modern-appbar__logo img { width: 100%; height: 100%; object-fit: contain; padding: 3px; }

.modern-appbar__name {
  font-size: 0.9375rem;
  font-weight: 400;
  color: #374151;
  letter-spacing: -0.1px;
}
.modern-appbar__name strong {
  font-weight: 700;
  color: #111827;
}

.modern-appbar__action {
  color: #9ca3af !important;
}

.modern-appbar__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #16a34a, #15803d);
  color: #fff;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.5px;
  border: none;
  cursor: pointer;
  margin-left: 6px;
  transition: opacity 0.15s;
}
.modern-appbar__avatar:hover { opacity: 0.85; }
.modern-appbar__avatar img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; }

/* ── Notifications ───────────────────────────────────────── */
.notif-panel {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 14px;
  width: 340px;
  max-height: 420px;
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
.notif-panel__title { font-size: 0.9rem; font-weight: 700; color: #111827; }
.notif-panel__mark-all {
  font-size: 0.72rem;
  font-weight: 600;
  color: #16a34a;
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
  color: #9ca3af;
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
.notif-item:hover { background: #f9fafb; }
.notif-item--non-lue { background: #f0fdf4; }
.notif-item--non-lue:hover { background: #dcfce7; }

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
  color: #111827;
}
.notif-item__message {
  font-size: 0.78rem;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.notif-item__date {
  font-size: 0.68rem;
  color: #9ca3af;
  margin-top: 2px;
}
.notif-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #16a34a;
  flex-shrink: 0;
  margin-top: 6px;
}

/* ── User Menu ───────────────────────────────────────────── */
.user-menu {
  background: #fff;
  border: 1px solid #f0f0f0;
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
  background: linear-gradient(135deg, #16a34a, #15803d);
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
  color: #111827;
  margin: 0 0 2px;
}
.user-menu__email {
  font-size: 0.75rem;
  color: #9ca3af;
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
  background: #dcfce7;
  color: #16a34a;
}

.user-menu__divider {
  height: 1px;
  background: #f3f4f6;
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
  color: #6b7280;
  background: none;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  margin-top: 4px;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}
.user-menu__profile-link:hover {
  background: #f0fdf4;
  color: #16a34a;
}
.user-menu__logout:hover {
  background: #fef2f2;
  color: #dc2626;
}
</style>
