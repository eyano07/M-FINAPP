<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'
import { MENAGE, dateHeure } from '~/utils/libelles'

/** Ménage : état de chaque unité en temps réel et tâches (créées aux départs), pensé pour la tablette et le téléphone. */
useHead({ title: 'Ménage' })

interface Etat { id: number, etablissementNom: string, numero: string, typeNom: string, etage: string | null, etatMenage: string,
  horsService: boolean, motifHorsService: string | null, occupee: boolean, clientActuel: string | null, departPrevu: string | null, arriveePrevue: string | null }
interface Tache { id: number, uniteId: number, uniteNumero: string, etablissementNom: string, type: string, statut: string, priorite: string,
  assigneNom: string | null, notes: string | null, creeLe: string, termineLe: string | null }

const api = useApi()
const session = useSessionStore()
const etats = ref<Etat[]>([])
const taches = ref<Tache[]>([])
const filtre = ref<string>('TOUS')
const erreur = ref('')
const ecrit = computed(() => session.a('GOUVERNANTE', 'RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN'))

async function charger() {
  try {
    const query = { etablissementId: useEtablissementStore().filtre }
    const [e, t] = await Promise.all([api<Etat[]>('/menage/unites', { query }), api<Tache[]>('/menage/taches', { query })])
    etats.value = e
    taches.value = t
    erreur.value = ''
  } catch (err) {
    erreur.value = messageErreur(err)
  }
}
let minuterie: ReturnType<typeof setInterval> | null = null
onMounted(() => { charger(); minuterie = setInterval(charger, 15_000) })
onBeforeUnmount(() => { if (minuterie) clearInterval(minuterie) })

const etatsFiltres = computed(() => filtre.value === 'TOUS' ? etats.value : etats.value.filter(e => e.etatMenage === filtre.value))
const compte = (etat: string) => etats.value.filter(e => e.etatMenage === etat).length

async function agir(f: () => Promise<unknown>) {
  try { await f(); await charger() } catch (e) { erreur.value = messageErreur(e) }
}
const changer = (u: Etat, etat: string) => agir(() => api(`/menage/unites/${u.id}/etat`, { method: 'PUT', body: { etat } }))
const commencer = (t: Tache) => agir(() => api(`/menage/taches/${t.id}/commencer`, { method: 'POST' }))
const terminer = (t: Tache) => agir(() => api(`/menage/taches/${t.id}/terminer`, { method: 'POST' }))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Ménage</h1>
        <p class="page-sub">État des unités en temps réel et tâches de nettoyage</p>
      </div>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" closable class="mb-4" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <v-row class="mb-2">
      <v-col v-for="(m, cle) in MENAGE" :key="cle" cols="6" md="3">
        <v-card class="classroom-card pa-4" :class="{ 'carte-active': filtre === cle }" @click="filtre = filtre === cle ? 'TOUS' : (cle as string)">
          <div class="kpi-label" :style="{ color: m.couleur }"><v-icon :icon="m.icone" size="16" /> {{ m.libelle }}</div>
          <div class="kpi-value">{{ compte(cle as string) }}</div>
        </v-card>
      </v-col>
    </v-row>

    <v-card v-if="taches.length" class="classroom-card mb-4">
      <v-card-title class="text-subtitle-1 font-weight-bold">Tâches</v-card-title>
      <v-list density="comfortable">
        <v-list-item v-for="t in taches" :key="t.id">
          <template #prepend>
            <v-avatar size="40" :color="t.priorite === 'HAUTE' ? 'error' : 'primary'" variant="tonal"><strong>{{ t.uniteNumero }}</strong></v-avatar>
          </template>
          <v-list-item-title>{{ t.type === 'DEPART' ? 'Après départ' : t.type === 'RECOUCHE' ? 'Recouche' : t.type === 'INSPECTION' ? 'Inspection' : 'Tâche' }}
            <v-chip v-if="t.priorite === 'HAUTE'" size="x-small" color="error" variant="tonal" class="ml-1">arrivée aujourd'hui</v-chip></v-list-item-title>
          <v-list-item-subtitle>{{ t.etablissementNom }} · {{ t.notes }} · {{ dateHeure(t.creeLe) }}<template v-if="t.assigneNom"> · {{ t.assigneNom }}</template></v-list-item-subtitle>
          <template #append>
            <template v-if="ecrit">
              <v-btn v-if="t.statut === 'A_FAIRE'" size="small" variant="tonal" @click="commencer(t)">Commencer</v-btn>
              <v-btn v-if="t.statut !== 'TERMINEE'" size="small" color="primary" variant="flat" class="ml-2" @click="terminer(t)">Terminé</v-btn>
            </template>
            <v-chip v-if="t.statut === 'TERMINEE'" size="small" color="success" variant="tonal">Terminée</v-chip>
          </template>
        </v-list-item>
      </v-list>
    </v-card>

    <div class="grille-unites">
      <v-card v-for="u in etatsFiltres" :key="u.id" class="classroom-card pa-4 unite" :style="{ '--c': u.horsService ? '#64748b' : MENAGE[u.etatMenage]?.couleur }">
        <div class="d-flex align-center">
          <div class="unite__numero">{{ u.numero }}</div>
          <v-spacer />
          <span class="pastille" :style="{ '--c': MENAGE[u.etatMenage]?.couleur }">{{ MENAGE[u.etatMenage]?.libelle }}</span>
        </div>
        <div class="text-caption text-medium-emphasis">{{ u.typeNom }}<template v-if="u.etage"> · étage {{ u.etage }}</template> · {{ u.etablissementNom }}</div>
        <div class="text-body-2 mt-2">
          <template v-if="u.horsService"><v-icon icon="mdi-wrench-outline" size="16" /> Hors service{{ u.motifHorsService ? ' : ' + u.motifHorsService : '' }}</template>
          <template v-else-if="u.occupee"><v-icon icon="mdi-account" size="16" /> {{ u.clientActuel }} · départ {{ dateHeure(u.departPrevu) }}</template>
          <template v-else-if="u.arriveePrevue"><v-icon icon="mdi-login-variant" size="16" /> Arrivée {{ dateHeure(u.arriveePrevue) }}</template>
          <template v-else><v-icon icon="mdi-door-closed" size="16" /> Libre</template>
        </div>
        <div v-if="ecrit && !u.horsService" class="d-flex flex-wrap ga-1 mt-3">
          <v-btn v-for="(m, cle) in MENAGE" v-show="cle !== u.etatMenage" :key="cle" size="x-small" variant="tonal" :prepend-icon="m.icone" @click="changer(u, cle as string)">
            {{ m.libelle }}
          </v-btn>
        </div>
      </v-card>
    </div>
  </div>
</template>

<style scoped>
.grille-unites { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 12px; }
.unite { border-left: 4px solid var(--c) !important; }
.unite__numero { font-size: 1.4rem; font-weight: 800; }
.carte-active { outline: 2px solid var(--color-primary); }
</style>
