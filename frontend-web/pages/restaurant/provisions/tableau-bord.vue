<script setup lang="ts">
definePageMeta({ module: 'RESTAURANT' })

/**
 * Tableau de bord des provisions de cuisine : achats, consommation, stock.
 *
 * Contrairement au tableau de bord de la carte, pas de ventes ni de profit :
 * une provision est un centre de coût, jamais revendue directement.
 */
import { Bar, Doughnut } from 'vue-chartjs'

interface ProvisionStat {
  articleId: number
  code: string
  libelle: string
  uniteMesure?: string
  quantiteAchetee: number
  quantiteConsommee: number
  stockActuel: number
  valeurStock: number
  sousSeuil: boolean
}
interface MouvementJour { date: string; quantiteEntree: number; quantiteSortie: number }
interface TableauBordProvisions {
  du: string
  au: string
  nombreProvisions: number
  valeurStockActuel: number
  nombreSousSeuil: number
  quantiteAchetee: number
  montantAchats: number
  nombreReceptions: number
  quantiteConsommee: number
  montantConsomme: number
  nombreSorties: number
  topConsommees: ProvisionStat[]
  parProvision: ProvisionStat[]
  mouvementsParJour: MouvementJour[]
}
interface AnalyseIa {
  du: string; au: string; synthese: string
  pointsForts: string[]; pointsAttention: string[]; recommandations: string[]
  genereParIa: boolean
}

const api = useApi()
const parametres = useRestaurantParametresStore()
const loading = ref(false)
const erreur = ref('')
const tb = ref<TableauBordProvisions | null>(null)

const MOIS = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']
const aujourdhui = new Date()
const mois = ref(aujourdhui.getMonth() + 1)
const annee = ref(aujourdhui.getFullYear())

const du = computed(() => `${annee.value}-${String(mois.value).padStart(2, '0')}-01`)
const au = computed(() => {
  const dernierJour = new Date(annee.value, mois.value, 0).getDate()
  return `${annee.value}-${String(mois.value).padStart(2, '0')}-${String(dernierJour).padStart(2, '0')}`
})

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    const [data] = await Promise.all([
      api<TableauBordProvisions>('/restaurant/provisions/tableau-bord', { params: { du: du.value, au: au.value } }),
      parametres.charger(),
    ])
    tb.value = data
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de charger le tableau de bord.')
  } finally {
    loading.value = false
  }
}
onMounted(charger)
watch([mois, annee], charger)

const fmtNb = (n?: number | null) => new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 2 }).format(n || 0)

// ── Analyse IA (à la demande) ────────────────────────────────────────────
const chargementIa = ref(false)
const erreurIa = ref('')
const analyse = ref<AnalyseIa | null>(null)

async function genererAnalyse() {
  chargementIa.value = true
  erreurIa.value = ''
  try {
    analyse.value = await api<AnalyseIa>('/restaurant/provisions/tableau-bord/analyse-ia', {
      method: 'POST',
      params: { du: du.value, au: au.value },
    })
  } catch (e: any) {
    erreurIa.value = messageErreurApi(e, "Impossible de générer l'analyse IA.")
  } finally {
    chargementIa.value = false
  }
}
watch([mois, annee], () => { analyse.value = null })

// ── Graphiques ────────────────────────────────────────────────────────────
const cb: any = {
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { labels: { font: { family: 'Inter, sans-serif', size: 11 }, color: '#6b7280', boxWidth: 12, padding: 14 } },
    tooltip: { backgroundColor: '#1f2937', padding: 10, cornerRadius: 8, titleFont: { size: 12 }, bodyFont: { size: 11 } },
  },
}
const fmtDateCourte = (d: string) => new Date(d).toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' })

/** Entrées et sorties par jour, deux séries de barres. */
const mouvementsJourData = computed(() => ({
  labels: (tb.value?.mouvementsParJour || []).map(m => fmtDateCourte(m.date)),
  datasets: [
    { label: 'Entrées (achats)', data: (tb.value?.mouvementsParJour || []).map(m => m.quantiteEntree), backgroundColor: 'rgba(22,163,74,0.7)', borderColor: '#16a34a', borderRadius: 6 },
    { label: 'Sorties (consommation)', data: (tb.value?.mouvementsParJour || []).map(m => m.quantiteSortie), backgroundColor: 'rgba(220,38,38,0.7)', borderColor: '#dc2626', borderRadius: 6 },
  ],
}))
const mouvementsJourOpts: any = { ...cb, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } }

