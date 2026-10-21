<script setup lang="ts">
// Pavé numérique à l'écran pour la saisie tactile (tablette) : il n'écrit rien
// lui-même, il émet la touche pressée et la page l'applique au champ actif.
defineProps<{
  titre?: string
  valeur?: string
  actif?: boolean
}>()
const emit = defineEmits<{ (e: 'touche', k: string): void }>()

const touches = ['7', '8', '9', '4', '5', '6', '1', '2', '3', '.', '0', '00']
</script>

<template>
  <v-card class="pave classroom-card pa-3">
    <p class="pave__titre">{{ titre || 'Saisie du montant' }}</p>
    <div class="pave__ecran" :class="{ 'pave__ecran--off': !actif }">
      {{ actif ? (valeur || '0') : 'Touchez un champ' }}
    </div>
    <div class="pave__grille">
      <!-- mousedown.prevent : garde le focus sur le champ en cours de saisie -->
      <v-btn
        v-for="t in touches"
        :key="t"
        variant="tonal"
        color="primary"
        size="x-large"
        class="pave__touche"
        :disabled="!actif"
        @mousedown.prevent
        @click="emit('touche', t)"
      >
        {{ t }}
      </v-btn>
      <v-btn
        variant="tonal"
        color="error"
        size="x-large"
        class="pave__touche"
        :disabled="!actif"
        aria-label="Tout effacer"
        @mousedown.prevent
        @click="emit('touche', 'clear')"
      >
        C
      </v-btn>
      <v-btn
        variant="tonal"
        color="warning"
        size="x-large"
        class="pave__touche"
        :disabled="!actif"
        aria-label="Effacer le dernier chiffre"
        @mousedown.prevent
        @click="emit('touche', 'back')"
      >
        <v-icon icon="mdi-backspace-outline" />
      </v-btn>
      <v-btn
        color="primary"
        size="x-large"
        class="pave__touche pave__ok"
        :disabled="!actif"
        @mousedown.prevent
        @click="emit('touche', 'suivant')"
      >
        <v-icon icon="mdi-arrow-right" class="mr-1" />Champ suivant
      </v-btn>
    </div>
  </v-card>
</template>

<style scoped>
.pave { user-select: none; touch-action: manipulation; }
.pave__titre {
  font-size: 0.72rem;
  font-weight: 700;
  color: #9ca3af;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 8px;
}
.pave__ecran {
  font-size: 1.6rem;
  font-weight: 700;
  text-align: right;
  padding: 8px 12px;
  margin-bottom: 10px;
  border-radius: 10px;
  background: #f3f4f6;
  color: #111827;
  min-height: 52px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pave__ecran--off { font-size: 0.9rem; font-weight: 500; color: #9ca3af; line-height: 36px; }
/* minmax(0, 1fr) et min-width: 0 : sans eux, les boutons « x-large » de Vuetify (largeur minimale d'environ 92 px) debordent de la
   carte de 260 px et la troisieme colonne (9, 6, 3, 00) est coupee. */
.pave__grille { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.pave__touche { height: 64px !important; min-width: 0 !important; padding: 0 !important; font-size: 1.4rem; font-weight: 600; }
.pave__ok { grid-column: 1 / -1; height: 56px !important; font-size: 1rem; }
</style>
