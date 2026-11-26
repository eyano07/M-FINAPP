<script setup lang="ts">
definePageMeta({ layout: 'blank' })

import { useParametresStore } from '~/stores/parametres'
import { estApplicationLogistique } from '~/utils/applicationMobile'

// Identifiant de connexion : nom d'utilisateur OU adresse e-mail (le champ API s'appelle toujours « email »).
const identifiant = ref('')
const motDePasse = ref('')
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)
// Double authentification (audit SYSCOHADA du 10/11/2026, C-23) : le serveur demande le code après le mot de passe.
const codeDemande = ref(false)
const code = ref('')

const { login } = useAuth()
// Retour de la page « Choisissez votre mot de passe » (mot de passe provisoire remplacé) : on se reconnecte.
const motDePasseChange = useRoute().query.motDePasse === 'change'
// Activation du mode local lancée depuis cet onglet : les comptes viennent maintenant du serveur en ligne.
const apresActivation = ref(false)
const parametresStore = useParametresStore()
// Application MBSC Logistique : mention « Logistique » sur l'écran de connexion (lue après le montage, l'agent
// utilisateur n'étant connu que du navigateur).
const logistique = ref(false)
onMounted(() => { logistique.value = estApplicationLogistique() })
onMounted(() => {
  parametresStore.charger()
  apresActivation.value = activationModeLocalRecente()
})

const year = new Date().getFullYear()

