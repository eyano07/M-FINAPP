<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

import { useParametresStore } from '~/stores/parametres'

const api = useApi()
const parametresStore = useParametresStore()

const loading = ref(true)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

const form = reactive({
  nom: '',
  nomComplet: '',
  slogan: '',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    await parametresStore.charger()
    form.nom = parametresStore.parametres.nom || ''
    form.nomComplet = parametresStore.parametres.nomComplet || ''
    form.slogan = parametresStore.parametres.slogan || ''
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger les paramètres.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)

async function enregistrer() {
  if (!form.nom.trim()) {
    erreur.value = 'Le nom de la société est obligatoire.'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/parametres', {
      method: 'PUT',
      body: { nom: form.nom, nomComplet: form.nomComplet || null, slogan: form.slogan || null },
    })
    await parametresStore.charger()
    succes.value = 'Paramètres enregistrés.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

// ── Logo ────────────────────────────────────────────────────────────────
const TYPES_AUTORISES = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/svg+xml']
const TAILLE_MAX_OCTETS = 5 * 1024 * 1024
const logoInput = ref<HTMLInputElement | null>(null)
const uploadingLogo = ref(false)

function declencherChoixLogo() {
  logoInput.value?.click()
}

async function onLogoChoisi(e: Event) {
  const input = e.target as HTMLInputElement
  const fichier = input.files?.[0]
  input.value = ''
  if (!fichier) return

  if (!TYPES_AUTORISES.includes(fichier.type)) {
    erreur.value = `Format non autorisé pour « ${fichier.name} ». Formats acceptés : JPEG, PNG, WEBP, SVG.`
    return
  }
  if (fichier.size > TAILLE_MAX_OCTETS) {
    erreur.value = `« ${fichier.name} » dépasse la taille maximale autorisée (5 Mo).`
    return
  }

  uploadingLogo.value = true
  erreur.value = ''
  succes.value = ''
  try {
    const formData = new FormData()
    formData.append('fichier', fichier)
    await api('/parametres/logo', { method: 'POST', body: formData })
    await parametresStore.charger()
    succes.value = 'Logo mis à jour.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'envoi du logo.")
  } finally {
    uploadingLogo.value = false
  }
}
</script>

<template>
  <div class="param-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Paramètres</h1>
        <p class="page-sub">Identité de l'entreprise : nom, logo et slogan affichés dans l'application</p>
      </div>
    </div>

    <!-- ── Aperçu ──────────────────────────────────────────── -->
    <div class="param-hero">
      <div class="param-hero__blob param-hero__blob--a" />
      <div class="param-hero__blob param-hero__blob--b" />
      <div class="param-hero__logo">
        <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
        <v-icon v-else icon="mdi-domain" size="26" color="white" />
      </div>
      <div class="param-hero__text">
        <p class="param-hero__nom">{{ form.nom || 'MBSC Finapp' }}</p>
        <p v-if="form.nomComplet" class="param-hero__complet">{{ form.nomComplet }}</p>
        <p v-if="form.slogan" class="param-hero__slogan">{{ form.slogan }}</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>
    <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
      {{ succes }}
    </v-alert>

    <div class="param-layout">
      <!-- ── Formulaire identite ─────────────────────────────── -->
      <div class="param-card">
        <p class="param-card__title">
          <v-icon icon="mdi-pencil-outline" size="16" class="mr-2" />
          Identité
        </p>

        <div v-if="loading" class="param-loading">
          <v-progress-circular indeterminate color="primary" size="24" />
        </div>

        <div v-else class="param-fields">
          <div class="param-field">
            <label class="param-label">Nom de la société *</label>
            <v-text-field v-model="form.nom" placeholder="ex: MBSC Finapp" hide-details="auto" />
          </div>
          <div class="param-field">
            <label class="param-label">Nom complet</label>
            <v-text-field
              v-model="form.nomComplet"
              placeholder="ex: Mines et Business Solutions Congo"
              hide-details="auto"
              hint="Si le nom ci-dessus est un sigle ou une abréviation"
              persistent-hint
            />
          </div>
          <div class="param-field">
            <label class="param-label">Slogan</label>
            <v-text-field v-model="form.slogan" placeholder="ex: La finance d'entreprise, simplement." hide-details="auto" />
          </div>
        </div>

        <v-btn
          color="primary"
          block
          rounded="lg"
          elevation="0"
          size="large"
          class="param-save-btn"
          :loading="saving"
          :disabled="loading"
          prepend-icon="mdi-content-save-outline"
          @click="enregistrer"
        >
          Enregistrer
        </v-btn>
      </div>

      <!-- ── Logo ────────────────────────────────────────────── -->
      <div class="param-card">
        <p class="param-card__title">
          <v-icon icon="mdi-image-outline" size="16" class="mr-2" />
          Logo
        </p>

        <div class="param-logo-preview">
          <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo actuel">
          <div v-else class="param-logo-preview__empty">
            <v-icon icon="mdi-image-off-outline" size="30" color="#d1d5db" />
            <span>Aucun logo configuré</span>
          </div>
        </div>

        <v-btn
          variant="tonal"
          color="primary"
          block
          rounded="lg"
          class="mt-4"
          :loading="uploadingLogo"
          prepend-icon="mdi-upload-outline"
          @click="declencherChoixLogo"
        >
          {{ parametresStore.parametres.logoUrl ? 'Remplacer le logo' : 'Ajouter un logo' }}
        </v-btn>
        <input
          ref="logoInput"
          type="file"
          accept="image/jpeg,image/png,image/webp,image/svg+xml"
          class="param-hidden-input"
          @change="onLogoChoisi"
        >
        <p class="param-logo-hint">Formats acceptés : JPEG, PNG, WEBP, SVG (5 Mo max).</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.param-page { max-width: 960px; margin: 0 auto; padding-bottom: 48px; }

/* ── Aperçu ──────────────────────────────────────────────── */
.param-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px 28px;
  background: linear-gradient(140deg, #16a34a 0%, #15803d 50%, #14532d 100%);
  border-radius: 20px;
  margin-bottom: 24px;
}
.param-hero__blob { position: absolute; border-radius: 50%; background: rgba(255,255,255,0.08); pointer-events: none; }
.param-hero__blob--a { width: 180px; height: 180px; top: -50px; right: -50px; }
.param-hero__blob--b { width: 100px; height: 100px; bottom: -30px; left: -20px; }

.param-hero__logo {
  position: relative; z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px; height: 56px;
  border-radius: 14px;
  background: rgba(255,255,255,0.18);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22);
  flex-shrink: 0;
  overflow: hidden;
}
.param-hero__logo img { width: 100%; height: 100%; object-fit: contain; padding: 6px; }