const COULEURS = ['#16a34a', '#2563eb', '#f59e0b', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#65a30d']

/** Répartition de la consommation entre provisions. */
const repartitionConsoData = computed(() => {
  const items = tb.value?.parProvision.filter(p => p.quantiteConsommee > 0) || []
  return {
    labels: items.map(p => p.libelle),
    datasets: [{
      data: items.map(p => p.quantiteConsommee),
      backgroundColor: items.map((_, i) => COULEURS[i % COULEURS.length] + 'cc'),
      borderColor: items.map((_, i) => COULEURS[i % COULEURS.length]),
      borderWidth: 2,
      hoverOffset: 8,
    }],
  }
})
const repartitionOpts: any = { ...cb, plugins: { ...cb.plugins, legend: { ...cb.plugins.legend, position: 'bottom' } }, cutout: '55%' }

/** Valeur du stock par provision. */
const stockData = computed(() => {
  const items = (tb.value?.parProvision || []).filter(p => p.valeurStock > 0)
  return {
    labels: items.map(p => p.libelle),
    datasets: [{ label: 'Valeur en stock', data: items.map(p => p.valeurStock), backgroundColor: 'rgba(126,34,206,0.65)', borderColor: '#7e22ce', borderRadius: 6 }],
  }
})
const stockOpts: any = { ...cb, indexAxis: 'y' as const, scales: { x: { beginAtZero: true } } }
</script>

<template>
  <div class="rdb-page">
    <div class="page-head">
      <div>
        <h1 class="page-title">Tableau de bord — Provisions</h1>
        <p class="page-sub">Achats, consommation et stock de cuisine</p>
      </div>
      <div class="d-flex ga-2">
        <v-select v-model="mois" :items="MOIS.map((m, i) => ({ title: m, value: i + 1 }))" label="Mois" variant="outlined"
          density="comfortable" rounded="lg" hide-details style="max-width: 160px" />
        <v-text-field v-model.number="annee" type="number" label="Année" variant="outlined" density="comfortable"
          rounded="lg" hide-details style="max-width: 120px" />
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" rounded="lg" class="mb-4" closable @click:close="erreur = ''">
      {{ erreur }}
    </v-alert>

    <v-skeleton-loader v-if="loading" type="card, card, card" />

    <template v-else-if="tb">
      <div class="rdb-kpis mb-5">
        <div class="rdb-kpi rdb-kpi--stock">
          <v-icon icon="mdi-sack" size="20" />
          <div><span class="rdb-kpi__val">{{ parametres.fmtMontant(tb.valeurStockActuel) }}</span><span class="rdb-kpi__lbl">Valeur du stock</span></div>
        </div>
        <div class="rdb-kpi rdb-kpi--achats">
          <v-icon icon="mdi-truck-delivery-outline" size="20" />
          <div><span class="rdb-kpi__val">{{ parametres.fmtMontant(tb.montantAchats) }}</span><span class="rdb-kpi__lbl">Achats de la période</span></div>
        </div>
        <div class="rdb-kpi rdb-kpi--conso">
          <v-icon icon="mdi-fire" size="20" />
          <div><span class="rdb-kpi__val">{{ parametres.fmtMontant(tb.montantConsomme) }}</span><span class="rdb-kpi__lbl">Consommation</span></div>
        </div>
        <div class="rdb-kpi rdb-kpi--nb">
          <v-icon icon="mdi-format-list-bulleted" size="20" />
          <div><span class="rdb-kpi__val">{{ tb.nombreProvisions }}</span><span class="rdb-kpi__lbl">Provisions référencées</span></div>
        </div>
        <div class="rdb-kpi" :class="tb.nombreSousSeuil > 0 ? 'rdb-kpi--alerte' : 'rdb-kpi--ok'">
          <v-icon :icon="tb.nombreSousSeuil > 0 ? 'mdi-alert-outline' : 'mdi-check-circle-outline'" size="20" />
          <div><span class="rdb-kpi__val">{{ tb.nombreSousSeuil }}</span><span class="rdb-kpi__lbl">Sous le seuil</span></div>
        </div>
      </div>

      <div class="rdb-charts mb-5">
        <div class="rdb-chart rdb-chart--wide">
          <p class="rdb-chart__title"><v-icon icon="mdi-chart-bar" size="15" class="mr-1" />Entrées et sorties par jour</p>
          <div class="rdb-chart__body">
            <Bar v-if="tb.mouvementsParJour.length" :data="mouvementsJourData" :options="mouvementsJourOpts" />
            <p v-else class="rdb-chart__empty">Aucun mouvement sur cette période.</p>
          </div>
        </div>
        <div class="rdb-chart">
          <p class="rdb-chart__title"><v-icon icon="mdi-chart-donut" size="15" class="mr-1" />Répartition de la consommation</p>
          <div class="rdb-chart__body">
            <Doughnut v-if="repartitionConsoData.labels.length" :data="repartitionConsoData" :options="repartitionOpts" />
            <p v-else class="rdb-chart__empty">Aucune consommation sur cette période.</p>
          </div>
        </div>
        <div class="rdb-chart">
          <p class="rdb-chart__title"><v-icon icon="mdi-chart-bar" size="15" class="mr-1" />Valeur du stock par provision</p>
          <div class="rdb-chart__body">
            <Bar v-if="stockData.labels.length" :data="stockData" :options="stockOpts" />
            <p v-else class="rdb-chart__empty">Aucun stock valorisé.</p>
          </div>
        </div>
      </div>

      <v-card class="classroom-card mb-5">
        <div class="rdb-card-head">
          <v-icon icon="mdi-trophy-outline" size="18" class="mr-2" />
          Provisions les plus consommées
        </div>
        <v-data-table
          :headers="[
            { title: 'Provision', key: 'libelle' },
            { title: 'Quantité consommée', key: 'quantiteConsommee', align: 'end' },
            { title: 'Stock actuel', key: 'stockActuel', align: 'end' },
          ]"
          :items="tb.topConsommees"
          items-per-page="5"
          hide-default-footer
        >
          <template #item.quantiteConsommee="{ item }">{{ fmtNb(item.quantiteConsommee) }} {{ item.uniteMesure || '' }}</template>
          <template #item.stockActuel="{ item }">{{ fmtNb(item.stockActuel) }} {{ item.uniteMesure || '' }}</template>
          <template #no-data>
            <div class="pa-6 text-center text-medium-emphasis">Aucune consommation sur cette période.</div>
          </template>
        </v-data-table>
      </v-card>

      <v-card class="classroom-card mb-5">
        <div class="rdb-card-head">
          <v-icon icon="mdi-format-list-bulleted" size="18" class="mr-2" />
          Détail par provision
        </div>
        <v-data-table
          :headers="[
            { title: 'Code', key: 'code' },
            { title: 'Provision', key: 'libelle' },
            { title: 'Acheté', key: 'quantiteAchetee', align: 'end' },
            { title: 'Consommé', key: 'quantiteConsommee', align: 'end' },
            { title: 'Stock actuel', key: 'stockActuel', align: 'end' },
            { title: 'Valeur', key: 'valeurStock', align: 'end' },
          ]"
          :items="tb.parProvision"
          items-per-page="10"
        >
          <template #item.quantiteAchetee="{ item }">{{ fmtNb(item.quantiteAchetee) }}</template>
          <template #item.quantiteConsommee="{ item }">{{ fmtNb(item.quantiteConsommee) }}</template>
          <template #item.stockActuel="{ item }">
            <span :class="item.sousSeuil ? 'font-weight-bold text-error' : ''">{{ fmtNb(item.stockActuel) }} {{ item.uniteMesure || '' }}</span>
          </template>
          <template #item.valeurStock="{ item }">{{ parametres.fmtMontant(item.valeurStock) }}</template>
        </v-data-table>
      </v-card>

      <div class="rdb-ia">
        <v-btn
          variant="tonal"
          color="deep-purple"
          rounded="lg"
          :prepend-icon="analyse ? 'mdi-refresh' : 'mdi-creation'"
          :loading="chargementIa"
          @click="genererAnalyse"
        >
          {{ analyse ? "Régénérer l'analyse IA" : "Générer l'analyse IA" }}
        </v-btn>
        <v-alert v-if="erreurIa" type="error" variant="tonal" density="compact" class="mt-2">{{ erreurIa }}</v-alert>

        <div v-if="analyse" class="rdb-ia__card">
          <div class="rdb-ia__head">
            <v-icon icon="mdi-creation" size="22" />
            <span class="rdb-ia__title">Analyse et recommandations</span>
            <v-chip v-if="!analyse.genereParIa" size="x-small" variant="tonal" color="grey" class="ml-2">Synthèse locale</v-chip>
          </div>

          <p class="rdb-ia__synthese">{{ analyse.synthese }}</p>

          <div class="rdb-ia__grid">
            <div v-if="analyse.pointsForts.length" class="rdb-ia__col">
              <h4 class="rdb-ia__col-title rdb-ia__col-title--forts"><v-icon icon="mdi-check-circle-outline" size="15" /> Points forts</h4>
              <ul><li v-for="(p, i) in analyse.pointsForts" :key="i">{{ p }}</li></ul>
            </div>
            <div v-if="analyse.pointsAttention.length" class="rdb-ia__col">
              <h4 class="rdb-ia__col-title rdb-ia__col-title--attention"><v-icon icon="mdi-alert-outline" size="15" /> Points de vigilance</h4>
              <ul><li v-for="(p, i) in analyse.pointsAttention" :key="i">{{ p }}</li></ul>
            </div>
          </div>

          <div v-if="analyse.recommandations.length" class="rdb-ia__reco">
            <h4 class="rdb-ia__col-title rdb-ia__col-title--reco"><v-icon icon="mdi-lightbulb-on-outline" size="15" /> Recommandations</h4>
            <ol><li v-for="(r, i) in analyse.recommandations" :key="i">{{ r }}</li></ol>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.rdb-page { max-width: 1280px; margin: 0 auto; padding-bottom: 48px; }

