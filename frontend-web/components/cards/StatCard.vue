<script setup lang="ts">
const props = defineProps<{
  label: string
  value: string | number
  icon: string
  color?: string
  trend?: string
  to?: string
}>()

function onClick() {
  if (props.to) navigateTo(props.to)
}
</script>

<template>
  <component
    :is="to ? 'a' : 'div'"
    class="stat-card"
    :class="[`stat-card--${color ?? 'primary'}`, { 'stat-card--clickable': to }]"
    :href="to || undefined"
    @click.prevent="onClick"
  >
    <span class="stat-card__label">
      <v-icon :icon="icon" size="16" class="stat-card__icon" />
      {{ label }}
    </span>
    <span class="stat-card__value">{{ value }}</span>
    <span v-if="trend" class="stat-card__trend">
      <v-icon icon="mdi-trending-up" size="13" class="mr-1" />{{ trend }}
    </span>
  </component>
</template>

<style scoped>
/* Fiche de chiffre : fond blanc, filet d'accent à gauche. La couleur porte un sens (argent = vert, à traiter = orange,
   résultat = selon son signe), pas une décoration ; le chiffre est le seul élément fort de la fiche. */
.stat-card {
  --accent: var(--color-primary);
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  padding: 18px 20px 18px 22px;
  background: #fff;
  border: 1px solid #e8ebe9;
  border-left: 4px solid var(--accent);
  border-radius: 12px;
  color: inherit;
  text-decoration: none;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.stat-card--clickable { cursor: pointer; }
.stat-card--clickable:hover { background: #fbfcfb; border-color: color-mix(in srgb, var(--accent) 45%, #e8ebe9); border-left-color: var(--accent); }
.stat-card--clickable:focus-visible { outline: 3px solid color-mix(in srgb, var(--accent) 55%, white); outline-offset: 2px; }

.stat-card--green, .stat-card--primary { --accent: var(--color-primary); }
.stat-card--teal   { --accent: #0f766e; }
.stat-card--indigo { --accent: #4338ca; }
.stat-card--blue   { --accent: #1d4ed8; }
.stat-card--orange { --accent: #c2410c; }
.stat-card--purple { --accent: #7e22ce; }

.stat-card__label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #4b5563;
}
.stat-card__icon { color: var(--accent); }
.stat-card__value {
  font-size: clamp(1.45rem, 2.2vw, 1.85rem);
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.02em;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.stat-card__trend {
  display: inline-flex;
  align-items: center;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--accent);
}
</style>