// Bascule clair / nuit dès la page de connexion (même choix que dans l'application).
const { effectif: modeEffectif, choisir: choisirMode } = useModeAffichage()
const basculerMode = () => choisirMode(modeEffectif.value === 'sombre' ? 'clair' : 'sombre')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await login(identifiant.value.trim(), motDePasse.value, codeDemande.value ? code.value.trim() : undefined)
    if (apresActivation.value) {
      oublierActivationModeLocal()
      await navigateTo('/admin/synchronisation')
      return
    }
    await navigateTo('/dashboard')
  } catch (e: any) {
    const codeErreur = e?.data?.code
    if (codeErreur === 'DOUBLE_AUTH_REQUISE' || codeErreur === 'CODE_DOUBLE_AUTH_INCORRECT') {
      codeDemande.value = true
      code.value = ''
      error.value = codeErreur === 'DOUBLE_AUTH_REQUISE' ? '' : messageErreurApi(e, 'Code incorrect.')
      return
    }
    error.value = messageErreurApi(e, 'Identifiants invalides. Veuillez réessayer.')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="cx">
    <!-- Fond coloré flou (même aurore que l'application) : le formulaire en verre le laisse transparaître. -->
    <div class="fond-aurore" aria-hidden="true"><span /><span /><span /><span /><span /></div>

    <!-- ── Visuel : photo de bureau comptable, teintée aux couleurs de la marque ── -->
    <aside class="visuel" aria-label="Présentation">
      <div class="visuel__photo" role="img" aria-label="Calculatrice, stylo et documents comptables sur un bureau" />

      <div class="visuel__marque">
        <span class="logo logo-principal"><LogoMfinapp /></span>
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

      <!-- Téléphone et tablette (< 960 px) : la photo du visuel en bandeau au-dessus du formulaire. -->
      <div class="acces__banniere" aria-hidden="true">
        <div class="acces__banniere-photo" />
        <div class="acces__banniere-marque">
          <span class="logo logo-principal"><LogoMfinapp /></span>
          <span>{{ parametresStore.parametres.nom }}</span>
          <span v-if="logistique" class="pastille-logistique">Logistique</span>
        </div>
        <p class="acces__banniere-texte">{{ logistique ? 'Logistique, approvisionnement et patrimoine' : 'Gestion financière et comptable' }}</p>
      </div>

      <div class="acces__inner">
        <div class="acces__marque">
          <span class="logo logo-principal"><LogoMfinapp /></span>
          <span>{{ parametresStore.parametres.nom }}</span>
          <span v-if="logistique" class="pastille-logistique">Logistique</span>
        </div>

        <h2 class="acces__titre">Connexion</h2>
        <p class="acces__sous">Entrez vos identifiants pour ouvrir votre espace.</p>

        <v-alert v-if="motDePasseChange && !error" type="success" variant="tonal" density="compact" rounded="lg" class="mb-6">
          Mot de passe enregistré : connectez-vous avec votre nouveau mot de passe.
        </v-alert>
        <v-alert v-if="apresActivation && !error" type="info" variant="tonal" density="compact" rounded="lg" class="mb-6">
          Mode local : les comptes de ce serveur viennent d'être remplacés par ceux du serveur en ligne. Connectez-vous
          avec votre identifiant et votre mot de passe du serveur en ligne ; vous verrez ensuite le résultat de l'activation.
        </v-alert>
        <v-alert v-if="error" type="error" variant="tonal" density="compact" rounded="lg" class="mb-6" role="alert">
          {{ error }}
        </v-alert>

        <v-form class="acces__form" @submit.prevent="submit">
          <div class="champ">
            <label class="champ__label" for="login-email">Nom d'utilisateur ou adresse e-mail</label>
            <v-text-field id="login-email" v-model="identifiant" placeholder="nom_utilisateur ou vous@exemple.com" type="text"
              prepend-inner-icon="mdi-account-outline" autocomplete="username" autocapitalize="none" autocorrect="off"
              spellcheck="false" required hide-details="auto" />
          </div>
          <div class="champ">
            <label class="champ__label" for="login-mdp">Mot de passe</label>
            <v-text-field id="login-mdp" v-model="motDePasse" :type="showPassword ? 'text' : 'password'"
              prepend-inner-icon="mdi-lock-outline" :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              autocomplete="current-password" required hide-details="auto" @click:append-inner="showPassword = !showPassword" />
          </div>
          <div v-if="codeDemande" class="champ">
            <label class="champ__label" for="login-code">Code de votre application d'authentification</label>
            <v-text-field id="login-code" v-model="code" placeholder="123456" inputmode="numeric" maxlength="6"
              prepend-inner-icon="mdi-shield-key-outline" autocomplete="one-time-code" autofocus hide-details="auto" />
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
.cx {
  /* Teinte du visuel : vert de marque vers turquoise et bleu nuit (21/11/2026). */
  --teinte: color-mix(in srgb, var(--color-primary) 55%, #062a2a);
  position: relative; isolation: isolate;
  display: flex; min-height: 100dvh; background: var(--canvas); color: var(--ink);
}

/* ── Visuel ──────────────────────────────────────────────── */
.visuel {
  position: relative;
  flex: 0 0 56%;
  display: none;
  overflow: hidden;
  background:
    radial-gradient(90% 70% at 100% 100%, rgba(99, 102, 241, 0.55), transparent 60%),
    radial-gradient(70% 60% at 0% 0%, color-mix(in srgb, var(--color-primary) 55%, transparent), transparent 60%),
    linear-gradient(150deg, var(--teinte), #0f3d4a 60%, #1e2a5e);
  color: #fff;
  margin: 14px 0 14px 14px; border-radius: 28px;
  box-shadow: 0 30px 60px -30px rgba(6, 30, 40, 0.6);
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
    linear-gradient(180deg, color-mix(in srgb, #0f3d4a 70%, transparent) 0%, transparent 22%),
    linear-gradient(0deg, #13234f 4%, color-mix(in srgb, #13234f 75%, transparent) 30%, transparent 62%);
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
  background: rgba(255, 255, 255, 0.72); color: #14211a; border-radius: 18px;
  -webkit-backdrop-filter: blur(18px) saturate(170%); backdrop-filter: blur(18px) saturate(170%);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: 0 1px 0 rgba(255,255,255,0.8) inset, 0 24px 50px -16px rgba(0,0,0,0.55);
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
.acces { position: relative; flex: 1; display: flex; align-items: center; justify-content: center; padding: 40px 24px; background: transparent; }
/* Formulaire dans une carte de verre posée sur l'aurore. */
.acces__inner {
  width: 100%; max-width: 440px; padding: 40px 36px 32px;
  background: var(--verre); -webkit-backdrop-filter: var(--flou); backdrop-filter: var(--flou);
  border: 1px solid var(--verre-bord); border-radius: 28px; box-shadow: var(--verre-ombre), 0 30px 60px -30px rgba(15, 40, 30, 0.35);
}
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
.acces__btn { margin-top: 4px; height: 52px !important; font-size: 1rem !important; font-weight: 600 !important; letter-spacing: 0 !important;
  border-radius: 14px !important; color: #fff !important;
  background: linear-gradient(135deg, var(--color-primary), color-mix(in srgb, var(--color-primary) 70%, #0891b2) 60%, color-mix(in srgb, var(--color-primary) 50%, #0891b2)) !important;
  box-shadow: 0 12px 24px -12px color-mix(in srgb, var(--color-primary) 75%, transparent) !important; }
.acces__pied { margin-top: 40px; font-size: 0.8125rem; color: var(--ink-faint); }

/* Mention « Logistique » de l'application MBSC Logistique. */
.pastille-logistique { display: inline-flex; align-items: center; padding: 3px 10px; border-radius: 999px; font-size: 0.72rem;
  font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #fff; background: #d97706; }

/* Bandeau photo du téléphone : même photo que le visuel du PC, fondue dans la couleur de marque. */
.acces__banniere { display: none; }
@media (max-width: 959px) {
  .acces { flex-direction: column; align-items: stretch; justify-content: flex-start; padding: 0 0 32px; }
  .acces__inner { align-self: center; width: calc(100% - 32px); margin-top: -28px; position: relative; z-index: 1; padding: 28px 22px 24px; }
  .acces__marque { display: none; }
  .acces__mode { z-index: 2; color: #fff; border-color: rgba(255, 255, 255, 0.4); background: rgba(0, 0, 0, 0.12); }
  .acces__banniere {
    display: block; position: relative; overflow: hidden; color: #fff;
    height: clamp(190px, 32vh, 280px);
    background:
      radial-gradient(90% 90% at 100% 100%, rgba(99, 102, 241, 0.55), transparent 60%),
      linear-gradient(150deg, var(--teinte), #0f3d4a 60%, #1e2a5e);
    border-radius: 0 0 28px 28px;
  }
  .acces__banniere-photo {
    position: absolute; inset: 0;
    background: url('/images/connexion-comptabilite.jpg') 35% 40% / cover no-repeat;
    filter: grayscale(1) contrast(1.08);
    mix-blend-mode: luminosity;
    opacity: 0.62;
  }
  .acces__banniere::after {
    content: ''; position: absolute; inset: 0; pointer-events: none;
    background: linear-gradient(0deg, #13234f 6%, color-mix(in srgb, #13234f 55%, transparent) 55%, transparent 100%);
  }
  .acces__banniere-marque {
    position: absolute; z-index: 1; left: 24px; right: 72px; bottom: 70px;
    display: flex; align-items: center; gap: 10px; font-size: 1.05rem; font-weight: 650;
  }
  .acces__banniere-texte { position: absolute; z-index: 1; left: 24px; right: 24px; bottom: 44px; margin: 0; font-size: 0.85rem; opacity: 0.82; }
}
</style>
