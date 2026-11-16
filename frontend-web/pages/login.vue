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

// Bascule clair / nuit dès la page de connexion (même choix que dans l'application).
const { effectif: modeEffectif, choisir: choisirMode } = useModeAffichage()
const basculerMode = () => choisirMode(modeEffectif.value === 'sombre' ? 'clair' : 'sombre')

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
  <div class="cx">

    <!-- ── Visuel : photo de bureau comptable, teintée aux couleurs de la marque ── -->
    <aside class="visuel" aria-label="Présentation">
      <div class="visuel__photo" role="img" aria-label="Calculatrice, stylo et documents comptables sur un bureau" />

      <div class="visuel__marque">
        <span class="logo" :class="{ 'logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="">
          <v-icon v-else icon="mdi-finance" size="22" color="white" />
        </span>
        <span class="visuel__nom">{{ parametresStore.parametres.nom }}</span>
      </div>

      <!-- Spécimen : une écriture posée sur le bureau, comme un bordereau -->
      <figure class="bordereau" aria-label="Exemple d'écriture comptable générée automatiquement">
        <figcaption class="bordereau__titre">Vente au comptant — écriture générée</figcaption>
        <table class="bordereau__table">
          <thead><tr><th scope="col">Compte</th><th scope="col" class="num">Débit</th><th scope="col" class="num">Crédit</th></tr></thead>
          <tbody>
            <tr><td><b>571</b> Caisse</td><td class="num">116,00</td><td class="num" /></tr>
            <tr><td><b>706</b> Services vendus</td><td class="num" /><td class="num">100,00</td></tr>
            <tr><td><b>4431</b> TVA facturée</td><td class="num" /><td class="num">16,00</td></tr>
          </tbody>
          <tfoot><tr><td>Équilibrée</td><td class="num">116,00</td><td class="num">116,00</td></tr></tfoot>
        </table>
      </figure>

      <div class="visuel__texte">
        <h1 v-if="parametresStore.parametres.slogan" class="visuel__titre">{{ parametresStore.parametres.slogan }}</h1>
        <h1 v-else class="visuel__titre">Votre comptabilité et votre gestion, dans un seul outil.</h1>
        <p class="visuel__sous">
          Caisse, banque, ventes, stocks, paie et budgets : chaque opération devient une écriture SYSCOHADA,
          au moment où elle est saisie.
        </p>
      </div>
    </aside>

    <!-- ── Formulaire ─────────────────────────────────────── -->
    <main class="acces">
      <button type="button" class="acces__mode" :aria-label="modeEffectif === 'sombre' ? 'Passer en affichage clair' : 'Passer en affichage nuit'" @click="basculerMode">
        <v-icon :icon="modeEffectif === 'sombre' ? 'mdi-white-balance-sunny' : 'mdi-weather-night'" size="20" />
      </button>

      <div class="acces__inner">
        <div class="acces__marque">
          <span class="logo logo--clair" :class="{ 'logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="">
            <v-icon v-else icon="mdi-finance" size="20" color="primary" />
          </span>
          <span>{{ parametresStore.parametres.nom }}</span>
        </div>

        <h2 class="acces__titre">Connexion</h2>
        <p class="acces__sous">Entrez vos identifiants pour ouvrir votre espace.</p>

        <v-alert v-if="error" type="error" variant="tonal" density="compact" rounded="lg" class="mb-6" role="alert">
          {{ error }}
        </v-alert>

        <v-form class="acces__form" @submit.prevent="submit">
          <div class="champ">
            <label class="champ__label" for="login-email">Adresse e-mail</label>
            <v-text-field id="login-email" v-model="email" placeholder="vous@exemple.com" type="email"
              prepend-inner-icon="mdi-email-outline" autocomplete="username" required hide-details="auto" />
          </div>
          <div class="champ">
            <label class="champ__label" for="login-mdp">Mot de passe</label>
            <v-text-field id="login-mdp" v-model="motDePasse" :type="showPassword ? 'text' : 'password'"
              prepend-inner-icon="mdi-lock-outline" :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              autocomplete="current-password" required hide-details="auto" @click:append-inner="showPassword = !showPassword" />
          </div>
          <v-btn type="submit" color="primary" size="large" block :loading="loading" class="acces__btn" elevation="0">
            Se connecter
          </v-btn>
        </v-form>

        <footer class="acces__pied">{{ parametresStore.parametres.nom }} &copy; {{ year }}</footer>
      </div>
    </main>
  </div>
</template>

<style scoped>
.cx { display: flex; min-height: 100dvh; background: var(--surface); color: var(--ink); }

