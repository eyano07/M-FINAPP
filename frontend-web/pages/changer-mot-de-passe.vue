<script setup lang="ts">
/**
 * Mot de passe provisoire (V131) : un compte créé par l'administrateur, ou dont il a redéfini le mot de passe, reste
 * sur cette page tant que l'utilisateur n'a pas choisi le sien (middleware/auth.global.ts) ; le serveur refuse tout le
 * reste (403 MOT_DE_PASSE_A_CHANGER). Après le changement, toutes les sessions sont fermées par le serveur : on se
 * reconnecte avec le nouveau mot de passe.
 */
definePageMeta({ layout: 'blank' })

import { useParametresStore } from '~/stores/parametres'
import { useAuthStore } from '~/stores/auth'

const api = useApi()
const { logout } = useAuth()
const auth = useAuthStore()
const parametresStore = useParametresStore()
// Copie en ligne d'un serveur local (V133) : un changement de mot de passe est une saisie, faite au serveur principal.
const surCopie = ref(false)
onMounted(async () => {
  parametresStore.charger()
  try {
    const e = await api<{ mode: string }>('/instance/etat')
    surCopie.value = e.mode === 'MIROIR'
  } catch {
    // ancien serveur ou instance indisponible : fonctionnement habituel
  }
})

const form = reactive({ provisoire: '', nouveau: '', confirmation: '' })
const voir = reactive({ provisoire: false, nouveau: false, confirmation: false })
const erreur = ref('')
const succes = ref(false)
const enCours = ref(false)

const prenom = computed(() => auth.user?.prenom || '')

const regles = computed(() => [
  { ok: form.nouveau.length >= 8, texte: 'Au moins 8 caractères' },
  { ok: !!form.nouveau && form.nouveau !== form.provisoire, texte: 'Différent du mot de passe provisoire' },
  { ok: !!form.confirmation && form.nouveau === form.confirmation, texte: 'Confirmation identique' },
])
const valide = computed(() => !!form.provisoire && regles.value.every(r => r.ok))

async function enregistrer() {
  erreur.value = ''
  if (!valide.value) {
    erreur.value = !form.provisoire
      ? 'Saisissez le mot de passe provisoire reçu de l\'administrateur.'
      : (regles.value.find(r => !r.ok)?.texte ?? 'Vérifiez le formulaire.')
    return
  }
  enCours.value = true
  try {
    await api('/profil/mot-de-passe', {
      method: 'PUT',
      body: { ancienMotDePasse: form.provisoire, nouveauMotDePasse: form.nouveau },
    })
    succes.value = true
    // Le serveur a fermé toutes les sessions : retour à la connexion avec le nouveau mot de passe.
    setTimeout(() => {
      auth.logout()
      navigateTo({ path: '/login', query: { motDePasse: 'change' } })
    }, 1800)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Le mot de passe n\'a pas pu être changé.')
  } finally {
    enCours.value = false
  }
}
</script>

