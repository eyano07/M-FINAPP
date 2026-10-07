<script setup lang="ts">
definePageMeta({ roles: ['ADMIN'] })

const api = useApi()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const succes = ref('')

interface TauxTva {
  id?: number
  taux: number
  dateEffet: string
  note?: string
  createdByEmail?: string
  createdAt?: string
}

const historique = ref<TauxTva[]>([])
const tauxActuel = ref<TauxTva | null>(null)

// ── Régime de TVA de l'entreprise ──────────────────────────────────────────
// Non assujettie : le serveur applique un taux de 0 partout (ventes, notes de frais,
// achats au comptant) ; le taux légal ci-dessous reste enregistré pour le jour où
// l'entreprise le deviendrait.
const assujetti = ref(true)
const dialogRegime = ref(false)
const regimeDemande = ref<boolean | null>(null)
const savingRegime = ref(false)
const parametresStore = useParametresStore()

interface ReponseTva { taux: number; dateEffet: string; historique?: TauxTva[]; assujetti?: boolean; tauxEnVigueur?: number }

function appliquer(data: ReponseTva) {
  assujetti.value = data.assujetti !== false
  tauxActuel.value = { taux: data.tauxEnVigueur ?? data.taux, dateEffet: data.dateEffet }
  historique.value = data.historique ?? []
}

function demanderRegime(valeur: boolean | null) {
  if (valeur == null || valeur === assujetti.value) return
  regimeDemande.value = valeur
  dialogRegime.value = true
}

async function confirmerRegime() {
  if (regimeDemande.value == null) return
  savingRegime.value = true
  erreur.value = ''
  succes.value = ''
  try {
    appliquer(await api<ReponseTva>('/admin/taux-tva/assujettissement', {
      method: 'PUT',
      body: { assujetti: regimeDemande.value },
    }))
    // Factures et écrans de TVA lisent le régime dans ce store : à rafraîchir tout de suite.
    await parametresStore.charger()
    succes.value = assujetti.value
      ? 'Entreprise déclarée assujettie à la TVA : les ventes et achats enregistrés à partir de maintenant portent la TVA.'
      : 'Entreprise déclarée non assujettie : aucune TVA sur les ventes et achats enregistrés à partir de maintenant.'
    dialogRegime.value = false
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement du régime de TVA.")
  } finally {
    savingRegime.value = false
  }
}

const form = reactive({
  taux: null as number | null,
  dateEffet: new Date().toISOString().substring(0, 10),
  note: '',
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    appliquer(await api<ReponseTva>('/admin/taux-tva'))
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le taux de TVA.')
  } finally {
    loading.value = false
  }
}

onMounted(charger)

async function enregistrer() {
  if (form.taux == null || form.taux < 0 || form.taux > 100) {
    erreur.value = 'Le taux doit être compris entre 0 et 100 %.'
    return
  }
  saving.value = true
  erreur.value = ''
  succes.value = ''
  try {
    await api('/admin/taux-tva', {
      method: 'POST',
      body: { taux: form.taux, dateEffet: form.dateEffet, note: form.note || null },
    })
    succes.value = `Taux enregistré : ${fmtTaux(form.taux)} à compter du ${fmtDate(form.dateEffet)}`
    form.taux = null
    form.note = ''
    await charger()
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Échec de l'enregistrement.")
  } finally {
    saving.value = false
  }
}

const fmtTaux = (t: number) => `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(t)} %`
function fmtDate(d: string) {
  return d ? new Date(d).toLocaleDateString('fr-FR', { year: 'numeric', month: 'long', day: 'numeric' }) : '—'
}
</script>

