<script setup lang="ts">
/**
 * Ligne de saisie répétée (compte + débit + crédit, ingrédient + quantité…).
 * PC : une rangée, les champs côte à côte et le bouton « retirer » au bout.
 * Téléphone et tablette (< 960 px) : une carte — « Ligne n » et le bouton retirer en tête, puis les champs en deux colonnes.
 *
 * Les champs se passent en contenu : `.ls-large` = champ principal (prend toute la largeur restante, toute la largeur de la carte
 * sur téléphone) ; `.ls-montant` = champ court (160 px sur PC, une demi-largeur sur téléphone) ; `.ls-court` = champ plus court (120 px).
 * `lecture` : pas de bouton « retirer » (consultation seule).
 */
defineProps<{ numero: number; retirable?: boolean; etiquette?: string; lecture?: boolean }>()
const emit = defineEmits<{ (e: 'retirer'): void }>()
</script>

<template>
  <div class="ls">
    <div class="ls__entete">
      <span class="ls__numero">{{ etiquette || 'Ligne' }} {{ numero }}</span>
      <v-btn
        v-if="!lecture"
        class="ls__retirer"
        icon="mdi-close"
        size="small"
        variant="text"
        :disabled="retirable === false"
        :aria-label="`Retirer la ligne ${numero}`"
        @click="emit('retirer')"
      />
    </div>
    <div class="ls__champs"><slot /></div>
  </div>
</template>

<style scoped>
.ls { display: flex; align-items: flex-start; gap: 10px; padding: 6px 0; }
.ls__entete { display: contents; }
.ls__numero { display: none; }
.ls__retirer { order: 2; flex: 0 0 auto; }
.ls__champs { order: 1; flex: 1 1 auto; min-width: 0; display: flex; align-items: flex-start; gap: 10px; }
.ls__champs > :deep(.ls-large) { flex: 1 1 auto; min-width: 0; }
.ls__champs > :deep(.ls-montant) { flex: 0 0 160px; max-width: 160px; }
.ls__champs > :deep(.ls-court) { flex: 0 0 120px; max-width: 120px; }

@media screen and (max-width: 959px) {
  .ls {
    display: block;
    margin-bottom: 10px;
    padding: 10px 12px 14px;
    background: #fff;
    border: 1px solid #eef0f2;
    border-radius: 14px;
  }
  .ls__entete { display: flex; align-items: center; justify-content: space-between; margin: 0 -4px 4px 0; }
  .ls__numero {
    display: inline;
    font-size: 0.7rem;
    font-weight: 700;
    letter-spacing: 0.5px;
    text-transform: uppercase;
    color: #94a3b8;
  }
  .ls__champs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .ls__champs > :deep(.ls-large) { grid-column: 1 / -1; }
  .ls__champs > :deep(.ls-montant),
  .ls__champs > :deep(.ls-court) { max-width: none; }
}
</style>
