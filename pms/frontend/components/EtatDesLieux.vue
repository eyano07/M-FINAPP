<script setup lang="ts">
import { ETATS_ELEMENT, dateHeure, usd } from '~/utils/libelles'

/**
 * États des lieux d'un séjour : entrée (depuis l'inventaire type du logement) et sortie (depuis l'entrée). Chaque élément
 * a un état ; à la sortie, un dégât ou un manque porte une retenue proposée sur la caution. Photos à l'appui.
 */
const props = defineProps<{ reservationId: number, peut: boolean }>()
const emit = defineEmits<{ (e: 'retenue', montant: number): void }>()

interface Ligne { id?: number | null, piece: string, element: string, quantite: number, etat: string, commentaire: string | null, coutRetenue: number }
interface Etat { id: number, type: 'ENTREE' | 'SORTIE', dateEtat: string, observations: string | null, valide: boolean, valideLe: string | null,
  lignes: Ligne[], retenue: number, photos: { id: number, legende: string | null }[] }

const api = useApi()
const etats = ref<Etat[]>([])
const courant = ref<'ENTREE' | 'SORTIE'>('ENTREE')
const brouillon = ref<{ observations: string, lignes: Ligne[] } | null>(null)
const erreur = ref('')
const enCours = ref(false)

async function charger() {
  try {
    etats.value = await api<Etat[]>(`/reservations/${props.reservationId}/etats-des-lieux`)
    const e = etat.value
    brouillon.value = e ? { observations: e.observations ?? '', lignes: e.lignes.map(l => ({ ...l, coutRetenue: Number(l.coutRetenue) })) } : null
    const sortie = etats.value.find(x => x.type === 'SORTIE')
    if (sortie) emit('retenue', Number(sortie.retenue))
  } catch (e) { erreur.value = messageErreur(e) }
}
onMounted(charger)
const etat = computed(() => etats.value.find(e => e.type === courant.value))
watch(courant, () => {
  const e = etat.value
  brouillon.value = e ? { observations: e.observations ?? '', lignes: e.lignes.map(l => ({ ...l, coutRetenue: Number(l.coutRetenue) })) } : null
})
const pieces = computed(() => [...new Set((brouillon.value?.lignes ?? []).map(l => l.piece))])
const retenue = computed(() => (brouillon.value?.lignes ?? []).reduce((s, l) => s + (Number(l.coutRetenue) || 0), 0))

async function ouvrir() {
  enCours.value = true
  try {
    await api(`/reservations/${props.reservationId}/etats-des-lieux`, { method: 'POST', body: { type: courant.value } })
    await charger()
  } catch (e) { erreur.value = messageErreur(e) } finally { enCours.value = false }
}
function ajouter(piece = '') {
  brouillon.value?.lignes.push({ piece: piece || pieces.value[0] || 'Séjour', element: '', quantite: 1, etat: 'BON', commentaire: null, coutRetenue: 0 })
}
async function enregistrer(valider = false) {
  if (!etat.value || !brouillon.value) return
  enCours.value = true
  erreur.value = ''
  try {
    await api(`/etats-des-lieux/${etat.value.id}`, { method: 'PUT', body: { observations: brouillon.value.observations,
      lignes: brouillon.value.lignes.filter(l => l.element.trim()) } })
    if (valider) await api(`/etats-des-lieux/${etat.value.id}/validation`, { method: 'POST' })
    await charger()
  } catch (e) { erreur.value = messageErreur(e) } finally { enCours.value = false }
}
</script>

