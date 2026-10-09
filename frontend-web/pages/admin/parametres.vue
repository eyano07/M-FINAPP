<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

import { useParametresStore } from '~/stores/parametres'
import { MODELES_ENTETE, MODELE_ENTETE_DEFAUT, type ModeleEntete } from '~/composables/useModeleEntete'

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
  adresse: '',
  telephone: '',
  email: '',
  rccm: '',
  idNat: '',
  nif: '',
  couleurPrimaire: '#15803D',
  modeleEntete: MODELE_ENTETE_DEFAUT as ModeleEntete,
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    await parametresStore.charger()
    form.nom = parametresStore.parametres.nom || ''
    form.nomComplet = parametresStore.parametres.nomComplet || ''
    form.slogan = parametresStore.parametres.slogan || ''
    form.adresse = parametresStore.parametres.adresse || ''
    form.telephone = parametresStore.parametres.telephone || ''
    form.email = parametresStore.parametres.email || ''
    form.rccm = parametresStore.parametres.rccm || ''
    form.idNat = parametresStore.parametres.idNat || ''
    form.nif = parametresStore.parametres.nif || ''
    form.couleurPrimaire = parametresStore.parametres.couleurPrimaire || '#15803D'
    form.modeleEntete = parametresStore.parametres.modeleEntete || MODELE_ENTETE_DEFAUT
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
  if (!estHexValide(form.couleurPrimaire)) {
    erreur.value = 'La couleur doit être un code hexadécimal valide (ex. #15803D).'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/parametres', {
      method: 'PUT',
      body: {
        nom: form.nom, nomComplet: form.nomComplet || null, slogan: form.slogan || null,
        adresse: form.adresse || null, telephone: form.telephone || null,
        email: form.email || null, rccm: form.rccm || null, idNat: form.idNat || null, nif: form.nif || null,
        couleurPrimaire: form.couleurPrimaire,
        modeleEntete: form.modeleEntete,
      },
    })
    await parametresStore.charger()
    succes.value = 'Paramètres enregistrés.'
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

// ── Couleur de marque ──────────────────────────────────────────────────
// Palette de depart proposee en raccourci ; l'utilisateur reste libre de
// saisir n'importe quel hex via le champ texte ou le selecteur natif.
const COULEURS_PREDEFINIES = [
  '#15803D', '#2563EB', '#4F46E5', '#7C3AED', '#DB2777',
  '#DC2626', '#EA580C', '#D97706', '#0891B2', '#0D9488',
]

function estHexValide(c: string) {
  return /^#[0-9A-Fa-f]{6}$/.test(c)
}

// Apercu en direct pendant la saisie (theme Vuetify + variable CSS), avant
// tout enregistrement — un hex incomplet en cours de frappe est simplement
// ignore plutot que d'appliquer une couleur invalide.
watch(() => form.couleurPrimaire, (c) => {
  if (estHexValide(c)) parametresStore.previsualiserCouleur(c)
})

// Si l'utilisateur quitte la page sans enregistrer, l'apercu ne doit pas
// rester applique ailleurs dans l'appli : on restaure la couleur reellement
// enregistree (no-op si l'enregistrement a reussi, puisque le store porte
// alors deja la meme valeur).
onBeforeUnmount(() => {
  parametresStore.previsualiserCouleur(parametresStore.parametres.couleurPrimaire || '#15803D')
  parametresStore.previsualiserModeleEntete(parametresStore.parametres.modeleEntete || MODELE_ENTETE_DEFAUT)
})

// ── Papier a en-tete des documents ─────────────────────────────────────
// Le modele choisi s'applique aux impressions des que l'on clique (apercu,
// comme la couleur) et aux PDF du serveur une fois enregistre.
watch(() => form.modeleEntete, m => parametresStore.previsualiserModeleEntete(m))