<template>
  <div class="tva-page">

    <div class="page-head">
      <div>
        <h1 class="page-title">Taux de TVA</h1>
        <p class="page-sub">Taux appliqué aux ventes · les factures conservent le taux en vigueur à leur date</p>
      </div>
    </div>

    <!-- ── Régime de TVA de l'entreprise ──────────────────── -->
    <div class="tva-card tva-regime">
      <p class="tva-card__title">
        <v-icon icon="mdi-scale-balance" size="16" class="mr-2" />
        Régime de TVA de l'entreprise
      </p>
      <v-btn-toggle
        :model-value="assujetti"
        mandatory
        density="comfortable"
        variant="outlined"
        rounded="lg"
        color="primary"
        :disabled="loading || savingRegime"
        @update:model-value="demanderRegime"
      >
        <v-btn :value="true" prepend-icon="mdi-check-decagram-outline">Assujettie à la TVA</v-btn>
        <v-btn :value="false" prepend-icon="mdi-cancel">Non assujettie</v-btn>
      </v-btn-toggle>
      <p class="tva-regime__texte">
        <template v-if="assujetti">
          La TVA est facturée sur les ventes (4431) et récupérée sur les achats (4452), au taux en vigueur ci-dessous.
        </template>
        <template v-else>
          Aucune TVA n'est facturée sur les ventes ni récupérée sur les achats : la TVA payée aux fournisseurs fait
          partie de la charge, et les factures portent la mention « TVA non applicable ».
        </template>
      </p>
      <v-alert v-if="assujetti && !loading && !(tauxActuel && tauxActuel.taux > 0)" type="warning" variant="tonal"
               rounded="lg" density="compact" class="mt-3">
        Entreprise assujettie, mais aucun taux n'est enregistré : les ventes partent sans TVA. Enregistrez le taux légal ci-dessous.
      </v-alert>
    </div>

    <!-- ── Taux en vigueur ─────────────────────────────────── -->
    <div class="tva-hero">
      <div class="tva-hero__blob tva-hero__blob--a" />
      <div class="tva-hero__blob tva-hero__blob--b" />
      <div class="tva-hero__left">
        <div class="tva-hero__icon">
          <v-icon icon="mdi-percent-outline" size="22" color="white" />
        </div>
        <div>
          <p class="tva-hero__label">{{ assujetti ? 'Taux en vigueur' : 'Taux légal enregistré — non appliqué' }}</p>
          <p class="tva-hero__val">
            <template v-if="tauxActuel && tauxActuel.taux > 0">
              <strong>{{ fmtTaux(tauxActuel.taux) }}</strong>
            </template>
            <template v-else>
              <span class="tva-hero__undefined">Non configuré</span>
            </template>
          </p>
        </div>
      </div>
      <div v-if="tauxActuel" class="tva-hero__badge">
        <v-icon icon="mdi-update" size="14" class="mr-1" />
        Depuis le {{ fmtDate(tauxActuel.dateEffet) }}
      </div>
    </div>

    <div class="tva-layout">
      <!-- ── Formulaire ──────────────────────────────────── -->
      <div class="tva-card">
        <p class="tva-card__title">
          <v-icon icon="mdi-pencil-outline" size="16" class="mr-2" />
          Définir un nouveau taux
        </p>

        <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
          {{ erreur }}
        </v-alert>
        <v-alert v-if="succes" type="success" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="succes = ''">
          {{ succes }}
        </v-alert>

        <div class="tva-fields">
          <div class="tva-field">
            <label class="tva-label">Taux de TVA (%) *</label>
            <v-text-field
              v-model.number="form.taux"
              type="number"
              min="0"
              max="100"
              step="0.01"
              placeholder="ex: 16"
              prepend-inner-icon="mdi-percent-outline"
              hide-details="auto"
              :hint="`Valeur actuelle : ${tauxActuel?.taux != null ? fmtTaux(tauxActuel.taux) : '—'}`"
              persistent-hint
            />
          </div>

          <div class="tva-field">
            <label class="tva-label">Date d'effet</label>
            <v-text-field
              v-model="form.dateEffet"
              type="date"
              prepend-inner-icon="mdi-calendar"
              hide-details="auto"
              hint="Les ventes antérieures conservent leur taux d'origine"
              persistent-hint
            />
          </div>

          <div class="tva-field">
            <label class="tva-label">Note (optionnel)</label>
            <v-textarea
              v-model="form.note"
              placeholder="Ex: Loi de finances 2026"
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
          class="tva-save-btn"
          prepend-icon="mdi-content-save-outline"
          @click="enregistrer"
        >
          Enregistrer le taux
        </v-btn>
      </div>

      <!-- ── Historique ──────────────────────────────────── -->
      <div class="tva-card">
        <p class="tva-card__title">
          <v-icon icon="mdi-history" size="16" class="mr-2" />
          Historique
        </p>

        <div v-if="loading" class="tva-hist-loading">
          <v-progress-circular indeterminate color="primary" size="24" />
        </div>

        <div v-else-if="historique.length === 0" class="tva-hist-empty">
          <v-icon icon="mdi-database-off-outline" size="32" color="#d1d5db" />
          <p>Aucun historique disponible.</p>
        </div>

        <div v-else class="tva-hist">
          <div v-for="(t, i) in historique" :key="t.id ?? i" class="tva-hist-row">
            <div class="tva-hist-row__dot" :class="{ 'tva-hist-row__dot--latest': i === 0 }" />
            <div class="tva-hist-row__body">
              <div class="tva-hist-row__header">
                <span class="tva-hist-row__rate">{{ fmtTaux(t.taux) }}</span>
                <span v-if="i === 0" class="tva-hist-row__badge">Actuel</span>
              </div>
              <span class="tva-hist-row__date">À compter du {{ fmtDate(t.dateEffet) }}</span>
              <span v-if="t.note" class="tva-hist-row__note">{{ t.note }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Confirmation du changement de régime ───────────── -->
    <v-dialog v-model="dialogRegime" max-width="540">
      <v-card rounded="lg">
        <v-card-title class="text-subtitle-1 pt-4">
          {{ regimeDemande ? "Déclarer l'entreprise assujettie à la TVA ?" : "Déclarer l'entreprise non assujettie à la TVA ?" }}
        </v-card-title>
        <v-card-text class="text-body-2">
          <p v-if="regimeDemande" class="mb-2">
            À partir de maintenant, la TVA sera facturée sur les ventes et récupérée sur les achats
            <template v-if="tauxActuel && tauxActuel.taux > 0">au taux de {{ fmtTaux(tauxActuel.taux) }}.</template>
            <template v-else>— mais aucun taux n'est encore enregistré : enregistrez ensuite le taux légal.</template>
          </p>
          <p v-else class="mb-2">
            À partir de maintenant, aucune TVA ne sera facturée sur les ventes ni récupérée sur les achats ;
            les factures porteront la mention « TVA non applicable ».
          </p>
          <p class="mb-0 text-medium-emphasis">Les ventes et notes de frais déjà enregistrées ne changent pas.</p>
        </v-card-text>
        <v-card-actions class="px-4 pb-4">
          <v-spacer />
          <v-btn variant="text" :disabled="savingRegime" @click="dialogRegime = false">Annuler</v-btn>
          <v-btn color="primary" variant="flat" rounded="lg" :loading="savingRegime" @click="confirmerRegime">Confirmer</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.tva-regime { margin-bottom: 20px; }
.tva-regime__texte { margin: 12px 0 0; font-size: 0.875rem; color: #6b7280; line-height: 1.5; }
.tva-page { max-width: 960px; margin: 0 auto; padding-bottom: 48px; }

/* ── Hero ────────────────────────────────────────────────── */
.tva-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  padding: 24px 28px;
  background: linear-gradient(140deg, #7c3aed 0%, #6d28d9 50%, #4c1d95 100%);
  border-radius: 20px;
  margin-bottom: 24px;
}
.tva-hero__blob {
  position: absolute;
  border-radius: 50%;
  background: rgba(255,255,255,0.08);
  pointer-events: none;
}
.tva-hero__blob--a { width: 180px; height: 180px; top: -50px; right: -50px; }
.tva-hero__blob--b { width: 100px; height: 100px; bottom: -30px; left: -20px; }

.tva-hero__left { display: flex; align-items: center; gap: 16px; position: relative; z-index: 1; }
.tva-hero__icon {
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
.tva-hero__label {
  font-size: 0.78rem;
  font-weight: 500;
  color: rgba(255,255,255,0.70);
  margin: 0 0 4px;
}
.tva-hero__val {
  font-size: 1.4rem;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.5px;
  margin: 0;
  font-variant-numeric: tabular-nums;
}
.tva-hero__undefined { color: rgba(255,255,255,0.50); font-size: 1rem; font-weight: 500; }
.tva-hero__badge {
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

/* ── Layout ──────────────────────────────────────────────── */
.tva-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
@media (max-width: 760px) { .tva-layout { grid-template-columns: 1fr; } }

.tva-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 18px;
  padding: 22px;
}
.tva-card__title {
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

.tva-fields { display: flex; flex-direction: column; gap: 16px; margin-bottom: 20px; }
.tva-field  { display: flex; flex-direction: column; gap: 6px; }
.tva-label  { font-size: 0.8125rem; font-weight: 600; color: #374151; }
.tva-save-btn { height: 50px !important; font-size: 0.9375rem !important; font-weight: 600 !important; }

/* ── Historique ──────────────────────────────────────────── */
.tva-hist-loading, .tva-hist-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 32px 16px;
  color: #9ca3af;
  font-size: 0.85rem;
  text-align: center;
}
.tva-hist-empty p { margin: 0; }

.tva-hist { display: flex; flex-direction: column; }
.tva-hist-row {
  display: flex;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f9fafb;
}
.tva-hist-row:last-child { border-bottom: none; }
.tva-hist-row__dot {
  width: 12px; height: 12px;
  border-radius: 50%;
  background: #e5e7eb;
  border: 2px solid #f3f4f6;
  margin-top: 5px;
  flex-shrink: 0;
}
.tva-hist-row__dot--latest { background: #7c3aed; border-color: #ede9fe; }
.tva-hist-row__body { flex: 1; }
.tva-hist-row__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.tva-hist-row__rate {
  font-size: 0.9rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
}
.tva-hist-row__badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 100px;
  background: #ede9fe;
  color: #6d28d9;
}
.tva-hist-row__date { display: block; font-size: 0.75rem; color: #9ca3af; }
.tva-hist-row__note {
  display: block;
  font-size: 0.78rem;
  color: #6b7280;
  font-style: italic;
  margin-top: 2px;
}
</style>
