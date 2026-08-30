<script setup lang="ts">
// CAISSIER a COMPTABILITE en LECTURE (pour Balance/Compte de resultat
// uniquement) mais ne voit pas les Budgets.
definePageMeta({ module: 'COMPTABILITE', roles: ['ADMIN', 'DFIN', 'DA', 'DG', 'COMPTABLE'] })

interface LigneBudget {
  compteNumero: string
  montantPrevu: number
  montantRealise?: number
}
interface Budget {
  id: number
  intitule: string
  exercice: number
  statut: string
  totalPrevu: number
  totalRealise: number
  lignes?: LigneBudget[]
}

const api = useApi()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const erreur = ref('')
const dialog = ref(false)
const budgets = ref<Budget[]>([])

const isDFIN = computed(() => auth.hasAnyRole(['DFIN']))
const isDA = computed(() => auth.hasAnyRole(['DA']))

const statutMeta: Record<string, { color: string }> = {
  BROUILLON: { color: 'grey' },
  SOUMIS: { color: 'blue' },
  APPROUVE: { color: 'green' },
  REJETE: { color: 'red' },
  EN_EXECUTION: { color: 'teal' },
}

const form = reactive({
  intitule: '',
  exercice: new Date().getFullYear(),
  observation: '',
  lignes: [{ compteNumero: '', montantPrevu: null as number | null }] as { compteNumero: string; montantPrevu: number | null }[],
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    budgets.value = await api<Budget[]>('/budgets')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger les budgets.'
  } finally {
    loading.value = false
  }
}

onMounted(charger)

function ajouterLigne() {
  form.lignes.push({ compteNumero: '', montantPrevu: null })
}
function retirerLigne(i: number) {
  form.lignes.splice(i, 1)
}

async function creerBudget() {
  const lignes = form.lignes.filter((l) => l.compteNumero && l.montantPrevu != null)
  if (!form.intitule || lignes.length === 0) {
    erreur.value = 'Renseignez un intitule et au moins une ligne.'
    return
  }
  saving.value = true
  erreur.value = ''
  try {
    await api('/budgets', {
      method: 'POST',
      body: {
        intitule: form.intitule,
        exercice: form.exercice,
        observation: form.observation || null,
        lignes: lignes.map((l) => ({ compteNumero: l.compteNumero, montantPrevu: l.montantPrevu })),
      },
    })
    dialog.value = false
    form.intitule = ''
    form.observation = ''
    form.lignes = [{ compteNumero: '', montantPrevu: null }]
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Echec de la creation du budget.'
  } finally {
    saving.value = false
  }
}

async function action(b: Budget, chemin: string) {
  erreur.value = ''
  try {
    await api(`/budgets/${b.id}/${chemin}`, { method: 'POST', body: {} })
    await charger()
  } catch (e: any) {
    erreur.value = e?.data?.message || "Echec de l'action."
  }
}