<template>
  <div class="cmp-root">
    <div class="cmp-carte">
      <div class="cmp-marque">
        <div class="cmp-logo" :class="{ 'cmp-logo--image': parametresStore.parametres.logoUrl }">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
          <v-icon v-else icon="mdi-finance" size="22" color="white" />
        </div>
        <span class="cmp-entreprise">{{ parametresStore.parametres.nom }}</span>
      </div>

      <div class="cmp-icone">
        <v-icon icon="mdi-shield-key-outline" size="30" />
      </div>
      <h1 class="cmp-titre">Choisissez votre mot de passe</h1>
      <p class="cmp-texte">
        <template v-if="prenom">Bienvenue {{ prenom }}. </template>
        Votre compte a été créé par l'administrateur avec un <strong>mot de passe provisoire</strong> :
        choisissez le vôtre pour continuer. Lui seul le connaîtra.
      </p>

      <v-alert v-if="surCopie" type="info" variant="tonal" density="compact" rounded="lg" class="mb-4">
        Ce serveur est la copie en ligne du serveur de l'entreprise : changez votre mot de passe depuis le réseau de
        l'entreprise (serveur local), puis reconnectez-vous ici après la synchronisation.
      </v-alert>
      <v-alert v-if="succes" type="success" variant="tonal" density="compact" rounded="lg" class="mb-4">
        Mot de passe enregistré. Reconnectez-vous avec votre nouveau mot de passe…
      </v-alert>
      <v-alert v-else-if="erreur" type="error" variant="tonal" density="compact" rounded="lg" class="mb-4">
        {{ erreur }}
      </v-alert>

      <v-form class="cmp-form" @submit.prevent="enregistrer">
        <v-text-field
          v-model="form.provisoire"
          label="Mot de passe provisoire"
          :type="voir.provisoire ? 'text' : 'password'"
          prepend-inner-icon="mdi-lock-clock"
          :append-inner-icon="voir.provisoire ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
          autocomplete="current-password"
          variant="outlined"
          rounded="lg"
          hide-details="auto"
          :disabled="succes"
          @click:append-inner="voir.provisoire = !voir.provisoire"
        />
        <v-text-field
          v-model="form.nouveau"
          label="Nouveau mot de passe"
          :type="voir.nouveau ? 'text' : 'password'"
          prepend-inner-icon="mdi-lock-outline"
          :append-inner-icon="voir.nouveau ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
          autocomplete="new-password"
          variant="outlined"
          rounded="lg"
          hide-details="auto"
          :disabled="succes"
          @click:append-inner="voir.nouveau = !voir.nouveau"
        />
        <v-text-field
          v-model="form.confirmation"
          label="Confirmer le nouveau mot de passe"
          :type="voir.confirmation ? 'text' : 'password'"
          prepend-inner-icon="mdi-lock-check-outline"
          :append-inner-icon="voir.confirmation ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
          autocomplete="new-password"
          variant="outlined"
          rounded="lg"
          hide-details="auto"
          :disabled="succes"
          @click:append-inner="voir.confirmation = !voir.confirmation"
        />

        <ul class="cmp-regles" aria-label="Règles du mot de passe">
          <li v-for="r in regles" :key="r.texte" :class="{ 'cmp-regle--ok': r.ok }">
            <v-icon :icon="r.ok ? 'mdi-check-circle' : 'mdi-circle-outline'" size="16" />
            {{ r.texte }}
          </li>
        </ul>

        <v-btn
          type="submit"
          color="primary"
          size="large"
          block
          elevation="0"
          rounded="lg"
          :loading="enCours"
          :disabled="succes"
        >
          Enregistrer et continuer
          <v-icon icon="mdi-arrow-right" size="18" class="ml-2" />
        </v-btn>
      </v-form>

      <button type="button" class="cmp-deconnexion" @click="logout">
        <v-icon icon="mdi-logout" size="16" /> Se déconnecter
      </button>
    </div>
  </div>
</template>

<style scoped>
.cmp-root {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background:
    radial-gradient(circle at 15% 10%, rgba(46, 125, 50, 0.10), transparent 45%),
    radial-gradient(circle at 85% 90%, rgba(46, 125, 50, 0.08), transparent 45%),
    #f6f8fa;
}
.cmp-carte {
  width: 100%;
  max-width: 440px;
  padding: 28px 28px 20px;
  background: #fff;
  border: 1px solid #e8ecef;
  border-radius: 20px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 12px 32px rgba(15, 23, 42, 0.08);
}
.cmp-marque { display: flex; align-items: center; gap: 10px; margin-bottom: 22px; }
.cmp-logo {
  width: 36px; height: 36px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: var(--color-primary);
  overflow: hidden;
}
.cmp-logo--image { background: #fff; border: 1px solid #e8ecef; }
.cmp-logo img { width: 100%; height: 100%; object-fit: contain; }
.cmp-entreprise { font-weight: 700; color: #334155; letter-spacing: 0.2px; }
.cmp-icone {
  width: 56px; height: 56px; border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 14px;
  background: var(--color-primary-light);
  color: var(--color-primary);
}
.cmp-titre { margin: 0 0 6px; font-size: 1.4rem; font-weight: 800; color: #0f172a; line-height: 1.25; }
.cmp-texte { margin: 0 0 18px; font-size: 0.92rem; line-height: 1.5; color: #475569; }
.cmp-form { display: flex; flex-direction: column; gap: 14px; }
.cmp-regles { list-style: none; margin: 0 0 4px; padding: 0; display: flex; flex-direction: column; gap: 4px; }
.cmp-regles li { display: flex; align-items: center; gap: 8px; font-size: 0.85rem; color: #64748b; }
.cmp-regles li .v-icon { color: #cbd5e1; }
.cmp-regles li.cmp-regle--ok { color: #15803d; }
.cmp-regles li.cmp-regle--ok .v-icon { color: #16a34a; }
.cmp-deconnexion {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  width: 100%;
  min-height: 44px;
  margin-top: 10px;
  border: 0;
  background: transparent;
  color: #64748b;
  font-size: 0.9rem;
  cursor: pointer;
}
.cmp-deconnexion:hover { color: #0f172a; }
@media screen and (max-width: 599px) {
  .cmp-carte { padding: 22px 18px 14px; border-radius: 18px; }
  .cmp-titre { font-size: 1.25rem; }
}
</style>
