<script setup lang="ts">
// Double authentification par code à usage unique (audit SYSCOHADA du 10/11/2026, C-23) : QR code à scanner avec
// une application d'authentification (Google Authenticator, Microsoft Authenticator, FreeOTP...), puis confirmation
// d'un premier code.
import QRCode from 'qrcode'

const emit = defineEmits<{ (e: 'activee'): void }>()

interface Etat { active: boolean, obligatoire: boolean, enrolementEnCours: boolean }

const api = useApi()
const dialogues = useDialogues()
const etat = ref<Etat | null>(null)
const secret = ref('')
const qr = ref('')
const code = ref('')
const envoi = ref(false)
const erreur = ref('')
const succes = ref('')

async function charger() {
  try {
    etat.value = await api<Etat>('/profil/double-auth')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de lire la double authentification.')
  }
}

async function demarrer() {
  envoi.value = true
  erreur.value = ''
  try {
    const r = await api<{ secret: string, uri: string }>('/profil/double-auth/demarrer', { method: 'POST' })
    secret.value = r.secret
    qr.value = await QRCode.toDataURL(r.uri, { width: 220, margin: 1 })
    code.value = ''
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de commencer l\'activation.')
  } finally {
    envoi.value = false
  }
}

async function confirmer() {
  envoi.value = true
  erreur.value = ''
  try {
    etat.value = await api<Etat>('/profil/double-auth/confirmer', { method: 'POST', body: { code: code.value.trim() } })
    secret.value = ''
    qr.value = ''
    succes.value = 'Double authentification activée : le code vous sera demandé à chaque connexion.'
    emit('activee')
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Code incorrect.')
  } finally {
    envoi.value = false
  }
}

async function desactiver() {
  const c = await dialogues.saisir({
    titre: 'Désactiver la double authentification ?',
    message: 'Votre compte ne sera plus protégé que par votre mot de passe. Saisissez le code actuel de votre application pour confirmer.',
    libelle: 'Code actuel de votre application',
    obligatoire: true,
    multiligne: false,
    inputmode: 'numeric',
    bouton: 'Désactiver',
    nature: 'danger',
    icone: 'mdi-shield-off-outline',
  })
  if (c === null) return
  envoi.value = true
  erreur.value = ''
  try {
    etat.value = await api<Etat>('/profil/double-auth/desactiver', { method: 'POST', body: { code: c.trim() } })
    succes.value = 'Double authentification désactivée.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Désactivation impossible.')
  } finally {
    envoi.value = false
  }
}

onMounted(charger)
</script>

<template>
  <div>
    <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mb-3">{{ erreur }}</v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" density="compact" class="mb-3">{{ succes }}</v-alert>

    <div v-if="etat?.active" class="d-flex flex-wrap align-center ga-3">
      <v-icon icon="mdi-shield-check" color="success" size="28" />
      <span>Double authentification active.</span>
      <v-btn v-if="!etat.obligatoire" variant="text" color="error" size="small" :loading="envoi" @click="desactiver">
        Désactiver
      </v-btn>
    </div>

    <template v-else>
      <p class="text-body-2 mb-3">
        {{ etat?.obligatoire
          ? 'La double authentification est obligatoire pour votre rôle.'
          : 'Protégez votre compte : à chaque connexion, un code à 6 chiffres de votre téléphone vous sera demandé.' }}
      </p>
      <v-btn v-if="!qr" color="primary" :loading="envoi" prepend-icon="mdi-qrcode" @click="demarrer">
        Activer la double authentification
      </v-btn>
      <div v-else class="enrolement">
        <img :src="qr" alt="QR code à scanner avec votre application d'authentification" width="220" height="220">
        <div>
          <ol class="text-body-2 mb-3">
            <li>Ouvrez votre application d'authentification et scannez ce QR code.</li>
            <li>Ou saisissez la clé : <code class="cle">{{ secret }}</code></li>
            <li>Saisissez le code à 6 chiffres affiché.</li>
          </ol>
          <v-text-field v-model="code" label="Code à 6 chiffres" inputmode="numeric" maxlength="6" variant="outlined"
                        density="comfortable" autocomplete="one-time-code" style="max-width: 200px" />
          <v-btn color="success" :loading="envoi" :disabled="code.trim().length !== 6" @click="confirmer">Confirmer</v-btn>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.enrolement {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  align-items: flex-start;
}
.cle {
  word-break: break-all;
  font-size: 0.85rem;
}
</style>