<template>
  <div class="edl">
    <div class="edl__onglets">
      <button v-for="t in (['ENTREE', 'SORTIE'] as const)" :key="t" type="button" class="edl__onglet" :class="{ 'edl__onglet--actif': courant === t }" @click="courant = t">
        <v-icon :icon="t === 'ENTREE' ? 'mdi-door-open' : 'mdi-door-closed'" size="16" />{{ t === 'ENTREE' ? 'Entrée' : 'Sortie' }}
        <v-icon v-if="etats.find(e => e.type === t)?.valide" icon="mdi-check-circle" size="14" color="success" />
      </button>
    </div>
    <v-alert v-if="erreur" type="error" variant="tonal" density="compact" closable class="mb-3" @click:close="erreur = ''">{{ erreur }}</v-alert>

    <div v-if="!etat" class="d-vide py-6">
      <div class="d-vide__icone"><v-icon icon="mdi-clipboard-list-outline" size="26" /></div>
      <div class="d-vide__titre">Pas encore d’état des lieux {{ courant === 'ENTREE' ? 'd’entrée' : 'de sortie' }}</div>
      <div class="d-vide__texte">{{ courant === 'ENTREE' ? 'Il reprend l’inventaire type du logement.' : 'Il reprend l’état des lieux d’entrée, élément par élément.' }}</div>
      <v-btn v-if="peut" color="primary" variant="flat" class="mt-3" :loading="enCours" prepend-icon="mdi-plus" @click="ouvrir">Commencer</v-btn>
    </div>

    <template v-else-if="brouillon">
      <div class="edl__entete">
        <span>{{ etat.valide ? `Validé le ${dateHeure(etat.valideLe)}` : 'Brouillon' }}</span>
        <span v-if="courant === 'SORTIE' && retenue" class="edl__retenue">Retenue proposée : {{ usd(retenue) }}</span>
      </div>
      <div v-for="p in pieces" :key="p" class="edl__piece">
        <div class="edl__piece-nom">{{ p }}</div>
        <div v-for="(l, i) in brouillon.lignes.filter(x => x.piece === p)" :key="i" class="edl__ligne">
          <v-text-field v-model="l.element" density="compact" hide-details :readonly="etat.valide || !peut" placeholder="Élément" class="edl__element" />
          <v-text-field v-model.number="l.quantite" type="number" min="0" density="compact" hide-details :readonly="etat.valide || !peut" class="edl__qte" />
          <div class="edl__etats">
            <button v-for="(e, cle) in ETATS_ELEMENT" :key="cle" type="button" class="edl__etat" :class="{ 'edl__etat--actif': l.etat === cle }" :style="{ '--c': e.couleur }"
              :disabled="etat.valide || !peut" :title="e.libelle" @click="l.etat = cle as string">{{ e.libelle }}</button>
          </div>
          <v-text-field v-if="courant === 'SORTIE' && ['ABIME', 'MANQUANT'].includes(l.etat)" v-model.number="l.coutRetenue" type="number" min="0" density="compact"
            hide-details prefix="$" :readonly="etat.valide || !peut" class="edl__cout" placeholder="Retenue" />
        </div>
        <v-btn v-if="!etat.valide && peut" size="x-small" variant="text" prepend-icon="mdi-plus" @click="ajouter(p)">Élément</v-btn>
      </div>
      <div v-if="!etat.valide && peut" class="d-flex ga-2 mt-2">
        <v-btn size="small" variant="tonal" prepend-icon="mdi-plus" @click="ajouter()">Ligne</v-btn>
      </div>
      <v-textarea v-model="brouillon.observations" label="Observations" rows="2" auto-grow class="mt-4" :readonly="etat.valide || !peut" />
      <div class="edl__libelle">Photos</div>
      <GaleriePhotos :photos="etat.photos" :envoi="`/etats-des-lieux/${etat.id}/photos`" :peut-ajouter="peut && !etat.valide" @change="charger" />
      <div v-if="!etat.valide && peut" class="d-flex ga-2 justify-end mt-4">
        <v-btn variant="tonal" :loading="enCours" @click="enregistrer(false)">Enregistrer</v-btn>
        <v-btn color="primary" variant="flat" :loading="enCours" prepend-icon="mdi-check" @click="enregistrer(true)">Valider</v-btn>
      </div>
    </template>
  </div>
</template>

<style scoped>
.edl__onglets { display: inline-flex; gap: 4px; padding: 4px; border-radius: 12px; background: var(--d-surface-3); margin-bottom: 14px; }
.edl__onglet { display: inline-flex; align-items: center; gap: 6px; height: 34px; padding: 0 12px; border: 0; border-radius: 9px; cursor: pointer; font: inherit;
  font-size: 0.84rem; font-weight: 650; color: var(--d-ink-soft); background: transparent; }
.edl__onglet--actif { color: var(--d-ink); background: var(--d-surface); box-shadow: var(--d-shadow-1); }
.edl__entete { display: flex; justify-content: space-between; font-size: 0.8rem; color: var(--d-ink-faint); margin-bottom: 10px; }
.edl__retenue { font-weight: 750; color: var(--menage-sale); }
.edl__piece { padding: 12px; border-radius: 14px; background: var(--d-surface-2); border: 1px solid var(--d-line); margin-bottom: 10px; }
.edl__piece-nom { font-size: 0.72rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--d-ink-faint); margin-bottom: 8px; }
.edl__ligne { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--d-line-strong); }
.edl__element { flex: 1 1 180px; }
.edl__qte { flex: 0 0 72px; }
.edl__cout { flex: 0 0 110px; }
.edl__etats { display: flex; gap: 3px; flex-wrap: wrap; }
.edl__etat { height: 30px; padding: 0 9px; border-radius: 8px; border: 1px solid var(--d-line); cursor: pointer; font: inherit; font-size: 0.72rem; font-weight: 650;
  color: var(--d-ink-soft); background: var(--d-surface); }
.edl__etat--actif { color: #fff; background: var(--c); border-color: var(--c); }
.edl__etat:disabled { cursor: default; }
.edl__libelle { font-size: 0.7rem; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--d-ink-faint); margin: 4px 0 8px; }
</style>