.rdb-kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.rdb-kpi { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 14px; background: #f9fafb; }
.rdb-kpi--stock  { background: linear-gradient(135deg,#faf5ff,#f3e8ff); color: #7e22ce; }
.rdb-kpi--achats { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rdb-kpi--conso  { background: linear-gradient(135deg,#fff7ed,#ffedd5); color: #c2410c; }
.rdb-kpi--nb     { background: linear-gradient(135deg,#eff6ff,#dbeafe); color: #1d4ed8; }
.rdb-kpi--alerte { background: linear-gradient(135deg,#fef2f2,#fee2e2); color: #b91c1c; }
.rdb-kpi--ok     { background: linear-gradient(135deg,#f0fdf4,#dcfce7); color: #15803d; }
.rdb-kpi__val { display: block; font-size: 1.05rem; font-weight: 800; letter-spacing: -0.4px; }
.rdb-kpi__lbl { display: block; font-size: 0.72rem; font-weight: 500; opacity: 0.8; }

.rdb-charts { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
.rdb-chart { background: #fff; border: 1px solid #f0f0f0; border-radius: 16px; padding: 16px 18px 20px; }
.rdb-chart--wide { grid-column: 1 / -1; }
.rdb-chart__title { font-size: 0.8rem; font-weight: 700; color: #374151; margin: 0 0 12px; display: flex; align-items: center; }
.rdb-chart__body { height: 260px; position: relative; }
.rdb-chart__empty { display: flex; align-items: center; justify-content: center; height: 100%; color: #9ca3af; font-size: 0.82rem; }

@media (max-width: 900px) { .rdb-charts { grid-template-columns: 1fr; } }

.rdb-card-head {
  display: flex; align-items: center; font-size: 0.8rem; font-weight: 700;
  letter-spacing: 0.3px; text-transform: uppercase; color: #6b7280; padding: 16px 20px 12px;
}

.rdb-ia__card {
  background: #fff; border: 1px solid #ede9fe; border-left: 3px solid #7c3aed;
  border-radius: 16px; padding: 20px 22px; margin-top: 16px; box-shadow: 0 2px 10px rgba(124, 58, 237, 0.06);
}
.rdb-ia__head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; color: #7c3aed; }
.rdb-ia__title { font-size: 0.95rem; font-weight: 700; color: #111827; }
.rdb-ia__synthese { font-size: 0.875rem; color: #374151; line-height: 1.6; margin: 0 0 18px; }
.rdb-ia__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 16px; }
.rdb-ia__col-title { display: flex; align-items: center; gap: 6px; font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px; margin: 0 0 8px; }
.rdb-ia__col-title--forts { color: #16a34a; }
.rdb-ia__col-title--attention { color: #d97706; }
.rdb-ia__col-title--reco { color: #7c3aed; }
.rdb-ia__col ul, .rdb-ia__reco ol { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; }
.rdb-ia__col li, .rdb-ia__reco li { font-size: 0.8125rem; color: #374151; line-height: 1.5; }
.rdb-ia__reco { padding-top: 14px; border-top: 1px dashed #ede9fe; }

@media (max-width: 640px) { .rdb-ia__grid { grid-template-columns: 1fr; } }
</style>
