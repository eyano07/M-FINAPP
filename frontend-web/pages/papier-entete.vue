<script setup lang="ts">
// Auto-service, comme /profil : accessible a tout utilisateur connecte,
// aucune restriction de role ni de module (voir AppBar.vue pour le lien).
definePageMeta({})

const api = useApi()

type Type = 'GENERAL' | 'INDIVIDUEL'
type Orientation = 'PORTRAIT' | 'PAYSAGE'
type Mode = 'SIMPLE' | 'MULTIPLE'

const type = ref<Type>('GENERAL')
const orientation = ref<Orientation>('PORTRAIT')
const mode = ref<Mode>('SIMPLE')
const nombrePages = ref(2)

const generation = ref(false)
const erreur = ref('')

/**
 * Ouvre le PDF genere dans un nouvel onglet, meme mecanique que l'impression
 * de l'ordre de mission (pages/drh/missions/[id].vue) : onglet ouvert de
 * facon synchrone, avant le premier await, pour eviter le blocage popup de
 * certains navigateurs sur une ouverture differee.
 */
async function generer() {
  const onglet = window.open('', '_blank')
  generation.value = true
  erreur.value = ''
  try {
    const n = mode.value === 'MULTIPLE' ? Math.max(1, Math.min(50, Math.round(nombrePages.value) || 1)) : 1
    const params = new URLSearchParams({ type: type.value, orientation: orientation.value, nombrePages: String(n) })
    const reponse = await api.raw<Blob>(`/papier-entete/pdf?${params}`, { responseType: 'blob' })
    const url = URL.createObjectURL(reponse._data as Blob)
    if (onglet) {
      onglet.location.href = url
    } else {
      window.open(url, '_blank')
    }
  } catch (e: any) {
    onglet?.close()
    erreur.value = messageErreurApi(e, 'Impossible de générer le papier à en-tête.')
  } finally {
    generation.value = false
  }
}
</script>

<template>
  <div class="pe-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Papier à en-tête</h1>
        <p class="page-sub">Imprimez du papier à en-tête vierge, prêt pour la rédaction</p>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-card rounded="lg" border flat class="pe-card">
      <v-card-text class="pe-body">
        <!-- ── Type ────────────────────────────────────────────────── -->
        <div class="pe-section">
          <p class="pe-section__label">Type d'en-tête</p>
          <div class="pe-choices">
            <button
              type="button" class="pe-choice" :class="{ 'pe-choice--on': type === 'GENERAL' }"
              @click="type = 'GENERAL'"
            >
              <v-icon icon="mdi-office-building-outline" size="22" />
              <span class="pe-choice__title">Général</span>
              <span class="pe-choice__desc">En-tête de l'entreprise</span>
            </button>
            <button
              type="button" class="pe-choice" :class="{ 'pe-choice--on': type === 'INDIVIDUEL' }"
              @click="type = 'INDIVIDUEL'"
            >
              <v-icon icon="mdi-account-box-outline" size="22" />
              <span class="pe-choice__title">Individuel</span>
              <span class="pe-choice__desc">+ votre nom, fonction et affectation</span>
            </button>
          </div>
          <p v-if="type === 'INDIVIDUEL'" class="pe-hint">
            Affiche votre fonction et votre affectation si votre administrateur les a renseignées,
            ainsi que votre téléphone et votre e-mail en pied de page (l'adresse reste celle de l'entreprise).
          </p>
        </div>

        <!-- ── Orientation ─────────────────────────────────────────── -->
        <div class="pe-section">
          <p class="pe-section__label">Orientation</p>
          <div class="pe-choices">
            <button
              type="button" class="pe-choice" :class="{ 'pe-choice--on': orientation === 'PORTRAIT' }"
              @click="orientation = 'PORTRAIT'"
            >
              <v-icon icon="mdi-file-outline" size="22" />
              <span class="pe-choice__title">Portrait</span>
            </button>
            <button
              type="button" class="pe-choice" :class="{ 'pe-choice--on': orientation === 'PAYSAGE' }"
              @click="orientation = 'PAYSAGE'"
            >
              <v-icon icon="mdi-file-outline" size="22" class="pe-icon-rotate" />
              <span class="pe-choice__title">Paysage</span>
            </button>
          </div>
        </div>

        <!-- ── Nombre de pages ─────────────────────────────────────── -->
        <div class="pe-section">
          <p class="pe-section__label">Nombre de pages</p>
          <div class="pe-choices">
            <button
              type="button" class="pe-choice pe-choice--row" :class="{ 'pe-choice--on': mode === 'SIMPLE' }"
              @click="mode = 'SIMPLE'"
            >
              <v-icon icon="mdi-file-outline" size="20" />
              <span class="pe-choice__title">Simple <span class="pe-choice__desc">— une page</span></span>
            </button>
            <button
              type="button" class="pe-choice pe-choice--row" :class="{ 'pe-choice--on': mode === 'MULTIPLE' }"
              @click="mode = 'MULTIPLE'"
            >
              <v-icon icon="mdi-file-multiple-outline" size="20" />
              <span class="pe-choice__title">Multiple <span class="pe-choice__desc">— plusieurs pages</span></span>
            </button>
          </div>
          <v-text-field
            v-if="mode === 'MULTIPLE'"
            v-model.number="nombrePages"
            type="number" min="1" max="50"
            label="Nombre de pages à imprimer"
            variant="outlined" density="comfortable" rounded="lg"
            hide-details="auto" class="pe-nombre-pages"
          />
        </div>
      </v-card-text>

      <v-card-actions class="pe-footer">
        <v-spacer />
        <v-btn
          color="primary" variant="flat" rounded="lg" size="large"
          prepend-icon="mdi-printer-outline" :loading="generation" @click="generer"
        >
          Générer le PDF
        </v-btn>
      </v-card-actions>
    </v-card>
  </div>
</template>

<style scoped>
.pe-page { max-width: 720px; margin: 0 auto; padding-bottom: 48px; }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }

.pe-card { overflow: visible; }
.pe-body { display: flex; flex-direction: column; gap: 26px; padding: 26px; }

.pe-section__label {
  font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px;
  color: #9ca3af; margin: 0 0 10px;
}

.pe-choices { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.pe-choice {
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
  padding: 14px 16px; border-radius: 14px; border: 1.5px solid #e5e7eb; background: #fff;
  color: #6b7280; cursor: pointer; text-align: left; transition: all 0.15s;
}
.pe-choice:hover { border-color: #d1d5db; background: #f9fafb; }
.pe-choice--on { border-color: #16a34a; background: #f0fdf4; color: #16a34a; }
.pe-choice__title { font-size: 0.9rem; font-weight: 700; color: #111827; }
.pe-choice--on .pe-choice__title { color: #15803d; }
.pe-choice__desc { font-size: 0.74rem; font-weight: 500; color: #9ca3af; }

.pe-choice--row { flex-direction: row; align-items: center; gap: 10px; }
.pe-choice--row .pe-choice__title { display: flex; align-items: baseline; gap: 4px; }

.pe-icon-rotate { transform: rotate(90deg); }

.pe-hint {
  font-size: 0.78rem; color: #6b7280; background: #f9fafb; border-radius: 10px;
  padding: 10px 12px; margin: 10px 0 0; line-height: 1.5;
}

.pe-nombre-pages { margin-top: 12px; max-width: 260px; }

.pe-footer { padding: 16px 26px 26px; }

@media (max-width: 480px) {
  .pe-choices { grid-template-columns: 1fr; }
}
</style>
