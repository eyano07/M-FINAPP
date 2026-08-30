<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

const api = useApi()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

interface TauxChange {
  id?: number
  taux: number
  dateEffet: string
  note?: string
  source?: string
  createdAt?: string
}

const taux = ref<TauxChange[]>([])
const tauxActuel = ref<TauxChange | null>(null)

const form = reactive({
  taux: null as number | null,
  dateEffet: new Date().toISOString().substring(0, 10),
  source: '',
  note: '',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const data = await api<{ taux: number; historique?: TauxChange[] }>('/admin/taux-change')
    tauxActuel.value = { taux: data.taux, dateEffet: new Date().toISOString().substring(0, 10) }
    taux.value = data.historique ?? []
  } catch (e: any) {
    if (e?.status !== 404) erreur.value = e?.data?.message || 'Impossible de charger le taux.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)

async function enregistrer() {
  if (!form.taux || form.taux <= 0) {
    erreur.value = 'Le taux doit être un nombre positif.'
    return
  }
  if (!form.source.trim()) {
    erreur.value = "Indiquez la source du taux (ex. « BCC, cote du 18/08/2026 ») : un taux sans source vérifiable n'est pas opposable lors d'un contrôle."
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/admin/taux-change', {
      method: 'POST',
      body: { taux: form.taux, dateEffet: form.dateEffet, source: form.source.trim(), note: form.note || null },
    })
    succes.value = `Taux enregistré : 1 USD = ${new Intl.NumberFormat('fr-FR').format(form.taux)} FC`
    form.taux = null
    form.source = ''
    form.note = ''
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || "Échec de l'enregistrement."
  } finally {
    saving.value = false
  }
}

function fmtDate(d: string) {
  return d ? new Date(d).toLocaleDateString('fr-FR', { year: 'numeric', month: 'long', day: 'numeric' }) : '—'
}
</script>

