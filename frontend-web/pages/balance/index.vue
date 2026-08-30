<script setup lang="ts">
definePageMeta({ module: 'CAISSE' })

interface LigneBalance {
  compteNumero: string
  compteLibelle: string
  type: string
  totalDebit: number
  totalCredit: number
  solde: number
}

const api = useApi()
const loading = ref(false)
const erreur = ref('')
const lignes = ref<LigneBalance[]>([])

// ── Devise toggle ──────────────────────────────────────────
// La balance est tenue en USD (devise de base) : c'est la vue par defaut,
// affichee sans conversion. Basculer en FC MULTIPLIE par le taux du jour
// (sens inverse de l'ancienne base CDF, ou la conversion en USD divisait).
const devise = ref<'CDF' | 'USD'>('USD')
const tauxJour = ref<number>(0)   // CDF pour 1 USD; 0 = non chargé

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    lignes.value = await api<LigneBalance[]>('/caisse/balance')
  } catch (e: any) {
    erreur.value = e?.data?.message || 'Impossible de charger la balance.'
  } finally {
    loading.value = false
  }
}

async function chargerTaux() {
  try {
    const data = await api<{ taux: number }>('/admin/taux-change')
    tauxJour.value = data.taux ?? 0
  } catch { /* pas encore configuré */ }
}

onMounted(() => { charger(); chargerTaux() })

const charges = computed(() =>
  lignes.value.filter(l => l.type === 'CHARGE').map(l => ({
    compte: l.compteNumero, libelle: l.compteLibelle, montant: l.solde,
  }))
)
const produits = computed(() =>
  lignes.value.filter(l => l.type === 'PRODUIT').map(l => ({
    compte: l.compteNumero, libelle: l.compteLibelle, montant: l.solde,
  }))
)

const totalCharges  = computed(() => charges.value.reduce((a, c) => a + c.montant, 0))
const totalProduits = computed(() => produits.value.reduce((a, c) => a + c.montant, 0))
const resultat      = computed(() => totalProduits.value - totalCharges.value)

function convertir(v: number) {
  if (devise.value === 'CDF' && tauxJour.value > 0) return v * tauxJour.value
  return v
}

const symbole = computed(() => devise.value === 'USD' ? '$' : 'FC')

function fmt(v: number): string {
  const val = convertir(v)
  if (devise.value === 'USD') {
    return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(val)
  }
  return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 }).format(val)
}

function toggleDevise() {
  if (devise.value === 'USD') {
    if (tauxJour.value <= 0) { erreur.value = "Taux du jour non configuré. Rendez-vous dans Administration → Taux de change."; return }
    devise.value = 'CDF'
  } else {
    devise.value = 'USD'
  }
}
</script>