/* ── Visuel ──────────────────────────────────────────────── */
.visuel {
  position: relative;
  flex: 0 0 56%;
  display: none;
  overflow: hidden;
  background: var(--hero-bg);
  color: #fff;
}
@media (min-width: 960px) { .visuel { display: block; } }

/* Photo en niveaux de gris fondue dans le vert de marque : une image réelle, mais aux couleurs de l'application. */
.visuel__photo {
  position: absolute; inset: 0;
  background: url('/images/connexion-comptabilite.jpg') 35% 40% / cover no-repeat;
  filter: grayscale(1) contrast(1.08);
  mix-blend-mode: luminosity;
  opacity: 0.62;
}
.visuel::after {
  content: ''; position: absolute; inset: 0; pointer-events: none;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--hero-bg) 70%, transparent) 0%, transparent 22%),
    linear-gradient(0deg, var(--hero-bg) 4%, color-mix(in srgb, var(--hero-bg) 82%, transparent) 30%, transparent 62%);
}

.visuel__marque { position: absolute; z-index: 1; top: 40px; left: 48px; display: flex; align-items: center; gap: 12px; }
.visuel__nom { font-size: 1.05rem; font-weight: 650; }

.logo { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 10px; background: rgba(255,255,255,0.14); overflow: hidden; flex-shrink: 0; }
.logo--image { background: #fff; }
.logo img { width: 100%; height: 100%; object-fit: contain; padding: 5px; }
.logo--clair { background: var(--color-primary-light); }

.visuel__texte { position: absolute; z-index: 1; left: 48px; right: 48px; bottom: 48px; max-width: 560px; }
.visuel__titre { font-size: clamp(2rem, 3.3vw, 3rem); font-weight: 700; line-height: 1.08; letter-spacing: -0.035em; margin: 0 0 16px; text-wrap: balance; }
.visuel__sous { font-size: 1rem; line-height: 1.6; color: rgba(255,255,255,0.82); margin: 0; max-width: 48ch; }

/* Bordereau d'écriture posé sur la photo */
.bordereau {
  position: absolute; z-index: 1; top: 112px; right: 48px; width: min(330px, 42%);
  margin: 0; padding: 16px 18px 12px;
  background: #fbfcfa; color: #14211a; border-radius: 8px;
  box-shadow: 0 18px 40px -12px rgba(0,0,0,0.55);
}
.bordereau__titre { font-size: 0.8rem; font-weight: 600; color: #3f4d45; margin-bottom: 8px; }
.bordereau__table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
.bordereau__table th { font-size: 0.72rem; font-weight: 600; color: #5b685f; text-align: left; padding: 0 0 5px; border-bottom: 1px solid #14211a; }
.bordereau__table td { padding: 6px 0; border-bottom: 1px solid #e3e8e4; }
.bordereau__table b { display: inline-block; min-width: 34px; color: #1a6b45; }
.bordereau__table .num { text-align: right; width: 64px; }
.bordereau__table th.num { text-align: right; }
.bordereau__table tfoot td { font-weight: 700; border-bottom: 0; border-top: 2px solid #14211a; padding-top: 7px; }

/* ── Formulaire ──────────────────────────────────────────── */
.acces { position: relative; flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px 24px; background: var(--surface); }
.acces__inner { width: 100%; max-width: 380px; }
.acces__mode {
  position: absolute; top: 20px; right: 20px;
  display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px;
  border: 1px solid var(--hairline); border-radius: 10px; background: transparent; color: var(--ink-soft); cursor: pointer;
}
.acces__mode:hover { color: var(--ink); border-color: var(--ink-faint); }
.acces__mode:focus-visible { outline: 3px solid var(--color-primary-mid); outline-offset: 2px; }
.acces__marque { display: flex; align-items: center; gap: 10px; margin-bottom: 36px; font-weight: 600; }
@media (min-width: 960px) { .acces__marque { display: none; } }
.acces__titre { font-size: 2rem; font-weight: 700; letter-spacing: -0.03em; margin: 0 0 8px; }
.acces__sous { font-size: 0.95rem; color: var(--ink-soft); margin: 0 0 32px; line-height: 1.5; }
.acces__form { display: flex; flex-direction: column; gap: 20px; }
.champ { display: flex; flex-direction: column; gap: 6px; }
.champ__label { font-size: 0.875rem; font-weight: 600; color: var(--ink-2); }
.acces__btn { margin-top: 4px; height: 52px !important; font-size: 1rem !important; font-weight: 600 !important; letter-spacing: 0 !important; border-radius: 10px !important; }
.acces__pied { margin-top: 40px; font-size: 0.8125rem; color: var(--ink-faint); }

@media (max-width: 959px) {
  .acces { align-items: flex-start; padding-top: 72px; }
}
</style>