.param-hero__text { position: relative; z-index: 1; min-width: 0; }
.param-hero__nom { font-size: 1.3rem; font-weight: 800; color: #fff; margin: 0; letter-spacing: -0.3px; }
.param-hero__complet { font-size: 0.82rem; color: rgba(255,255,255,0.78); margin: 2px 0 0; }
.param-hero__slogan { font-size: 0.82rem; color: rgba(255,255,255,0.65); margin: 4px 0 0; font-style: italic; }

/* ── Layout ──────────────────────────────────────────────── */
.param-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
@media (max-width: 760px) { .param-layout { grid-template-columns: 1fr; } }

.param-card { background: #fff; border: 1px solid #f0f0f0; border-radius: 18px; padding: 22px; }
.param-card__title {
  display: flex; align-items: center;
  font-size: 0.78rem; font-weight: 700; letter-spacing: 0.4px; text-transform: uppercase;
  color: #9ca3af; margin: 0 0 18px; padding-bottom: 14px; border-bottom: 1px solid #f3f4f6;
}

.param-loading { display: flex; justify-content: center; padding: 32px 0; }

.param-fields { display: flex; flex-direction: column; gap: 16px; margin-bottom: 20px; }
.param-field { display: flex; flex-direction: column; gap: 6px; }
.param-label { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.param-save-btn { height: 50px !important; font-size: 0.9375rem !important; font-weight: 600 !important; }

/* ── Logo ────────────────────────────────────────────────── */
.param-logo-preview {
  display: flex; align-items: center; justify-content: center;
  height: 140px;
  border: 1.5px dashed #e5e7eb;
  border-radius: 14px;
  background: #fafafa;
  overflow: hidden;
}
.param-logo-preview img { max-width: 100%; max-height: 100%; object-fit: contain; padding: 12px; }
.param-logo-preview__empty {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  color: #d1d5db; font-size: 0.8rem;
}
.param-hidden-input { display: none; }
.param-logo-hint { font-size: 0.75rem; color: #9ca3af; margin: 10px 0 0; text-align: center; }
</style>
