<script setup lang="ts">
// Auto-service : chaque utilisateur connecte gere ses propres informations.
// Accessible depuis le bouton engrenage et le menu du compte (AppBar.vue).
definePageMeta({})

const auth = useAuthStore()
const api = useApi()

const loading = ref(true)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

// ── Informations ───────────────────────────────────────────────────────
const form = reactive({ nom: '', prenom: '', telephone: '' })

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const p = await api<{ nom: string; prenom: string; telephone: string | null }>('/profil')
    form.nom = p.nom || ''
    form.prenom = p.prenom || ''
    form.telephone = p.telephone || ''
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le profil.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  if (!form.nom.trim() || !form.prenom.trim()) {
    erreur.value = 'Nom et prénom sont obligatoires.'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/profil', {
      method: 'PUT',
      body: { nom: form.nom.trim(), prenom: form.prenom.trim(), telephone: form.telephone.trim() || null },
    })
    // Rafraichit le store global : le nom/l'avatar dans l'App Bar et le menu
    // laterale en dependent, pas seulement cette page.
    await auth.verifierSession()
    succes.value = 'Profil mis à jour.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

// ── Photo ─────────────────────────────────────────────────────────────
const TYPES_AUTORISES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp']
const TAILLE_MAX_OCTETS = 5 * 1024 * 1024
const photoInput = ref<HTMLInputElement | null>(null)
const uploadingPhoto = ref(false)

function declencherChoixPhoto() {
  photoInput.value?.click()
}

async function onPhotoChoisie(e: Event) {
  const input = e.target as HTMLInputElement
  const fichier = input.files?.[0]
  input.value = ''
  if (!fichier) return

  if (!TYPES_AUTORISES.includes(fichier.type)) {
    erreur.value = `Format non autorisé pour « ${fichier.name} ». Formats acceptés : JPEG, PNG, WEBP.`
    return
  }
  if (fichier.size > TAILLE_MAX_OCTETS) {
    erreur.value = `« ${fichier.name} » dépasse la taille maximale autorisée (5 Mo).`
    return
  }

  uploadingPhoto.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const formData = new FormData()
    formData.append('fichier', fichier)
    await api('/profil/photo', { method: 'POST', body: formData })
    await auth.verifierSession()
    succes.value = 'Photo de profil mise à jour.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'envoi de la photo.")
  } finally {
    uploadingPhoto.value = false
  }
}

// ── Mot de passe ──────────────────────────────────────────────────────
const pwd = reactive({ ancien: '', nouveau: '', confirmation: '' })
const pwdSaving = ref(false)
const pwdErreur = ref('')
const pwdSucces = ref('')
const showAncien = ref(false)
const showNouveau = ref(false)

async function changerMotDePasse() {
  pwdErreur.value = ''
  pwdSucces.value = ''
  if (!pwd.ancien || !pwd.nouveau) {
    pwdErreur.value = 'Mot de passe actuel et nouveau mot de passe sont obligatoires.'
    return
  }
  if (pwd.nouveau.length < 8) {
    pwdErreur.value = 'Le nouveau mot de passe doit contenir au moins 8 caractères.'
    return
  }
  if (pwd.nouveau !== pwd.confirmation) {
    pwdErreur.value = 'La confirmation ne correspond pas au nouveau mot de passe.'
    return
  }
  pwdSaving.value = true
  try {
    await api('/profil/mot-de-passe', {
      method: 'PUT',
      body: { ancienMotDePasse: pwd.ancien, nouveauMotDePasse: pwd.nouveau },
    })
    pwd.ancien = ''
    pwd.nouveau = ''
    pwd.confirmation = ''
    pwdSucces.value = 'Mot de passe modifié.'
  } catch (e: any) {
    pwdErreur.value = messageErreurApi(e, 'Échec du changement de mot de passe.')
  } finally {
    pwdSaving.value = false
  }
}
</script>

