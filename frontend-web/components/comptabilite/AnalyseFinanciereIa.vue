<script setup lang="ts">
interface AnalyseFinanciere {
  du: string
  au: string
  synthese: string
  pointsForts: string[]
  pointsAttention: string[]
  recommandations: string[]
  genereParIa: boolean
}

// du est optionnel (le Bilan est une photo a une date, sans borne de debut) ;
// au est toujours requis, comme sur les 3 pages qui utilisent ce composant.
const props = defineProps<{ du?: string, au: string }>()

const api = useApi()
const auth = useAuthStore()
const chargement = ref(false)
const erreur = ref('')
const analyse = ref<AnalyseFinanciere | null>(null)

async function genererAnalyse() {
  chargement.value = true
  erreur.value = ''
  try {
    const params = new URLSearchParams({ au: props.au })
    if (props.du) params.set('du', props.du)
    analyse.value = await api<AnalyseFinanciere>(`/comptabilite/etats-financiers/analyse-ia?${params}`, { method: 'POST' })
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de générer l'analyse IA.")
  } finally {
    chargement.value = false
  }
}
</script>

<template>
  <div v-if="auth.hasAnyRole(['DFIN', 'ADMIN'])" class="analyse-ia">
    <div class="analyse-ia__trigger no-print">
      <v-btn
        variant="tonal"
        color="deep-purple"
        rounded="lg"
        :prepend-icon="analyse ? 'mdi-refresh' : 'mdi-creation'"
        :loading="chargement"
        @click="genererAnalyse"
      >
        {{ analyse ? "Régénérer l'analyse IA" : "Générer l'analyse IA" }}
      </v-btn>
      <v-alert v-if="erreur" type="error" variant="tonal" density="compact" class="mt-2">{{ erreur }}</v-alert>
    </div>

    <div v-if="analyse" class="analyse-ia__card">
      <div class="analyse-ia__head">
        <v-icon icon="mdi-creation" size="22" />
        <span class="analyse-ia__title">Mes Analyses</span>
      </div>

      <p class="analyse-ia__synthese">{{ analyse.synthese }}</p>

      <div class="analyse-ia__grid">
        <div v-if="analyse.pointsForts.length" class="analyse-ia__col">
          <h4 class="analyse-ia__col-title analyse-ia__col-title--forts">
            <v-icon icon="mdi-check-circle-outline" size="15" /> Points forts
          </h4>
          <ul>
            <li v-for="(p, i) in analyse.pointsForts" :key="i">{{ p }}</li>
          </ul>
        </div>

        <div v-if="analyse.pointsAttention.length" class="analyse-ia__col">
          <h4 class="analyse-ia__col-title analyse-ia__col-title--attention">
            <v-icon icon="mdi-alert-outline" size="15" /> Points de vigilance
          </h4>
          <ul>
            <li v-for="(p, i) in analyse.pointsAttention" :key="i">{{ p }}</li>
          </ul>
        </div>
      </div>

      <div v-if="analyse.recommandations.length" class="analyse-ia__reco">
        <h4 class="analyse-ia__col-title analyse-ia__col-title--reco">
          <v-icon icon="mdi-lightbulb-on-outline" size="15" /> Recommandations
        </h4>
        <ol>
          <li v-for="(r, i) in analyse.recommandations" :key="i">{{ r }}</li>
        </ol>
      </div>

      <div class="analyse-ia__signature">
        <span class="analyse-ia__signature-label">Directeur Financier</span>
        <span class="analyse-ia__signature-name">{{ auth.fullName || '—' }}</span>
        <div class="analyse-ia__signature-line" />
        <span class="analyse-ia__signature-hint">Signature et cachet</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.analyse-ia__card {
  background: #fff;
  border: 1px solid #ede9fe;
  border-left: 3px solid #7c3aed;
  border-radius: 16px;
  padding: 20px 22px;
  margin-top: 20px;
  box-shadow: 0 2px 10px rgba(124, 58, 237, 0.06);
  /* Evite qu'une carte a cheval sur un saut de page imprime un fragment de
     bordure arrondie sur chaque page (rendu double du contour observe a
     l'impression) : on bascule la carte entiere sur la page suivante si
     necessaire plutot que de la couper. */
  break-inside: avoid;
  page-break-inside: avoid;
}
.analyse-ia__head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; color: #7c3aed; }
.analyse-ia__title { font-size: 0.95rem; font-weight: 700; color: #111827; }
.analyse-ia__synthese { font-size: 0.875rem; color: #374151; line-height: 1.6; margin: 0 0 18px; }

.analyse-ia__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-bottom: 16px; }
.analyse-ia__col-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 0.72rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.4px;
  margin: 0 0 8px;
}
.analyse-ia__col-title--forts { color: #16a34a; }
.analyse-ia__col-title--attention { color: #d97706; }
.analyse-ia__col-title--reco { color: #7c3aed; }
.analyse-ia__col ul,
.analyse-ia__reco ol { margin: 0; padding-left: 18px; display: flex; flex-direction: column; gap: 6px; }
.analyse-ia__col li,
.analyse-ia__reco li { font-size: 0.8125rem; color: #374151; line-height: 1.5; }

.analyse-ia__reco { padding-top: 14px; border-top: 1px dashed #ede9fe; }

.analyse-ia__signature {
  margin: 24px 0 0 auto; padding-top: 12px; width: 220px;
  display: flex; flex-direction: column; align-items: center; text-align: center;
}
.analyse-ia__signature-label { font-size: 0.72rem; font-weight: 700; color: #111827; }
.analyse-ia__signature-name { font-size: 0.8125rem; color: #374151; margin-top: 2px; }
.analyse-ia__signature-line { width: 100%; border-top: 1px solid #9ca3af; margin: 28px 0 4px; }
.analyse-ia__signature-hint { font-size: 0.68rem; color: #9ca3af; font-style: italic; }

@media (max-width: 640px) {
  .analyse-ia__grid { grid-template-columns: 1fr; }
}

@media print {
  /* Les recommandations demarrent sur une nouvelle page plutot que d'etre
     coupees en bas de la page precedente ou de faire deborder la signature
     seule sur la page suivante. */
  .analyse-ia__reco {
    break-before: page;
    page-break-before: always;
  }
  .analyse-ia__signature {
    break-inside: avoid;
    page-break-inside: avoid;
  }
}
</style>
