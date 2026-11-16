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

    <!-- ── Gauche · Marque ─────────────────────────────────── -->
    <div class="brand-panel">
      <div class="brand-panel__content">
        <div class="brand-mark">
          <span class="brand-logo-wrap" :class="{ 'brand-logo-wrap--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="">
            <v-icon v-else icon="mdi-finance" size="24" color="white" />
          </span>
          <span class="brand-name">{{ parametresStore.parametres.nom }}</span>
        </div>

        <h1 v-if="parametresStore.parametres.slogan" class="brand-headline">
          {{ parametresStore.parametres.slogan }}
        </h1>
        <h1 v-else class="brand-headline">
          Chaque opération laisse une écriture juste.
        </h1>
        <p class="brand-sub">
          Caisse, banque, notes de frais, ventes et paie : tout est comptabilisé selon le SYSCOHADA,
          au moment où vous saisissez.
        </p>

        <!-- Spécimen : une écriture de vente, telle que l'application la génère -->
        <figure class="ledger" aria-label="Exemple d'écriture comptable générée automatiquement">
          <figcaption class="ledger__title">Exemple : une vente au comptant</figcaption>
          <table class="ledger__table">
            <thead>
              <tr><th scope="col">Compte</th><th scope="col" class="num">Débit</th><th scope="col" class="num">Crédit</th></tr>
            </thead>
            <tbody>
              <tr><td><span class="ledger__no">571</span> Caisse</td><td class="num">116,00</td><td class="num" /></tr>
              <tr><td><span class="ledger__no">706</span> Services vendus</td><td class="num" /><td class="num">100,00</td></tr>
              <tr><td><span class="ledger__no">4431</span> TVA facturée</td><td class="num" /><td class="num">16,00</td></tr>
            </tbody>
            <tfoot>
              <tr><td>Total</td><td class="num">116,00</td><td class="num">116,00</td></tr>
            </tfoot>
          </table>
        </figure>
      </div>
    </div>

    <!-- ── Droite · Formulaire ─────────────────────────────── -->
    <main class="form-panel">
      <div class="form-panel__inner">

        <div class="form-mark">
          <span class="brand-logo-wrap brand-logo-wrap--image" :class="{ 'brand-logo-wrap--vide': !parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="">
            <v-icon v-else icon="mdi-finance" size="22" color="primary" />
          </span>
          <span class="form-mark__name">{{ parametresStore.parametres.nom }}</span>
        </div>

        <header class="form-header">
          <h2 class="form-title">Connexion</h2>
          <p class="form-subtitle">
            Entrez vos identifiants pour accéder à votre espace.
          </p>
        </header>

        <v-alert
          v-if="error"
          type="error"
          variant="tonal"
          density="compact"
          rounded="lg"
          class="mb-6"
          role="alert"
        >
          {{ error }}
        </v-alert>

        <v-form @submit.prevent="submit" class="form-body">
          <div class="field-group">
            <label class="field-label" for="login-email">Adresse e-mail</label>
            <v-text-field
              id="login-email"
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
            <label class="field-label" for="login-mdp">Mot de passe</label>
            <v-text-field
              id="login-mdp"
              v-model="motDePasse"
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
          </v-btn>
        </v-form>

        <footer class="form-footer">
          {{ parametresStore.parametres.nom }} &copy; {{ year }}
        </footer>
      </div>
    </main>

  </div>
</template>

<style scoped>
/* ── Disposition ─────────────────────────────────────────── */
.login-root {
  display: flex;
  min-height: 100dvh;
  background: var(--canvas);
}