<template>
  <div class="profil-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Mon profil</h1>
        <p class="page-sub">Informations personnelles, photo et mot de passe</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card" />

    <template v-else>
      <!-- ── Photo + informations ────────────────────────────────── -->
      <v-card rounded="lg" border flat class="mb-5">
        <v-card-text class="profil-hero">
          <div class="profil-hero__photo">
            <img v-if="auth.photoObjectUrl" :src="auth.photoObjectUrl" alt="Photo de profil">
            <span v-else class="profil-hero__initials">{{ auth.initials }}</span>
          </div>
          <div class="profil-hero__meta">
            <p class="profil-hero__name">{{ auth.fullName || auth.user?.email }}</p>
            <p class="profil-hero__email">{{ auth.user?.email }}</p>
            <div class="profil-hero__roles">
              <span v-for="r in auth.roles" :key="r" class="profil-hero__role">{{ r }}</span>
            </div>
          </div>
          <div class="profil-hero__action">
            <v-btn variant="tonal" color="primary" rounded="lg" size="small" prepend-icon="mdi-camera-outline"
              :loading="uploadingPhoto" @click="declencherChoixPhoto">
              {{ auth.user?.photoUrl ? 'Changer la photo' : 'Ajouter une photo' }}
            </v-btn>
            <input ref="photoInput" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="onPhotoChoisie">
            <p class="profil-hero__hint">JPEG, PNG, WEBP (5 Mo max)</p>
          </div>
        </v-card-text>
      </v-card>

      <v-card rounded="lg" border flat class="mb-5">
        <v-card-item>
          <v-card-title class="text-subtitle-1">Informations personnelles</v-card-title>
        </v-card-item>
        <v-card-text>
          <div class="profil-form">
            <v-text-field v-model="form.nom" label="Nom" variant="outlined" density="comfortable" rounded="lg" hide-details="auto" />
            <v-text-field v-model="form.prenom" label="Prénom" variant="outlined" density="comfortable" rounded="lg" hide-details="auto" />
            <v-text-field :model-value="auth.user?.email" label="E-mail" variant="outlined" density="comfortable" rounded="lg"
              hide-details="auto" readonly disabled hint="L'e-mail de connexion n'est pas modifiable ici." persistent-hint />
            <v-text-field v-model="form.telephone" label="Téléphone" variant="outlined" density="comfortable" rounded="lg"
              hide-details="auto" prepend-inner-icon="mdi-phone-outline" placeholder="Ex: +243 999 123 456" />
          </div>
        </v-card-text>
        <v-card-actions class="px-4 pb-4">
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" prepend-icon="mdi-content-save-outline" :loading="saving" @click="enregistrer">
            Enregistrer
          </v-btn>
        </v-card-actions>
      </v-card>

      <!-- ── Mot de passe ────────────────────────────────────────── -->
      <v-card rounded="lg" border flat>
        <v-card-item>
          <v-card-title class="text-subtitle-1">Mot de passe</v-card-title>
          <v-card-subtitle>Modifiez votre mot de passe de connexion</v-card-subtitle>
        </v-card-item>
        <v-card-text>
          <v-alert v-if="pwdErreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-3">{{ pwdErreur }}</v-alert>
          <v-alert v-if="pwdSucces" type="success" variant="tonal" rounded="lg" density="compact" class="mb-3">{{ pwdSucces }}</v-alert>
          <div class="profil-form">
            <v-text-field v-model="pwd.ancien" label="Mot de passe actuel" :type="showAncien ? 'text' : 'password'"
              variant="outlined" density="comfortable" rounded="lg" hide-details="auto"
              :append-inner-icon="showAncien ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="showAncien = !showAncien" />
            <v-text-field v-model="pwd.nouveau" label="Nouveau mot de passe" :type="showNouveau ? 'text' : 'password'"
              variant="outlined" density="comfortable" rounded="lg" hide-details="auto"
              hint="8 caractères minimum" persistent-hint
              :append-inner-icon="showNouveau ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" @click:append-inner="showNouveau = !showNouveau" />
            <v-text-field v-model="pwd.confirmation" label="Confirmer le nouveau mot de passe" :type="showNouveau ? 'text' : 'password'"
              variant="outlined" density="comfortable" rounded="lg" hide-details="auto" />
          </div>
        </v-card-text>
        <v-card-actions class="px-4 pb-4">
          <v-spacer />
          <v-btn color="primary" variant="flat" rounded="lg" prepend-icon="mdi-lock-reset" :loading="pwdSaving" @click="changerMotDePasse">
            Changer le mot de passe
          </v-btn>
        </v-card-actions>
      </v-card>
    </template>
  </div>
</template>

<style scoped>
.profil-page { max-width: 760px; margin: 0 auto; padding-bottom: 48px; }

.profil-hero { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
.profil-hero__photo {
  width: 76px; height: 76px; border-radius: 50%; flex-shrink: 0; overflow: hidden;
  background: linear-gradient(135deg, #16a34a, #15803d);
  display: flex; align-items: center; justify-content: center;
}
.profil-hero__photo img { width: 100%; height: 100%; object-fit: cover; }
.profil-hero__initials { color: #fff; font-size: 1.4rem; font-weight: 700; }
.profil-hero__meta { flex: 1; min-width: 180px; }
.profil-hero__name { font-size: 1.05rem; font-weight: 700; color: #111827; margin: 0 0 2px; }
.profil-hero__email { font-size: 0.82rem; color: #9ca3af; margin: 0 0 8px; }
.profil-hero__roles { display: flex; flex-wrap: wrap; gap: 4px; }
.profil-hero__role { font-size: 0.66rem; font-weight: 600; padding: 2px 8px; border-radius: 100px; background: #dcfce7; color: #16a34a; }
.profil-hero__action { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.profil-hero__hint { font-size: 0.7rem; color: #9ca3af; margin: 0; }

.profil-form { display: flex; flex-direction: column; gap: 16px; }
</style>