const apercuPdfEnCours = ref(false)
/** Papier a en-tete genere par le serveur avec le modele et la couleur affiches, meme non enregistres. */
async function apercuPdf() {
  const onglet = window.open('', '_blank')
  apercuPdfEnCours.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ type: 'GENERAL', orientation: 'PORTRAIT', nombrePages: '1', modele: form.modeleEntete })
    if (estHexValide(form.couleurPrimaire)) params.set('couleur', form.couleurPrimaire)
    const reponse = await api.raw<Blob>(`/papier-entete/pdf?${params}`, { responseType: 'blob' })
    const url = URL.createObjectURL(reponse._data as Blob)
    if (onglet) onglet.location.href = url
    else window.open(url, '_blank')
  } catch (e: any) {
    onglet?.close()
    erreur.value = messageErreurApi(e, "L'aperçu PDF n'a pas pu être généré.")
  } finally {
    apercuPdfEnCours.value = false
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
          <div class="param-field">
            <label class="param-label">Adresse</label>
            <v-text-field v-model="form.adresse" placeholder="ex: 12 Avenue du Commerce, Kinshasa/Gombe" hide-details="auto" />
          </div>
          <div class="param-field">
            <label class="param-label">Téléphone</label>
            <v-text-field v-model="form.telephone" placeholder="ex: +243 000 000 000" hide-details="auto" />
          </div>
          <div class="param-field">
            <label class="param-label">Email</label>
            <v-text-field
              v-model="form.email"
              placeholder="ex: contact@societe.com"
              hide-details="auto"
              hint="Plusieurs adresses possibles, séparées par « · »"
              persistent-hint
            />
          </div>
        </div>
      </div>

      <!-- ── Registre legal ──────────────────────────────────── -->
      <div class="param-card">
        <p class="param-card__title">
          <v-icon icon="mdi-bank-outline" size="16" class="mr-2" />
          Registre légal
        </p>

        <div v-if="loading" class="param-loading">
          <v-progress-circular indeterminate color="primary" size="24" />
        </div>

        <div v-else class="param-fields">
          <div class="param-field">
            <label class="param-label">RCCM</label>
            <v-text-field v-model="form.rccm" placeholder="ex: CD/LSH/RCCM/24-B-1162" hide-details="auto" />
          </div>
          <div class="param-field">
            <label class="param-label">ID. Nat</label>
            <v-text-field v-model="form.idNat" placeholder="ex: 05-B-0500-N49144X" hide-details="auto" />
          </div>
          <div class="param-field">
            <label class="param-label">NIF</label>
            <v-text-field v-model="form.nif" placeholder="ex: A2420976C" hide-details="auto" />
          </div>
        </div>
        <p class="param-registre-hint">
          Affichés sur le papier à en-tête des documents (PDF et impressions).
        </p>
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

      <!-- ── Couleur de marque ─────────────────────────────────── -->
      <div class="param-card">
        <p class="param-card__title">
          <v-icon icon="mdi-palette-outline" size="16" class="mr-2" />
          Couleur de l'interface
        </p>

        <div class="param-color-row">
          <label class="param-color-swatch" :style="{ background: estHexValide(form.couleurPrimaire) ? form.couleurPrimaire : '#e5e7eb' }">
            <input v-model="form.couleurPrimaire" type="color" class="param-color-native">
          </label>
          <v-text-field
            v-model="form.couleurPrimaire"
            placeholder="#15803D"
            hide-details="auto"
            maxlength="7"
          />
        </div>

        <div class="param-color-presets">
          <button
            v-for="c in COULEURS_PREDEFINIES"
            :key="c"
            type="button"
            class="param-color-preset"
            :class="{ 'is-active': form.couleurPrimaire.toLowerCase() === c.toLowerCase() }"
            :style="{ background: c }"
            :title="c"
            @click="form.couleurPrimaire = c"
          />
        </div>

        <p class="param-logo-hint">
          S'applique aux boutons, liens et accents dans toute l'application, ainsi qu'à tous les documents imprimés et PDF.
        </p>
      </div>

      <!-- ── Papier a en-tete ──────────────────────────────────── -->
      <div class="param-card param-card--large">
        <div class="param-entete-tete">
          <p class="param-card__title mb-0">
            <v-icon icon="mdi-file-document-outline" size="16" class="mr-2" />
            Papier à en-tête des documents
          </p>
          <v-btn
            variant="tonal"
            color="primary"
            rounded="lg"
            prepend-icon="mdi-file-pdf-box"
            :loading="apercuPdfEnCours"
            @click="apercuPdf"
          >
            Aperçu PDF
          </v-btn>
        </div>
        <p class="param-entete-intro">
          Le modèle choisi et la couleur ci-dessus habillent tous les documents : PDF (papier à en-tête, ordres de mission,
          budgets) et impressions depuis le navigateur (états financiers, notes de frais, factures…).
        </p>
        <div class="param-entete-grille" role="radiogroup" aria-label="Modèle de papier à en-tête">
          <button
            v-for="m in MODELES_ENTETE"
            :key="m.value"
            type="button"
            role="radio"
            class="param-entete-choix"
            :class="{ 'is-active': form.modeleEntete === m.value }"
            :aria-checked="form.modeleEntete === m.value"
            @click="form.modeleEntete = m.value"
          >
            <ParametresApercuEntete
              :modele="m.value"
              :couleur="estHexValide(form.couleurPrimaire) ? form.couleurPrimaire : '#15803D'"
              :nom="form.nom"
              :logo-url="parametresStore.parametres.logoUrl"
            />
            <span class="param-entete-choix__titre">
              <v-icon v-if="form.modeleEntete === m.value" icon="mdi-check-circle" size="16" color="primary" />
              {{ m.titre }}
            </span>
            <span class="param-entete-choix__desc">{{ m.description }}</span>
          </button>
        </div>
      </div>
    </div>

    <v-btn
      color="primary"
      block
      rounded="lg"
      elevation="0"
      size="large"
      class="param-save-btn mt-5"
      :loading="saving"
      :disabled="loading"
      prepend-icon="mdi-content-save-outline"
      @click="enregistrer"
    >
      Enregistrer
    </v-btn>
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
  background: linear-gradient(140deg, var(--color-primary) 0%, var(--color-primary-dark) 50%, var(--color-primary-darker) 100%);
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
.param-registre-hint { font-size: 0.75rem; color: #9ca3af; margin: 14px 0 0; }

/* ── Couleur de marque ───────────────────────────────────── */
.param-color-row { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.param-color-swatch {
  position: relative;
  width: 44px; height: 44px;
  flex-shrink: 0;
  border-radius: 12px;
  border: 1.5px solid #e5e7eb;
  overflow: hidden;
  cursor: pointer;
}
.param-color-native {
  position: absolute;
  inset: -4px;
  width: calc(100% + 8px);
  height: calc(100% + 8px);
  border: none;
  padding: 0;
  cursor: pointer;
}
.param-color-presets { display: flex; flex-wrap: wrap; gap: 10px; }
.param-color-preset {
  width: 28px; height: 28px;
  border-radius: 50%;
  border: 2px solid #fff;
  outline: 1.5px solid #e5e7eb;
  cursor: pointer;
  padding: 0;
  transition: transform 0.12s ease, outline-color 0.12s ease;
}
.param-color-preset:hover { transform: scale(1.12); }
.param-card--large { grid-column: 1 / -1; }
.param-entete-tete { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.param-entete-intro { font-size: 0.8125rem; color: #4b5563; margin: 0 0 16px; }
.param-entete-grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(140px, 100%), 1fr)); gap: 14px; }
.param-entete-choix {
  display: flex; flex-direction: column; gap: 6px; text-align: left; padding: 10px; border-radius: 12px;
  border: 2px solid #e5e7eb; background: #f9fafb; cursor: pointer; font: inherit; transition: border-color 0.15s, background 0.15s;
}
.param-entete-choix:hover { border-color: #9ca3af; }
.param-entete-choix:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.param-entete-choix.is-active { border-color: var(--color-primary); background: var(--color-primary-lighter); }
.param-entete-choix__titre { display: flex; align-items: center; gap: 4px; font-size: 0.875rem; font-weight: 700; color: #111827; margin-top: 4px; }
.param-entete-choix__desc { font-size: 0.75rem; color: #4b5563; line-height: 1.35; }
.param-color-preset.is-active { outline: 2px solid #111827; outline-offset: 1px; }
</style>
