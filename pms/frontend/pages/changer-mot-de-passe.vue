<script setup lang="ts">
import { useSessionStore } from '~/stores/session'

/**
 * Mot de passe provisoire : un compte créé par l'administrateur de M-FINAPP doit choisir son propre mot de passe avant
 * toute autre action (M-FINAPP refuse le reste). Le changement est fait dans M-FINAPP, qui ferme ensuite toutes les
 * sessions : on se reconnecte avec le nouveau mot de passe (valable dans M-FINAPP et dans l'hôtellerie).
 */
definePageMeta({ layout: false })
useHead({ title: 'Choisir votre mot de passe' })

const session = useSessionStore()
const form = reactive({ provisoire: '', nouveau: '', confirmation: '' })
const voir = ref(false)
const enCours = ref(false)
const erreur = ref('')
const succes = ref(false)

const regles = computed(() => [
  { ok: form.nouveau.length >= 8, texte: 'Au moins 8 caractères' },
  { ok: /[A-Za-z]/.test(form.nouveau) && /\d/.test(form.nouveau), texte: 'Des lettres et des chiffres' },
  { ok: !!form.nouveau && form.nouveau !== form.provisoire, texte: 'Différent du mot de passe provisoire' },
  { ok: !!form.nouveau && form.nouveau === form.confirmation, texte: 'Les deux saisies sont identiques' },
])
const valide = computed(() => !!form.provisoire && regles.value.every(r => r.ok))

async function changer() {
  erreur.value = ''
  if (!valide.value) {
    erreur.value = !form.provisoire ? "Saisissez le mot de passe provisoire reçu de l'administrateur." : (regles.value.find(r => !r.ok)?.texte ?? '')
    return
  }
  enCours.value = true
  try {
    await useApi()('/auth/mot-de-passe', { method: 'PUT', body: { ancienMotDePasse: form.provisoire, nouveauMotDePasse: form.nouveau } })
    succes.value = true
    setTimeout(() => {
      session.fermer()
      navigateTo({ path: '/login', query: { motDePasse: 'change' } })
    }, 1800)
  } catch (e) {
    erreur.value = messageErreur(e, "Le mot de passe n'a pas pu être changé.")
  } finally {
    enCours.value = false
  }
}
</script>

<template>
  <CadreAcces>
    <v-card class="acces__carte pa-8" rounded="xl">
      <div class="acces__logo logo-principal"><LogoMfinapp /></div>
      <h1 class="text-h5 font-weight-bold mt-4">Choisissez votre mot de passe</h1>
      <p class="text-body-2 text-medium-emphasis mb-6">
        {{ session.nomComplet ? session.nomComplet + ', votre' : 'Votre' }} compte a été créé avec un mot de passe provisoire.
        Choisissez le vôtre : il servira dans M-FINAPP et dans l'hôtellerie.
      </p>
      <v-alert v-if="succes" type="success" variant="tonal" class="mb-4 text-left">
        Mot de passe changé. Reconnectez-vous avec votre nouveau mot de passe…
      </v-alert>
      <template v-else>
        <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-4 text-left">{{ erreur }}</v-alert>
        <v-form @submit.prevent="changer">
          <v-text-field v-model="form.provisoire" :type="voir ? 'text' : 'password'" label="Mot de passe provisoire"
            prepend-inner-icon="mdi-lock-clock" autocomplete="current-password" />
          <v-text-field v-model="form.nouveau" :type="voir ? 'text' : 'password'" label="Nouveau mot de passe"
            prepend-inner-icon="mdi-lock-outline" autocomplete="new-password"
            :append-inner-icon="voir ? 'mdi-eye-off' : 'mdi-eye'" @click:append-inner="voir = !voir" />
          <v-text-field v-model="form.confirmation" :type="voir ? 'text' : 'password'" label="Confirmez le nouveau mot de passe"
            prepend-inner-icon="mdi-lock-check-outline" autocomplete="new-password" />
          <ul class="regles text-left mb-4">
            <li v-for="r in regles" :key="r.texte" :class="{ 'regles--ok': r.ok }">
              <v-icon :icon="r.ok ? 'mdi-check-circle' : 'mdi-circle-outline'" size="16" /> {{ r.texte }}
            </li>
          </ul>
          <v-btn type="submit" color="primary" size="large" block :loading="enCours" :disabled="!valide">Enregistrer mon mot de passe</v-btn>
        </v-form>
        <v-btn variant="text" class="mt-3" @click="session.fermer(); navigateTo('/login')">Se déconnecter</v-btn>
      </template>
    </v-card>
  </CadreAcces>
</template>

<style scoped>
.acces { min-height: 100dvh; display: grid; place-items: center; padding: 16px; background: var(--canvas); }
.acces__carte { width: 100%; max-width: 440px; text-align: center; }
.acces__logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 18px; display: grid; place-items: center; padding: 10px; }
.regles { list-style: none; padding: 0; font-size: 0.85rem; opacity: 0.8; }
.regles li { display: flex; align-items: center; gap: 6px; padding: 2px 0; }
.regles--ok { color: var(--color-primary); opacity: 1; }
</style>