/* ── Panneau de marque (gauche) : un grand livre ─────────── */
.brand-panel {
  position: relative;
  display: none;
  flex: 0 0 46%;
  background-color: var(--color-primary-darkest);
  /* Lignes d'un registre comptable, et la marge verticale double du grand livre. */
  background-image:
    linear-gradient(to right, transparent 59px, rgba(255,255,255,0.16) 59px, rgba(255,255,255,0.16) 60px, transparent 60px,
      transparent 63px, rgba(255,255,255,0.16) 63px, rgba(255,255,255,0.16) 64px, transparent 64px),
    repeating-linear-gradient(to bottom, transparent 0, transparent 35px, rgba(255,255,255,0.07) 35px, rgba(255,255,255,0.07) 36px);
  overflow: hidden;
  padding: 56px 56px 56px 96px;
}
@media (min-width: 960px) {
  .brand-panel { display: flex; align-items: center; }
}

.brand-panel__content {
  position: relative;
  max-width: 460px;
  color: #fff;
}

.brand-mark {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 48px;
}
.brand-logo-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px; height: 44px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.12);
  overflow: hidden;
}
.brand-logo-wrap--image { background: rgba(255, 255, 255, 0.94); }
.brand-logo-wrap img { width: 100%; height: 100%; object-fit: contain; padding: 5px; }
.brand-name {
  font-size: 1.05rem;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.brand-headline {
  font-size: clamp(1.75rem, 2.7vw, 2.35rem);
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.02em;
  margin: 0 0 16px;
  text-wrap: balance;
}
.brand-sub {
  font-size: 0.95rem;
  line-height: 1.65;
  color: rgba(255, 255, 255, 0.78);
  margin: 0 0 40px;
  max-width: 420px;
}

/* ── Spécimen d'écriture ─────────────────────────────────── */
.ledger {
  margin: 0;
  padding: 18px 20px 14px;
  background: #fbfcfa;
  color: #14211a;
  border-radius: 8px;
  box-shadow: 0 1px 0 rgba(0,0,0,0.25);
}
.ledger__title {
  font-size: 0.8125rem;
  font-weight: 600;
  color: #3f4d45;
  margin-bottom: 10px;
}
.ledger__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
  font-variant-numeric: tabular-nums;
}
.ledger__table th {
  font-size: 0.75rem;
  font-weight: 600;
  color: #5b685f;
  text-align: left;
  padding: 0 0 6px;
  border-bottom: 1px solid #14211a;
}
.ledger__table td { padding: 7px 0; border-bottom: 1px solid #e3e8e4; }
.ledger__table .num { text-align: right; width: 84px; }
.ledger__table th.num { text-align: right; }
.ledger__no {
  display: inline-block;
  min-width: 38px;
  margin-right: 6px;
  font-weight: 600;
  color: var(--color-primary-dark);
}
.ledger__table tfoot td {
  font-weight: 700;
  border-bottom: 0;
  border-top: 2px solid #14211a;
  padding-top: 8px;
}

/* ── Panneau du formulaire (droite) ──────────────────────── */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}
.form-panel__inner { width: 100%; max-width: 400px; }

.form-header { margin-bottom: 32px; }
.form-title {
  font-size: 1.85rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #111827;
  margin: 0 0 8px;
}
.form-subtitle { font-size: 0.9375rem; color: #4b5563; margin: 0; line-height: 1.5; }

.form-body { display: flex; flex-direction: column; gap: 20px; }
.field-group { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 0.875rem; font-weight: 600; color: #1f2937; }

.submit-btn {
  margin-top: 4px;
  height: 52px !important;
  font-size: 1rem !important;
  font-weight: 600 !important;
  letter-spacing: 0 !important;
  border-radius: 10px !important;
}

.form-footer { margin-top: 36px; font-size: 0.8125rem; color: #6b7280; text-align: center; }

/* Petits écrans : le panneau de marque est masqué, la marque passe en tête du formulaire. */
.form-mark { display: none; align-items: center; gap: 10px; margin-bottom: 28px; }
.form-mark__name { font-weight: 600; color: #1f2937; }
.brand-logo-wrap--vide { background: var(--color-primary-light); }
@media (max-width: 959px) {
  .form-panel { align-items: flex-start; padding-top: 48px; }
  .form-mark { display: flex; }
}
</style>
