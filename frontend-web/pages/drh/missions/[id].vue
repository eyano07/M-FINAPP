<script setup lang="ts">
definePageMeta({ module: 'DRH_MISSIONS' })

interface AgentMission {
  id: number; employeId: number | null; employeNomComplet: string | null; nomLibre: string | null
  nomAffiche: string; nationalite: string | null; numeroPasseport: string | null
  fonctionMission: string | null; civilite: string | null
}
interface Mission {
  id: number; numero: string; lieuMission: string; distanceVille: number | null; province: string | null
  territoire: string | null; superviseurNomComplet: string | null; superviseurCivilite: string | null
  butMission: string; dureeMission: string | null; dateDepart: string; dateRetour: string
  moyenTransport: string | null; fraisMission: string | null; collective: boolean; agents: AgentMission[]
}

const route = useRoute()
const api = useApi()
const parametresStore = useParametresStore()

const loading = ref(true)
const erreur = ref('')
const mission = ref<Mission | null>(null)

async function charger() {
  loading.value = true
  erreur.value = ''
  try {
    mission.value = await api<Mission>(`/drh/missions/${route.params.id}`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, "Impossible de charger l'ordre de mission.")
  } finally {
    loading.value = false
  }
}
onMounted(() => { charger(); parametresStore.charger() })

const dateImpression = ref('')
function imprimer() {
  dateImpression.value = new Date().toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  nextTick(() => window.print())
}

const telechargementPdf = ref(false)
async function telechargerPdf() {
  if (!mission.value) return
  telechargementPdf.value = true
  erreur.value = ''
  try {
    await telechargerFichier(api, `/drh/missions/${mission.value.id}/pdf`, `ordre-mission-${mission.value.numero}.pdf`)
  } catch (e: any) {
    erreur.value = messageErreurApi(e, 'Impossible de générer le PDF.')
  } finally {
    telechargementPdf.value = false
  }
}

const fmtDate = (d: string) => (d ? new Date(d).toLocaleDateString('fr-FR') : '—')
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Ordre de mission</h1>
        <p class="page-sub" v-if="mission">{{ mission.numero }} — {{ mission.lieuMission }}</p>
      </div>
      <div class="d-flex ga-2">
        <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/drh/missions">Retour</v-btn>
        <v-btn color="error" variant="tonal" rounded="lg" prepend-icon="mdi-printer-outline" :disabled="!mission" @click="imprimer">
          Imprimer
        </v-btn>
        <v-btn color="primary" variant="flat" rounded="lg" prepend-icon="mdi-file-pdf-box"
          :loading="telechargementPdf" :disabled="!mission" @click="telechargerPdf">
          PDF sur papier entête
        </v-btn>
      </div>
    </div>

    <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 no-print">{{ erreur }}</v-alert>
    <v-skeleton-loader v-if="loading" type="article" />

    <template v-else-if="mission">
      <div class="etat-print-header">
        <div class="etat-print-header__brand">
          <div class="etat-print-header__logo" :class="{ 'etat-print-header__logo--image': parametresStore.parametres.logoUrl }">
            <img v-if="parametresStore.parametres.logoUrl" :src="parametresStore.parametres.logoUrl" alt="Logo">
            <v-icon v-else icon="mdi-finance" size="16" color="white" />
          </div>
          <div>
            <span class="etat-print-header__company">{{ parametresStore.parametres.nom }}</span>
            <span class="etat-print-header__service">Direction des Ressources Humaines</span>
            <span class="etat-print-header__doc">Ordre de mission {{ mission.numero }}</span>
          </div>
        </div>
        <div class="etat-print-header__meta">
          <span>Imprimé le : {{ dateImpression }}</span>
        </div>
      </div>

      <v-card class="classroom-card pa-6 mb-4">
        <v-row>
          <v-col cols="12" md="4"><span class="calc-label">Site de la mission</span><br><strong>{{ mission.lieuMission }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Territoire</span><br><strong>{{ mission.territoire ?? '—' }}</strong></v-col>
          <v-col cols="12" md="4"><span class="calc-label">Province</span><br><strong>{{ mission.province ?? '—' }}</strong></v-col>
          <v-col v-if="mission.collective" cols="12" md="6">
            <span class="calc-label">Superviseur de la mission</span><br>
            <strong>{{ mission.superviseurCivilite }} {{ mission.superviseurNomComplet ?? '—' }}</strong>
          </v-col>
          <v-col cols="12" md="3"><span class="calc-label">Distance</span><br><strong>{{ mission.distanceVille ?? '—' }} km</strong></v-col>
          <v-col cols="12" md="3"><span class="calc-label">Date de départ</span><br><strong>{{ fmtDate(mission.dateDepart) }}</strong></v-col>
          <v-col cols="12" md="3"><span class="calc-label">Date de retour</span><br><strong>{{ fmtDate(mission.dateRetour) }}</strong></v-col>
          <v-col cols="12" md="3"><span class="calc-label">Durée</span><br><strong>{{ mission.dureeMission ?? '—' }}</strong></v-col>
          <v-col cols="12" md="3"><span class="calc-label">Moyen de transport</span><br><strong>{{ mission.moyenTransport ?? '—' }}</strong></v-col>
          <v-col cols="12"><span class="calc-label">But de la mission</span><br><strong>{{ mission.butMission }}</strong></v-col>
          <v-col cols="12" md="6"><span class="calc-label">Frais de mission</span><br><strong>{{ mission.fraisMission ?? '—' }}</strong></v-col>
        </v-row>
      </v-card>

      <section class="etat-bloc">
        <h2 class="etat-titre">Agents en mission</h2>
        <table class="etat-table">
          <thead><tr><th>Civilité</th><th>Nom complet</th><th>Nationalité</th><th>N° Passeport</th><th>Fonction en mission</th></tr></thead>
          <tbody>
            <tr v-for="a in mission.agents" :key="a.id">
              <td>{{ a.civilite ?? '—' }}</td><td>{{ a.nomAffiche }}</td>
              <td>{{ a.nationalite ?? '—' }}</td><td>{{ a.numeroPasseport ?? '—' }}</td>
              <td>{{ a.fonctionMission ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 24px; flex-wrap: wrap; }
.page-title { font-size: 1.5rem; font-weight: 700; color: #111827; margin: 0; }
.page-sub { font-size: 0.875rem; color: #6b7280; margin: 2px 0 0; }
.calc-label { font-size: 0.7rem; color: #6b7280; text-transform: uppercase; letter-spacing: 0.4px; }
.etat-bloc { margin-bottom: 20px; }
.etat-titre { font-size: 1.05rem; font-weight: 700; color: #111827; margin: 0 0 10px; padding-bottom: 6px; border-bottom: 2px solid #16a34a; }
.etat-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.etat-table th { text-align: left; padding: 7px 8px; font-size: 0.68rem; text-transform: uppercase; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
.etat-table td { padding: 6px 8px; border-bottom: 1px solid #f3f4f6; color: #374151; }
</style>