<template>
  <div class="bal-page">

    <!-- ── Header ──────────────────────────────────────────── -->
    <div class="page-head">
      <div>
        <h1 class="page-title">Balance — Produits &amp; Charges</h1>
        <p class="page-sub">
          Résultat net de l'exercice en cours
          <span v-if="tauxJour > 0" class="bal-taux-badge">
            1 USD = {{ new Intl.NumberFormat('fr-FR').format(tauxJour) }} FC
          </span>
        </p>
      </div>
      <div class="bal-header-actions">
        <button class="bal-devise-toggle" :class="{ 'bal-devise-toggle--usd': devise === 'USD' }" @click="toggleDevise">
          <span class="bal-devise-toggle__pill" :class="{ 'bal-devise-toggle__pill--right': devise === 'USD' }" />
          <span class="bal-devise-toggle__label" :class="{ 'bal-devise-toggle__label--active': devise === 'CDF' }">FC</span>
          <span class="bal-devise-toggle__label" :class="{ 'bal-devise-toggle__label--active': devise === 'USD' }">USD</span>
        </button>
        <v-btn variant="outlined" color="primary" prepend-icon="mdi-refresh" rounded="lg" size="small" @click="charger">
          Actualiser
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card" class="mb-4" />

    <template v-else>
      <!-- ── Grid charges / produits ─────────────────────── -->
      <div class="bal-grid">

        <!-- Charges -->
        <div class="bal-card bal-card--charge">
          <div class="bal-card__header">
            <div class="bal-card__header-left">
              <div class="bal-card__icon bal-card__icon--charge">
                <v-icon icon="mdi-trending-down" size="18" />
              </div>
              <div>
                <p class="bal-card__title">Charges</p>
                <p class="bal-card__count">{{ charges.length }} ligne{{ charges.length !== 1 ? 's' : '' }}</p>
              </div>
            </div>
            <div class="bal-card__total bal-card__total--charge">
              {{ fmt(totalCharges) }} <span class="bal-card__sym">{{ symbole }}</span>
            </div>
          </div>

          <!-- Sous-colonnes -->
          <div class="bal-table">
            <div class="bal-table__head">
              <span>Compte</span>
              <span>Libellé</span>
              <span class="bal-table__num">Montant ({{ symbole }})</span>
            </div>
            <div v-if="charges.length === 0" class="bal-table__empty">
              <v-icon icon="mdi-minus-circle-outline" size="20" color="#d1d5db" />
              <span>Aucune charge enregistrée</span>
            </div>
            <div v-for="c in charges" :key="c.compte" class="bal-table__row">
              <span class="bal-table__code">{{ c.compte }}</span>
              <span class="bal-table__label">{{ c.libelle }}</span>
              <span class="bal-table__amount bal-table__amount--charge">{{ fmt(c.montant) }}</span>
            </div>
            <div class="bal-table__footer bal-table__footer--charge">
              <span class="bal-table__footer-label">Total charges</span>
              <span class="bal-table__footer-val">{{ fmt(totalCharges) }} {{ symbole }}</span>
            </div>
          </div>
        </div>

        <!-- Produits -->
        <div class="bal-card bal-card--produit">
          <div class="bal-card__header">
            <div class="bal-card__header-left">
              <div class="bal-card__icon bal-card__icon--produit">
                <v-icon icon="mdi-trending-up" size="18" />
              </div>
              <div>
                <p class="bal-card__title">Produits</p>
                <p class="bal-card__count">{{ produits.length }} ligne{{ produits.length !== 1 ? 's' : '' }}</p>
              </div>
            </div>
            <div class="bal-card__total bal-card__total--produit">
              {{ fmt(totalProduits) }} <span class="bal-card__sym">{{ symbole }}</span>
            </div>
          </div>

          <div class="bal-table">
            <div class="bal-table__head">
              <span>Compte</span>
              <span>Libellé</span>
              <span class="bal-table__num">Montant ({{ symbole }})</span>
            </div>
            <div v-if="produits.length === 0" class="bal-table__empty">
              <v-icon icon="mdi-minus-circle-outline" size="20" color="#d1d5db" />
              <span>Aucun produit enregistré</span>
            </div>
            <div v-for="p in produits" :key="p.compte" class="bal-table__row">
              <span class="bal-table__code">{{ p.compte }}</span>
              <span class="bal-table__label">{{ p.libelle }}</span>
              <span class="bal-table__amount bal-table__amount--produit">{{ fmt(p.montant) }}</span>
            </div>
            <div class="bal-table__footer bal-table__footer--produit">
              <span class="bal-table__footer-label">Total produits</span>
              <span class="bal-table__footer-val">{{ fmt(totalProduits) }} {{ symbole }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- ── Résultat net ─────────────────────────────────── -->
      <div class="bal-result" :class="resultat >= 0 ? 'bal-result--pos' : 'bal-result--neg'">
        <div class="bal-result__blob" />
        <div class="bal-result__left">
          <p class="bal-result__label">Résultat net</p>
          <p class="bal-result__hint">{{ resultat >= 0 ? '✓ Excédent' : '✗ Déficit' }} de l'exercice en cours</p>
        </div>
        <div class="bal-result__right">
          <span class="bal-result__val">{{ fmt(Math.abs(resultat)) }}</span>
          <span class="bal-result__sym">{{ symbole }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.bal-page { max-width: 1200px; margin: 0 auto; padding-bottom: 48px; }

/* ── Header ──────────────────────────────────────────────── */
.bal-header-actions { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

.bal-taux-badge {
  display: inline-block;
  font-size: 0.72rem;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 100px;
  background: #dbeafe;
  color: #1d4ed8;
  margin-left: 8px;
  vertical-align: middle;
}

/* ── Devise toggle ───────────────────────────────────────── */
.bal-devise-toggle {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: #f3f4f6;
  border: 1.5px solid #e5e7eb;
  border-radius: 100px;
  padding: 4px 6px;
  cursor: pointer;
  font-size: 0.78rem;
  font-weight: 700;
  min-width: 88px;
  transition: border-color 0.2s;
}
.bal-devise-toggle:hover { border-color: #16a34a; }
.bal-devise-toggle__pill {
  position: absolute;
  top: 3px; left: 3px;
  width: 38px; height: calc(100% - 6px);
  border-radius: 100px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0,0,0,0.12);
  transition: transform 0.22s cubic-bezier(.4,0,.2,1);
}
.bal-devise-toggle__pill--right { transform: translateX(40px); }
.bal-devise-toggle__label {
  position: relative;
  z-index: 1;
  width: 40px;
  text-align: center;
  color: #9ca3af;
  transition: color 0.15s;
  user-select: none;
}
.bal-devise-toggle__label--active { color: #111827; }

/* ── Grid ────────────────────────────────────────────────── */
.bal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 20px;
  margin-bottom: 20px;
}

/* ── Cards ───────────────────────────────────────────────── */
.bal-card {
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.bal-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 14px;
}
.bal-card__header-left { display: flex; align-items: center; gap: 12px; }

.bal-card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px; height: 38px;
  border-radius: 10px;
  flex-shrink: 0;
}
.bal-card__icon--charge  { background: #fee2e2; color: #dc2626; }
.bal-card__icon--produit { background: #dcfce7; color: #16a34a; }

.bal-card__title {
  font-size: 0.95rem;
  font-weight: 700;
  color: #111827;
  margin: 0;
}
.bal-card__count {
  font-size: 0.72rem;
  color: #9ca3af;
  margin: 0;
}

.bal-card__total {
  font-size: 1.1rem;
  font-weight: 800;
  letter-spacing: -0.3px;
  font-variant-numeric: tabular-nums;
}
.bal-card__total--charge  { color: #dc2626; }
.bal-card__total--produit { color: #16a34a; }
.bal-card__sym { font-size: 0.78rem; font-weight: 600; margin-left: 2px; }

/* ── Table ───────────────────────────────────────────────── */
.bal-table { overflow-x: auto; }

.bal-table__head {
  display: grid;
  grid-template-columns: 80px 1fr 130px;
  padding: 8px 20px;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  color: #9ca3af;
  background: #fafafa;
  border-top: 1px solid #f3f4f6;
  border-bottom: 1px solid #f3f4f6;
}
.bal-table__num { text-align: right; }

.bal-table__row {
  display: grid;
  grid-template-columns: 80px 1fr 130px;
  align-items: center;
  padding: 11px 20px;
  border-bottom: 1px solid #f9fafb;
  transition: background 0.12s;
}
.bal-table__row:hover { background: #fafafa; }

.bal-table__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 28px 20px;
  font-size: 0.85rem;
  color: #9ca3af;
}

.bal-table__code {
  display: inline-block;
  font-size: 0.68rem;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 6px;
  background: #f3f4f6;
  color: #6b7280;
  width: fit-content;
}
.bal-table__label {
  font-size: 0.875rem;
  color: #374151;
  padding: 0 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bal-table__amount {
  text-align: right;
  font-size: 0.875rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.bal-table__amount--charge  { color: #dc2626; }
.bal-table__amount--produit { color: #16a34a; }

.bal-table__footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 20px;
  font-weight: 700;
  font-size: 0.875rem;
  border-top: 2px solid #f3f4f6;
}
.bal-table__footer-label { color: #374151; }
.bal-table__footer-val { font-variant-numeric: tabular-nums; }
.bal-table__footer--charge  .bal-table__footer-val { color: #dc2626; }
.bal-table__footer--produit .bal-table__footer-val { color: #16a34a; }

/* ── Résultat net ────────────────────────────────────────── */
.bal-result {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px;
  border-radius: 20px;
  flex-wrap: wrap;
}
.bal-result--pos { background: linear-gradient(135deg, #16a34a 0%, #15803d 50%, #14532d 100%); }
.bal-result--neg { background: linear-gradient(135deg, #ef4444 0%, #dc2626 50%, #7f1d1d 100%); }

.bal-result__blob {
  position: absolute;
  width: 200px; height: 200px;
  border-radius: 50%;
  background: rgba(255,255,255,0.08);
  top: -60px; right: -60px;
  pointer-events: none;
}

.bal-result__left { position: relative; z-index: 1; }
.bal-result__label {
  font-size: 1rem;
  font-weight: 700;
  color: #fff;
  margin: 0 0 4px;
}
.bal-result__hint {
  font-size: 0.82rem;
  color: rgba(255,255,255,0.72);
  margin: 0;
}
.bal-result__right {
  display: flex;
  align-items: baseline;
  gap: 6px;
  position: relative; z-index: 1;
}
.bal-result__val {
  font-size: 2rem;
  font-weight: 800;
  color: #fff;
  letter-spacing: -1px;
  font-variant-numeric: tabular-nums;
}
.bal-result__sym {
  font-size: 1rem;
  font-weight: 700;
  color: rgba(255,255,255,0.80);
}
</style>