const fmt = (v: number) => new Intl.NumberFormat('fr-FR').format(v || 0)
const taux = (b: Budget) => (b.totalPrevu ? Math.round((b.totalRealise / b.totalPrevu) * 100) : 0)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Budgets</h1>
        <p class="page-sub">Prévisions élaborées par le DFIN et approuvées par le DA</p>
      </div>
      <button v-if="isDFIN" class="bud-new-btn" @click="dialog = true">
        <v-icon icon="mdi-plus" size="18" class="mr-1" />
        Nouveau budget
      </button>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <v-row v-else>
      <v-col v-for="b in budgets" :key="b.id" cols="12" md="6" lg="4">
        <v-card class="classroom-card pa-4" height="100%">
          <div class="d-flex align-center mb-2">
            <v-icon icon="mdi-chart-box-outline" color="primary" class="mr-2" />
            <span class="text-subtitle-1 font-weight-medium">{{ b.intitule }}</span>
            <v-spacer />
            <v-chip :color="statutMeta[b.statut]?.color" size="small" variant="tonal">
              {{ b.statut }}
            </v-chip>
          </div>

          <div class="text-caption text-medium-emphasis mb-1">
            Exercice {{ b.exercice }} - Realise : {{ fmt(b.totalRealise) }} / {{ fmt(b.totalPrevu) }} F
          </div>
          <v-progress-linear
            :model-value="taux(b)"
            color="primary"
            height="10"
            rounded
            class="mb-2"
          />
          <div class="text-end text-caption font-weight-medium">{{ taux(b) }}%</div>

          <v-divider class="my-2" />
          <div class="d-flex flex-wrap ga-2">
            <v-btn v-if="isDFIN && b.statut === 'BROUILLON'" size="small" variant="tonal" color="blue"
              @click="action(b, 'soumettre')">Soumettre</v-btn>
            <v-btn v-if="isDA && b.statut === 'SOUMIS'" size="small" variant="tonal" color="success"
              @click="action(b, 'approuver')">Approuver</v-btn>
            <v-btn v-if="isDA && b.statut === 'SOUMIS'" size="small" variant="tonal" color="error"
              @click="action(b, 'rejeter')">Rejeter</v-btn>
            <v-btn v-if="isDFIN && b.statut === 'APPROUVE'" size="small" variant="tonal" color="teal"
              @click="action(b, 'demarrer')">Demarrer</v-btn>
          </div>
        </v-card>
      </v-col>
    </v-row>

    <v-empty-state
      v-if="!loading && budgets.length === 0"
      icon="mdi-chart-box-outline"
      title="Aucun budget"
      text="Aucun budget previsionnel n'a encore ete cree."
    />

    <!-- Dialog creation -->
    <v-dialog v-model="dialog" max-width="640">
      <div class="bud-dialog">
        <!-- En-tête dégradé -->
        <div class="bud-dialog__head">
          <div class="bud-dialog__head-blob bud-dialog__head-blob--a" />
          <div class="bud-dialog__head-blob bud-dialog__head-blob--b" />
          <div class="bud-dialog__head-icon">
            <v-icon icon="mdi-chart-box-plus-outline" size="22" color="white" />
          </div>
          <div class="bud-dialog__head-text">
            <p class="bud-dialog__head-title">Nouveau budget prévisionnel</p>
            <p class="bud-dialog__head-sub">Renseignez les informations du budget</p>
          </div>
          <button class="bud-dialog__close" @click="dialog = false">
            <v-icon icon="mdi-close" size="18" color="rgba(255,255,255,0.75)" />
          </button>
        </div>

        <!-- Corps -->
        <div class="bud-dialog__body">
          <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" density="compact" class="mb-4" closable @click:close="erreur = ''">
            {{ erreur }}
          </v-alert>

          <div class="bud-field">
            <label class="bud-label">Intitulé *</label>
            <v-text-field v-model="form.intitule" placeholder="Ex: Budget 2026 – Exploitation" hide-details="auto" />
          </div>

          <div class="bud-row">
            <div class="bud-field">
              <label class="bud-label">Exercice *</label>
              <v-text-field v-model.number="form.exercice" type="number" hide-details="auto" />
            </div>
          </div>

          <div class="bud-field">
            <label class="bud-label">Observation</label>
            <v-textarea v-model="form.observation" placeholder="Remarques ou contexte budgétaire…" rows="2" hide-details />
          </div>

          <div class="bud-section-label">
            <v-icon icon="mdi-format-list-bulleted" size="14" class="mr-1" />
            Lignes budgétaires
          </div>

          <div v-for="(l, i) in form.lignes" :key="i" class="bud-ligne">
            <v-text-field v-model="l.compteNumero" placeholder="N° Compte OHADA" density="compact" hide-details />
            <v-text-field v-model.number="l.montantPrevu" placeholder="Montant prévu" type="number" density="compact" hide-details />
            <button class="bud-del-btn" :disabled="form.lignes.length === 1" @click="retirerLigne(i)">
              <v-icon icon="mdi-delete-outline" size="18" />
            </button>
          </div>

          <button class="bud-add-ligne" @click="ajouterLigne">
            <v-icon icon="mdi-plus" size="15" class="mr-1" />
            Ajouter une ligne
          </button>
        </div>

        <!-- Pied -->
        <div class="bud-dialog__footer">
          <button class="bud-cancel-btn" :disabled="saving" @click="dialog = false">Annuler</button>
          <button class="bud-submit-btn" :disabled="saving" @click="creerBudget">
            <v-progress-circular v-if="saving" indeterminate size="16" width="2" color="white" class="mr-2" />
            <v-icon v-else icon="mdi-check" size="17" class="mr-1" />
            Créer le budget
          </button>
        </div>
      </div>
    </v-dialog>
  </div>
