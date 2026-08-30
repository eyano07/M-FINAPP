<script setup lang="ts">
definePageMeta({ layout: 'blank' })

import { useParametresStore } from '~/stores/parametres'

const email = ref('')
const motDePasse = ref('')
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)

const { login } = useAuth()
const parametresStore = useParametresStore()
onMounted(() => { parametresStore.charger() })

const features = [
  { icon: 'mdi-chart-arc', label: 'Tableaux de bord en temps réel' },
  { icon: 'mdi-shield-check-outline', label: 'Sécurité de niveau entreprise' },
  { icon: 'mdi-receipt-text-outline', label: 'Suivi des notes de frais' },
]

const year = new Date().getFullYear()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await login(email.value, motDePasse.value)
    await navigateTo('/dashboard')
  } catch (e: any) {
    error.value =
      e?.data?.message || 'Identifiants invalides. Veuillez réessayer.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-root">

    <!-- ── Left · Brand panel ─────────────────────────────── -->
    <div class="brand-panel">
      <div class="brand-panel__blob brand-panel__blob--a" />
      <div class="brand-panel__blob brand-panel__blob--b" />

      <div class="brand-panel__content">
        <div class="brand-logo-wrap" :class="{ 'brand-logo-wrap--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="28" color="white" />
        </div>
        <p class="brand-eyebrow">{{ parametresStore.parametres.nom }}</p>
        <h1 v-if="parametresStore.parametres.slogan" class="brand-headline">
          {{ parametresStore.parametres.slogan }}
        </h1>
        <h1 v-else class="brand-headline">
          La finance d'entreprise,<br>simplement.
        </h1>
        <p class="brand-sub">
          Pilotez vos budgets, validez les dépenses et analysez vos flux
          financiers en un seul endroit.
        </p>

        <ul class="brand-features">
          <li v-for="f in features" :key="f.label" class="brand-feature">
            <span class="brand-feature__icon">
              <v-icon :icon="f.icon" size="16" />
            </span>
            {{ f.label }}
          </li>
        </ul>
      </div>
    </div>

    <!-- ── Right · Form panel ────────────────────────────── -->
    <div class="form-panel">
      <div class="form-panel__inner">

        <header class="form-header">
          <h2 class="form-title">Connexion</h2>
          <p class="form-subtitle">
            Accédez à votre espace de gestion financière
          </p>
        </header>

        <v-alert
          v-if="error"
          type="error"
          variant="tonal"
          density="compact"
          rounded="lg"
          class="mb-6"
        >
          {{ error }}
        </v-alert>

        <v-form @submit.prevent="submit" class="form-body">
          <div class="field-group">
            <span class="field-label">Adresse e-mail</span>
            <v-text-field
              v-model="email"
              placeholder="vous@exemple.com"
              type="email"
              prepend-inner-icon="mdi-email-outline"
              autocomplete="username"
              required
              hide-details="auto"
            />
          </div>

          <div class="field-group">
            <span class="field-label">Mot de passe</span>
            <v-text-field
              v-model="motDePasse"
              placeholder="••••••••"
              :type="showPassword ? 'text' : 'password'"
              prepend-inner-icon="mdi-lock-outline"
              :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              autocomplete="current-password"
              required
              hide-details="auto"
              @click:append-inner="showPassword = !showPassword"
            />
          </div>

          <v-btn
            type="submit"
            color="primary"
            size="large"
            block
            :loading="loading"
            class="submit-btn"
            elevation="0"
          >
            Se connecter
            <v-icon icon="mdi-arrow-right" size="18" class="ml-2" />
          </v-btn>
        </v-form>

        <footer class="form-footer">
          {{ parametresStore.parametres.nom }} &copy; {{ year }} — Gestion financière
        </footer>
      </div>
    </div>

  </div>
</template>

<style scoped>
/* ── Layout root ─────────────────────────────────────────── */
.login-root {
  display: flex;
  min-height: 100dvh;
  background: #f8f9fa;
}

/* ── Brand panel (left) ──────────────────────────────────── */
.brand-panel {
  position: relative;
  display: none;
  flex: 0 0 42%;
  background: linear-gradient(145deg, #0a3d1f 0%, #145a32 50%, #0d4f2a 100%);
  overflow: hidden;
  padding: 56px 52px;
}

@media (min-width: 960px) {
  .brand-panel { display: flex; align-items: center; }
}

.brand-panel__blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
}
.brand-panel__blob--a {
  width: 380px; height: 380px;
  background: rgba(30, 142, 62, 0.35);
  top: -80px; right: -100px;
}
.brand-panel__blob--b {
  width: 260px; height: 260px;
  background: rgba(26, 115, 232, 0.18);
  bottom: 40px; left: -60px;
}

.brand-panel__content {
  position: relative;
  z-index: 1;
  color: #fff;
}

.brand-logo-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px; height: 52px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  margin-bottom: 28px;
  overflow: hidden;
}
.brand-logo-wrap--image { background: rgba(255, 255, 255, 0.92); }
.brand-logo-wrap img { width: 100%; height: 100%; object-fit: contain; padding: 6px; }

.brand-eyebrow {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.6px;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.55);
  margin: 0 0 14px;
}

.brand-headline {
  font-size: clamp(1.6rem, 2.4vw, 2.1rem);
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.5px;
  margin: 0 0 18px;
}

.brand-sub {
  font-size: 0.9rem;
  line-height: 1.65;
  color: rgba(255, 255, 255, 0.65);
  margin: 0 0 40px;
  max-width: 340px;
}

.brand-features {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.brand-feature {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 0.875rem;
  color: rgba(255, 255, 255, 0.82);
}

.brand-feature__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px; height: 30px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

/* ── Form panel (right) ──────────────────────────────────── */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-panel__inner {
  width: 100%;
  max-width: 420px;
}

/* ── Form header ─────────────────────────────────────────── */
.form-header {
  margin-bottom: 36px;
}

.form-title {
  font-size: 1.85rem;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: #111827;
  margin: 0 0 8px;
}

.form-subtitle {
  font-size: 0.9rem;
  color: #6b7280;
  margin: 0;
}

/* ── Fields ──────────────────────────────────────────────── */
.form-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: #374151;
  letter-spacing: 0.1px;
}

/* ── Submit button ───────────────────────────────────────── */
.submit-btn {
  margin-top: 4px;
  height: 52px !important;
  font-size: 0.9375rem !important;
  font-weight: 600 !important;
  letter-spacing: 0.2px !important;
  border-radius: 12px !important;
}

/* ── Footer ──────────────────────────────────────────────── */
.form-footer {
  margin-top: 36px;
  font-size: 0.78rem;
  color: #9ca3af;
  text-align: center;
}
</style>
