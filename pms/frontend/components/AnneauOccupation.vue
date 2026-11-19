<script setup lang="ts">
/**
 * Anneau de progression (taux d'occupation) : piste discrète, arc en dégradé à bout rond, valeur au centre. L'arc se
 * dessine à l'apparition (transition de stroke-dashoffset), sans animation si l'utilisateur la refuse.
 */
const props = withDefaults(defineProps<{ valeur: number | null, taille?: number, epaisseur?: number, clair?: boolean }>(), {
  taille: 168, epaisseur: 14, clair: false,
})
const id = `anneau-${Math.random().toString(36).slice(2, 9)}`
const rayon = computed(() => (props.taille - props.epaisseur) / 2)
const circonference = computed(() => 2 * Math.PI * rayon.value)
const affiche = ref(0)
onMounted(() => requestAnimationFrame(() => { affiche.value = Math.max(0, Math.min(100, props.valeur ?? 0)) }))
watch(() => props.valeur, v => { affiche.value = Math.max(0, Math.min(100, v ?? 0)) })
const decalage = computed(() => circonference.value * (1 - affiche.value / 100))
</script>

<template>
  <div class="anneau" :class="{ 'anneau--clair': clair }" :style="{ width: `${taille}px`, height: `${taille}px` }">
    <svg :width="taille" :height="taille" :viewBox="`0 0 ${taille} ${taille}`" aria-hidden="true">
      <defs>
        <linearGradient :id="id" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" class="anneau__stop1" />
          <stop offset="100%" class="anneau__stop2" />
        </linearGradient>
      </defs>
      <circle :cx="taille / 2" :cy="taille / 2" :r="rayon" class="anneau__piste" :stroke-width="epaisseur" fill="none" />
      <circle :cx="taille / 2" :cy="taille / 2" :r="rayon" :stroke="`url(#${id})`" :stroke-width="epaisseur" fill="none" stroke-linecap="round"
        :stroke-dasharray="circonference" :stroke-dashoffset="decalage" class="anneau__arc" :transform="`rotate(-90 ${taille / 2} ${taille / 2})`" />
    </svg>
    <div class="anneau__centre">
      <slot>
        <div class="anneau__valeur">{{ valeur == null ? '—' : `${Math.round(valeur)}` }}<small v-if="valeur != null">%</small></div>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.anneau { position: relative; flex: none; }
.anneau svg { display: block; }
.anneau__piste { stroke: color-mix(in srgb, var(--color-primary) 12%, transparent); }
.anneau__stop1 { stop-color: color-mix(in srgb, var(--color-primary) 80%, #6ee7b7); }
.anneau__stop2 { stop-color: var(--color-primary); }
.anneau--clair .anneau__piste { stroke: rgba(255, 255, 255, 0.14); }
.anneau--clair .anneau__stop1 { stop-color: #ffffff; }
.anneau--clair .anneau__stop2 { stop-color: color-mix(in srgb, var(--color-primary) 30%, #a7f3d0); }
.anneau__arc { transition: stroke-dashoffset 1.1s cubic-bezier(0.2, 0.7, 0.2, 1); }
@media (prefers-reduced-motion: reduce) { .anneau__arc { transition: none; } }
.anneau__centre { position: absolute; inset: 0; display: grid; place-items: center; text-align: center; }
.anneau__valeur { font-size: 2.4rem; font-weight: 780; letter-spacing: -0.05em; line-height: 1; font-variant-numeric: tabular-nums; }
.anneau__valeur small { font-size: 1.1rem; font-weight: 700; margin-left: 2px; opacity: 0.75; }
</style>