</template>

<style scoped>
/* ── Bouton header ───────────────────────────────────────── */
.bud-new-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 20px;
  height: 42px;
  border-radius: 12px;
  background: #16a34a;
  color: #fff;
  font-size: 0.875rem;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(22,163,74,0.25);
  flex-shrink: 0;
}
.bud-new-btn:hover { background: #15803d; box-shadow: 0 4px 14px rgba(22,163,74,0.35); }

/* ── Dialog wrapper ──────────────────────────────────────── */
.bud-dialog {
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
}

/* ── En-tête dégradé ─────────────────────────────────────── */
.bud-dialog__head {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 22px 22px 22px 22px;
  background: linear-gradient(140deg, #22c55e 0%, #16a34a 50%, #14532d 100%);
}
.bud-dialog__head-blob {
  position: absolute;
  border-radius: 50%;
  background: rgba(255,255,255,0.10);
  pointer-events: none;
}
.bud-dialog__head-blob--a { width: 160px; height: 160px; top: -50px; right: -40px; }
.bud-dialog__head-blob--b { width: 80px;  height: 80px;  bottom: -20px; left: 60px; }

.bud-dialog__head-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px; height: 46px;
  border-radius: 13px;
  background: rgba(255,255,255,0.18);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(255,255,255,0.22);
  flex-shrink: 0;
  position: relative; z-index: 1;
}
.bud-dialog__head-text { flex: 1; position: relative; z-index: 1; }
.bud-dialog__head-title {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin: 0 0 3px;
}
.bud-dialog__head-sub {
  font-size: 0.78rem;
  color: rgba(255,255,255,0.72);
  margin: 0;
}
.bud-dialog__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px; height: 32px;
  border-radius: 50%;
  background: rgba(255,255,255,0.12);
  border: none;
  cursor: pointer;
  transition: background 0.15s;
  position: relative; z-index: 1;
}
.bud-dialog__close:hover { background: rgba(255,255,255,0.22); }

/* ── Corps ───────────────────────────────────────────────── */
.bud-dialog__body {
  padding: 22px 22px 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-height: 60vh;
  overflow-y: auto;
}
.bud-row { display: grid; grid-template-columns: 1fr; gap: 14px; }
.bud-field { display: flex; flex-direction: column; gap: 6px; }
.bud-label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: #374151;
}
.bud-section-label {
  display: flex;
  align-items: center;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.4px;
  text-transform: uppercase;
  color: #9ca3af;
  padding-bottom: 8px;
  border-bottom: 1px solid #f3f4f6;
  margin-top: 4px;
}
.bud-ligne {
  display: grid;
  grid-template-columns: 1fr 1fr 36px;
  gap: 8px;
  align-items: center;
}
.bud-del-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px; height: 34px;
  border-radius: 9px;
  background: #fef2f2;
  color: #ef4444;
  border: 1px solid #fecaca;
  cursor: pointer;
  transition: background 0.15s;
}
.bud-del-btn:hover:not(:disabled) { background: #fee2e2; }
.bud-del-btn:disabled { opacity: 0.35; cursor: not-allowed; }

.bud-add-ligne {
  display: inline-flex;
  align-items: center;
  font-size: 0.8125rem;
  font-weight: 600;
  color: #16a34a;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 0;
  transition: opacity 0.15s;
}
.bud-add-ligne:hover { opacity: 0.75; }

/* ── Pied ────────────────────────────────────────────────── */
.bud-dialog__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 22px;
  border-top: 1px solid #f3f4f6;
}
.bud-cancel-btn {
  padding: 0 18px;
  height: 40px;
  border-radius: 10px;
  background: #f3f4f6;
  color: #374151;
  font-size: 0.875rem;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.15s;
}
.bud-cancel-btn:hover:not(:disabled) { background: #e5e7eb; }
.bud-cancel-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.bud-submit-btn {
  display: inline-flex;
  align-items: center;
  padding: 0 22px;
  height: 40px;
  border-radius: 10px;
  background: #16a34a;
  color: #fff;
  font-size: 0.875rem;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.18s, box-shadow 0.18s;
  box-shadow: 0 2px 8px rgba(22,163,74,0.25);
}
.bud-submit-btn:hover:not(:disabled) { background: #15803d; }
.bud-submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
