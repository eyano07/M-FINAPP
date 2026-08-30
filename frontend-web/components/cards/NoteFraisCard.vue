<script setup lang="ts">
interface NoteFrais {
  id: number
  reference: string
  objet: string
  montant: number
  devise?: string
  statut: string
  sens?: string | null
  priorite?: string | null
  createurNom?: string
  dateCreation?: string
}

const props = defineProps<{ note: NoteFrais }>()
const emit = defineEmits<{ (e: 'open', id: number): void }>()

function imprimer(e: MouseEvent) {
  e.stopPropagation()
  navigateTo(`/notes-frais/${props.note.id}?print=1`)
}

// Le dégradé de la bannière reflète le statut de la note (lecture visuelle
// immédiate du circuit de validation), et non plus un hasard par référence.
const meta = computed(() => statutNoteMeta(props.note.statut, props.note.sens))
const bannerGradient = computed(() => meta.value.gradient)
const bannerIcon = computed(() =>
  props.note.sens === 'ENCAISSEMENT' ? 'mdi-cash-plus' : 'mdi-receipt-text-outline'
)

const prioriteMeta: Record<string, { label: string; bg: string; color: string; icon: string }> = {
  HAUTE:   { label: 'Haute',   bg: '#fee2e2', color: '#dc2626', icon: 'mdi-arrow-up-bold' },
  MOYENNE: { label: 'Moyenne', bg: '#ffedd5', color: '#ea580c', icon: 'mdi-minus' },
  BASSE:   { label: 'Basse',   bg: '#dbeafe', color: '#2563eb', icon: 'mdi-arrow-down-bold' },
}
const prioMeta = computed(() => props.note.priorite ? prioriteMeta[props.note.priorite] : null)

const montantFmt = computed(() =>
  new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(props.note.montant)
  + ' ' + (props.note.devise || 'CDF')
)
</script>

<template>
  <div class="nfc" @click="emit('open', note.id)">
    <!-- Gradient banner -->
    <div class="nfc__banner" :style="{ background: bannerGradient }">
      <div class="nfc__banner-blob" />
      <span class="nfc__banner-ref">{{ note.reference }}</span>
      <div class="nfc__banner-icon">
        <v-icon :icon="bannerIcon" size="22" color="white" />
      </div>
    </div>

    <div class="nfc__body">
      <!-- Chips statut + priorité -->
      <div class="nfc__chips">
        <span class="nfc__chip" :style="{ background: meta.bg, color: meta.text }">
          {{ meta.label }}
        </span>
        <span v-if="prioMeta" class="nfc__chip" :style="{ background: prioMeta.bg, color: prioMeta.color }">
          <v-icon :icon="prioMeta.icon" size="11" class="mr-1" />{{ prioMeta.label }}
        </span>
      </div>

      <p class="nfc__objet">{{ note.objet }}</p>
      <p class="nfc__montant">{{ montantFmt }}</p>

      <div class="nfc__footer">
        <span v-if="note.createurNom" class="nfc__creator">
          <v-icon icon="mdi-account-circle-outline" size="14" class="mr-1" />{{ note.createurNom }}
        </span>
        <span class="nfc__footer-actions">
          <button type="button" class="nfc__print" title="Imprimer" @click="imprimer">
            <v-icon icon="mdi-printer-outline" size="15" />
          </button>
          <span class="nfc__open">
            Ouvrir <v-icon icon="mdi-arrow-right" size="14" class="ml-1" />
          </span>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.nfc {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 20px;
  overflow: hidden;
  height: 100%;
  display: flex;
  flex-direction: column;
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}
.nfc:hover {
  box-shadow: 0 8px 32px rgba(0,0,0,0.10);
  transform: translateY(-3px);
}

/* ── Banner gradient ─────────────────────────────────────── */
.nfc__banner {
  position: relative;
  overflow: hidden;
  height: 88px;
  padding: 16px 18px 14px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}
.nfc__banner-blob {
  position: absolute;
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: rgba(255,255,255,0.10);
  top: -30px;
  right: -30px;
  pointer-events: none;
}
.nfc__banner-ref {
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.8px;
  text-transform: uppercase;
  color: rgba(255,255,255,0.80);
  position: relative;
  z-index: 1;
}
.nfc__banner-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(255,255,255,0.18);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22);
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

/* ── Body ────────────────────────────────────────────────── */
.nfc__body {
  padding: 14px 18px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.nfc__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.nfc__chip {
  display: inline-flex;
  align-items: center;
  font-size: 0.68rem;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 100px;
}

.nfc__objet {
  font-size: 0.9375rem;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.nfc__montant {
  font-size: 1.2rem;
  font-weight: 800;
  color: #111827;
  letter-spacing: -0.3px;
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.nfc__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid #f3f4f6;
}
.nfc__creator {
  font-size: 0.78rem;
  color: #9ca3af;
  display: flex;
  align-items: center;
}
.nfc__footer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.nfc__print {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.nfc__print:hover { background: #f3f4f6; color: #111827; }
.nfc__open {
  font-size: 0.78rem;
  font-weight: 700;
  color: #16a34a;
  display: flex;
  align-items: center;
}
</style>
