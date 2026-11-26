<script setup lang="ts">
// Boîte unique de confirmation et de saisie de l'application (voir composables/useDialogues.ts) : montée une fois dans
// app.vue, elle remplace confirm() et prompt() du navigateur. Une demande à la fois ; la promesse de l'appelant est
// résolue à la validation (true / le texte saisi) ou à l'abandon (false / null : bouton, Échap, clic à côté).
import type { DemandeDialogue, NatureDialogue } from '~/composables/useDialogues'

const NATURES: Record<NatureDialogue, { icone: string, couleur: string }> = {
  danger: { icone: 'mdi-alert-circle-outline', couleur: 'error' },
  attention: { icone: 'mdi-alert-outline', couleur: 'warning' },
  info: { icone: 'mdi-help-circle-outline', couleur: 'primary' },
}

const ouvert = computed(() => demandeDialogue.value !== null)

// Le contenu reste affiché pendant le fondu de fermeture : on garde la dernière demande jusqu'à la suivante.
const affichee = shallowRef<DemandeDialogue | null>(null)
const texte = ref('')
watch(demandeDialogue, (d) => {
  if (!d) return
  affichee.value = d
  texte.value = ''
})

const nature = computed<NatureDialogue>(() => affichee.value?.options.nature ?? 'info')
const reglage = computed(() => NATURES[nature.value])
const icone = computed(() => affichee.value?.options.icone ?? reglage.value.icone)
const paragraphes = computed(() => {
  const m = affichee.value?.options.message
  return !m ? [] : Array.isArray(m) ? m : [m]
})
const saisie = computed(() => affichee.value?.saisie ?? null)
const peutValider = computed(() => !saisie.value?.obligatoire || texte.value.trim().length > 0)

function fermer(valeur: boolean | string | null) {
  const d = demandeDialogue.value
  if (!d) return
  demandeDialogue.value = null
  d.resoudre(valeur)
}
function valider() {
  if (!peutValider.value) return
  fermer(saisie.value ? texte.value.trim() : true)
}
function abandonner() {
  fermer(saisie.value ? null : false)
}

// Focus à l'ouverture : sur l'abandon quand l'action est destructrice (un Entrée distrait ne supprime rien), sur la
// validation sinon. Un champ de saisie prend lui-même le focus (autofocus).
const boutonAbandon = ref<{ $el: HTMLElement } | null>(null)
const boutonValider = ref<{ $el: HTMLElement } | null>(null)
function donnerLeFocus() {
  if (saisie.value) return
  nextTick(() => (nature.value === 'info' ? boutonValider : boutonAbandon).value?.$el?.focus())
}
</script>

<template>
  <v-dialog
    :model-value="ouvert"
    max-width="480"
    :persistent="!!saisie"
    aria-labelledby="boite-dialogue-titre"
    :aria-describedby="paragraphes.length ? 'boite-dialogue-message' : undefined"
    @update:model-value="(v: boolean) => { if (!v) abandonner() }"
    @after-enter="donnerLeFocus"
  >
    <v-card v-if="affichee" class="boite" :class="`boite--${nature}`" @keydown.esc="abandonner">
      <div class="boite__corps">
        <span class="boite__icone" aria-hidden="true">
          <v-icon :icon="icone" size="24" />
        </span>
        <div class="boite__textes">
          <h2 id="boite-dialogue-titre" class="boite__titre">{{ affichee.options.titre }}</h2>
          <div v-if="paragraphes.length" id="boite-dialogue-message" class="boite__message">
            <p v-for="(p, i) in paragraphes" :key="i">{{ p }}</p>
          </div>
        </div>
      </div>

      <div v-if="saisie" class="boite__saisie">
        <v-textarea
          v-if="saisie.multiligne !== false"
          v-model="texte"
          :label="saisie.libelle"
          :placeholder="saisie.placeholder"
          :maxlength="saisie.maxlength"
          rows="3"
          max-rows="8"
          auto-grow
          autofocus
          variant="outlined"
          density="comfortable"
          hide-details
          @keydown.ctrl.enter.prevent="valider"
          @keydown.meta.enter.prevent="valider"
        />
        <v-text-field
          v-else
          v-model="texte"
          :label="saisie.libelle"
          :placeholder="saisie.placeholder"
          :maxlength="saisie.maxlength"
          :inputmode="saisie.inputmode"
          autocomplete="off"
          autofocus
          hide-details
          @keydown.enter.prevent="valider"
        />
      </div>

      <div class="boite__actions">
        <v-btn ref="boutonAbandon" variant="text" class="boite__bouton" @click="abandonner">
          {{ affichee.options.abandon || 'Annuler' }}
        </v-btn>
        <v-btn
          ref="boutonValider"
          :color="reglage.couleur"
          variant="flat"
          class="boite__bouton boite__valider"
          :disabled="!peutValider"
          @click="valider"
        >
          {{ affichee.options.bouton || 'Confirmer' }}
        </v-btn>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.boite {
  --boite-accent: var(--color-primary);
  --boite-accent-fond: var(--color-primary-light);
  padding: 24px 24px 20px;
  border: 0 !important;
  border-radius: var(--mbsc-radius, 16px) !important;
  box-shadow: 0 24px 48px -12px rgba(17, 24, 39, 0.35), 0 0 0 1px rgba(17, 24, 39, 0.05) !important;
}
.boite--danger {
  --boite-accent: #dc2626;
  --boite-accent-fond: #fee2e2;
}
.boite--attention {
  --boite-accent: #b45309;
  --boite-accent-fond: #fef3c7;
}

.boite__corps {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}
.boite__icone {
  flex: none;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--boite-accent-fond);
  color: var(--boite-accent);
}
.boite__textes {
  min-width: 0;
  flex: 1;
}
.boite__titre {
  margin: 2px 0 0;
  font-size: 1.125rem;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: -0.2px;
  color: var(--ink);
  overflow-wrap: anywhere;
  text-wrap: balance;
}
.boite__message {
  margin-top: 8px;
  font-size: 0.9375rem;
  line-height: 1.55;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
  text-wrap: pretty;
}
.boite__message p + p {
  margin-top: 8px;
}
.boite__saisie {
  margin-top: 20px;
}

.boite__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}
.boite__bouton {
  min-height: 40px;
  padding-inline: 18px;
  font-weight: 600;
}
/* Validation inactive (motif obligatoire encore vide) : un gris lisible, pas la couleur de la nature à 26 %. */
.boite__valider.v-btn--disabled {
  opacity: 1;
  background-color: #e5e7eb !important;
  color: var(--ink-faint) !important;
}
/* Ambre : le blanc sur #d97706 (3,2:1) ne se lit pas bien ; le bouton prend la teinte foncée de l'icône. */
.boite--attention .boite__valider:not(:disabled) {
  background-color: var(--boite-accent) !important;
  color: #fff !important;
}

@media (max-width: 480px) {
  .boite {
    padding: 20px 18px 16px;
  }
  .boite__actions {
    flex-direction: column-reverse;
  }
  .boite__bouton {
    width: 100%;
  }
}
</style>