<template>
  <div class="tc-page">

    <!-- ── Header ──────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Taux de change</h1>
        <p class="page-sub">Configurez le taux USD → FC utilisé dans la balance</p>
      </div>
    </div>

    <!-- ── Taux actuel hero ────────────────────────────────── -->
    <div class="tc-hero">
      <div class="tc-hero__blob tc-hero__blob--a" />
      <div class="tc-hero__blob tc-hero__blob--b" />
      <div class="tc-hero__left">
        <div class="tc-hero__icon">
          <v-icon icon="mdi-currency-usd" size="22" color="white" />
        </div>
        <div>
          <p class="tc-hero__label">Taux actuel du jour</p>
          <p class="tc-hero__val">
            <template v-if="tauxActuel && tauxActuel.taux > 0">
              1 USD = <strong>{{ new Intl.NumberFormat('fr-FR').format(tauxActuel.taux) }}</strong> FC
            </template>
            <template v-else>
              <span class="tc-hero__undefined">Non configuré</span>
            </template>
          </p>
        </div>
      </div>
      <div class="tc-hero__badge">
        <v-icon icon="mdi-update" size="14" class="mr-1" />
        {{ fmtDate(form.dateEffet) }}
      </div>
    </div>

    <div class="tc-layout">
      <!-- ── Formulaire ──────────────────────────────────── -->
      <div class="tc-card">
        <p class="tc-card__title">
          <v-icon icon="mdi-pencil-outline" size="16" class="mr-2" />
          Définir un nouveau taux
        </p>

        <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
          {{ erreur }}
        </v-alert>
        <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
          {{ succes }}
        </v-alert>

        <div class="tc-fields">
          <div class="tc-field">
            <label class="tc-label">Taux : 1 USD vaut combien de FC ? *</label>
            <v-text-field
              v-model.number="form.taux"
              type="number"
              placeholder="ex: 2850"
              prepend-inner-icon="mdi-cash-multiple"
              hide-details="auto"
              :hint="`Valeur actuelle : ${tauxActuel?.taux ? new Intl.NumberFormat('fr-FR').format(tauxActuel.taux) + ' FC' : '—'}`"
              persistent-hint
            />
          </div>

          <div class="tc-field">
            <label class="tc-label">Date d'effet</label>
            <v-text-field
              v-model="form.dateEffet"
              type="date"
              prepend-inner-icon="mdi-calendar"
              hide-details="auto"
            />
          </div>

          <div class="tc-field">
            <label class="tc-label">Source du taux *</label>
            <v-text-field
              v-model="form.source"
              placeholder="Ex : BCC, cote du 18/08/2026"
              prepend-inner-icon="mdi-bank-outline"
              hide-details="auto"
              hint="Référence vérifiable de la cote retenue — exigée en cas de contrôle."
              persistent-hint
            />
          </div>

          <div class="tc-field">
            <label class="tc-label">Note (optionnel)</label>
            <v-textarea
              v-model="form.note"
              placeholder="Ex: Taux BCC du 06/06/2026"
              rows="2"
              hide-details
            />
          </div>
        </div>

        <v-btn
          color="primary"
          block
          rounded="lg"
          elevation="0"
          size="large"
          :loading="saving"
          class="tc-save-btn"
          prepend-icon="mdi-content-save-outline"
          @click="enregistrer"
        >
          Enregistrer le taux
        </v-btn>
      </div>

      <!-- ── Historique ──────────────────────────────────── -->
      <div class="tc-card">
        <p class="tc-card__title">
          <v-icon icon="mdi-history" size="16" class="mr-2" />
          Historique
        </p>

        <div v-if="loading" class="tc-hist-loading">
          <v-progress-circular indeterminate color="primary" size="24" />
        </div>

        <div v-else-if="taux.length === 0" class="tc-hist-empty">
          <v-icon icon="mdi-database-off-outline" size="32" color="#d1d5db" />
          <p>Aucun historique disponible.</p>
        </div>

        <div v-else class="tc-hist">
          <div v-for="(t, i) in taux" :key="i" class="tc-hist-row" :class="{ 'tc-hist-row--latest': i === 0 }">
            <div class="tc-hist-row__dot" :class="{ 'tc-hist-row__dot--latest': i === 0 }" />
            <div class="tc-hist-row__body">
              <div class="tc-hist-row__header">
                <span class="tc-hist-row__rate">
                  1 USD = {{ new Intl.NumberFormat('fr-FR').format(t.taux) }} FC
                </span>
                <span v-if="i === 0" class="tc-hist-row__badge">Actuel</span>
              </div>
              <span class="tc-hist-row__date">{{ fmtDate(t.dateEffet || t.createdAt || '') }}</span>
              <span v-if="t.source" class="tc-hist-row__source">Source : {{ t.source }}</span>
              <span v-else class="tc-hist-row__source tc-hist-row__source--absente">Source non renseignée</span>
              <span v-if="t.note" class="tc-hist-row__note">{{ t.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tc-page { max-width: 960px; margin: 0 auto; padding-bottom: 48px; }

/* ── Hero ────────────────────────────────────────────────── */
.tc-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 24px 28px;
  background: linear-gradient(140deg, #2563eb 0%, #1d4ed8 50%, #1e3a8a 100%);
  border-radius: 20px;
  margin-bottom: 24px;
}
.tc-hero__blob {
  position: absolute;
  border-radius: 50%;
  background: rgba(255,255,255,0.08);
  pointer-events: none;
}
.tc-hero__blob--a { width: 180px; height: 180px; top: -50px; right: -50px; }
.tc-hero__blob--b { width: 100px; height: 100px; bottom: -30px; left: -20px; }

.tc-hero__left { display: flex; align-items: center; gap: 16px; position: relative; z-index: 1; }

.tc-hero__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px; height: 48px;
  border-radius: 13px;
  background: rgba(255,255,255,0.18);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22);
  flex-shrink: 0;
}
.tc-hero__label {
  font-size: 0.78rem;
  font-weight: 500;
  color: rgba(255,255,255,0.70);
  margin: 0 0 4px;
}
.tc-hero__val {
  font-size: 1.4rem;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.5px;
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.tc-hero__undefined { color: rgba(255,255,255,0.50); font-size: 1rem; font-weight: 500; }

.tc-hero__badge {
  display: inline-flex;
  align-items: center;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 6px 14px;
  border-radius: 100px;
  background: rgba(255,255,255,0.15);
  color: rgba(255,255,255,0.85);
  border: 1px solid rgba(255,255,255,0.18);
  position: relative; z-index: 1;
}

/* ── Layout 2 colonnes ───────────────────────────────────── */
.tc-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
@media (max-width: 760px) { .tc-layout { grid-template-columns: 1fr; } }

/* ── Cards ───────────────────────────────────────────────── */
.tc-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 18px;
  padding: 22px;
}
.tc-card__title {
  display: flex;
  align-items: center;
  font-size: 0.78rem;
  font-weight: 700;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #9ca3af;
  margin: 0 0 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f3f4f6;
}

/* ── Form ────────────────────────────────────────────────── */
.tc-fields { display: flex; flex-direction: column; gap: 16px; margin-bottom: 20px; }
.tc-field  { display: flex; flex-direction: column; gap: 6px; }
.tc-label  { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.tc-save-btn { height: 50px !important; font-size: 0.9375rem !important; font-weight: 600 !important; }

/* ── Historique ──────────────────────────────────────────── */
.tc-hist-loading, .tc-hist-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 16px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.tc-hist-empty p { margin: 0; }

.tc-hist { display: flex; flex-direction: column; }

.tc-hist-row {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f9fafb;
  position: relative;
}
.tc-hist-row:last-child { border-bottom: none; }

.tc-hist-row__dot {
  width: 12px; height: 12px;
  border-radius: 50%;
  background: #e5e7eb;
  border: 2px solid #f3f4f6;
  margin-top: 5px;
  flex-shrink: 0;
}
.tc-hist-row__dot--latest { background: #2563eb; border-color: #dbeafe; }

.tc-hist-row__body { flex: 1; }
.tc-hist-row__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.tc-hist-row__rate {
  font-size: 0.9rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
}
.tc-hist-row__badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 100px;
  background: #dbeafe;
  color: #1d4ed8;
}
.tc-hist-row__date {
  display: block;
  font-size: 0.75rem;
  color: #9ca3af;
}
.tc-hist-row__note {
  display: block;
  font-size: 0.78rem;
  color: #6b7280;
  font-style: italic;
  margin-top: 2px;
}
.tc-hist-row__source {
  display: block;
  font-size: 0.78rem;
  color: #374151;
  margin-top: 2px;
}
/* Un taux anterieur a l'obligation de source : signale sans alarmer. */
.tc-hist-row__source--absente {
  color: #9ca3af;
  font-style: italic;
}
</style>
