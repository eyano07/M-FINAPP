<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  icon: string
  color?: string
  trend?: string
}>()
</script>

<template>
  <div class="stat-card" :class="`stat-card--${color ?? 'primary'}`">
    <div class="stat-card__top">
      <div class="stat-card__icon-wrap">
        <v-icon :icon="icon" size="20" />
      </div>
      <span v-if="trend" class="stat-card__trend-badge">
        <v-icon icon="mdi-trending-up" size="11" class="mr-1" />{{ trend }}
      </span>
    </div>

    <div class="stat-card__body">
      <span class="stat-card__label">{{ label }}</span>
      <span class="stat-card__value">{{ value }}</span>
    </div>

    <div class="stat-card__blob stat-card__blob--a" />
    <div class="stat-card__blob stat-card__blob--b" />
  </div>
</template>

<style scoped>
.stat-card {
  position: relative;
  overflow: hidden;
  border-radius: 20px;
  padding: 24px 22px 22px;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  cursor: default;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 16px 40px -8px var(--stat-shadow);
}

.stat-card--green {
  background: linear-gradient(140deg, #22c55e 0%, #16a34a 45%, #14532d 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(21,128,61,0.50);
}
.stat-card--orange {
  background: linear-gradient(140deg, #fb923c 0%, #ea580c 45%, #9a3412 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(234,88,12,0.50);
}
.stat-card--blue {
  background: linear-gradient(140deg, #60a5fa 0%, #2563eb 45%, #1e3a8a 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(37,99,235,0.50);
}
.stat-card--purple {
  background: linear-gradient(140deg, #c084fc 0%, #9333ea 45%, #581c87 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(147,51,234,0.50);
}
.stat-card--teal {
  background: linear-gradient(140deg, #2dd4bf 0%, #0d9488 45%, #134e4a 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(13,148,136,0.50);
}
.stat-card--indigo {
  background: linear-gradient(140deg, #818cf8 0%, #4f46e5 45%, #312e81 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(79,70,229,0.50);
}
.stat-card--primary {
  background: linear-gradient(140deg, #22c55e 0%, #16a34a 45%, #14532d 100%);
  --stat-icon-bg: rgba(255,255,255,0.18);
  --stat-label-color: rgba(255,255,255,0.72);
  --stat-value-color: #ffffff;
  --stat-badge-bg: rgba(255,255,255,0.16);
  --stat-badge-color: rgba(255,255,255,0.90);
  --stat-blob-a: rgba(255,255,255,0.10);
  --stat-blob-b: rgba(0,0,0,0.12);
  --stat-shadow: rgba(21,128,61,0.50);
}

.stat-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  position: relative;
  z-index: 1;
}

.stat-card__icon-wrap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--stat-icon-bg);
  color: #ffffff;
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.20);
}

.stat-card__trend-badge {
  display: inline-flex;
  align-items: center;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 100px;
  background: var(--stat-badge-bg);
  color: var(--stat-badge-color);
  border: 1px solid rgba(255,255,255,0.16);
  white-space: nowrap;
}

.stat-card__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
  z-index: 1;
}

.stat-card__label {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--stat-label-color);
  letter-spacing: 0.2px;
}

.stat-card__value {
  font-size: 1.85rem;
  font-weight: 800;
  color: var(--stat-value-color);
  letter-spacing: -1px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.stat-card__blob {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.stat-card__blob--a {
  width: 140px;
  height: 140px;
  background: var(--stat-blob-a);
  right: -40px;
  top: -40px;
}
.stat-card__blob--b {
  width: 100px;
  height: 100px;
  background: var(--stat-blob-b);
  right: 15px;
  bottom: -30px;
}
</style>
