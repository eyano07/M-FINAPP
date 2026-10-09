<script setup lang="ts">
/**
 * Contrôle budgétaire d'une note de frais : chaque ligne est rapprochée du budget en exécution de
 * l'exercice (budgétée, dépassement du disponible à fin de mois, hors budget, aucun budget). Deux modes :
 * - saisie (`lignes` fourni) : contrôle des lignes en cours de saisie, recalculé à chaque modification ;
 * - note enregistrée (`noteId` seul) : contrôle à jour de la note.
 * Émet le résultat pour que l'écran exige la justification quand elle est requise.
 */
interface LigneSaisie {
  compteImputation?: string | null
  montant?: number | null
  quantiteMarchandise?: number | null
  achatMarchandise?: boolean
}

const props = defineProps<{
  noteId?: number | null
  lignes?: LigneSaisie[]
  devise?: string
  sens?: string | null
  rafraichir?: number
}>()
const emit = defineEmits<{ (e: 'resultat', r: any | null): void }>()

const api = useApi()
const resultat = ref<any | null>(null)
const chargement = ref(false)
const erreur = ref('')
let minuterie: ReturnType<typeof setTimeout> | null = null

const actif = computed(() => props.sens !== 'ENCAISSEMENT')

async function controler() {
  if (!actif.value) {
    resultat.value = null
    emit('resultat', null)
    return
  }
  chargement.value = true
  erreur.value = ''
  try {
    if (props.lignes) {
      const lignes = props.lignes
        .filter(l => Number(l.montant) > 0)
        .map(l => ({
          compteNumero: l.compteImputation || null,
          montant: Number(l.montant),
          quantite: l.achatMarchandise ? Number(l.quantiteMarchandise) || null : null,
          achatMarchandise: !!l.achatMarchandise,
        }))
      if (!lignes.length) {
        resultat.value = null
        emit('resultat', null)
        return
      }
      resultat.value = await api<any>('/notes-frais/controle-budgetaire', {
        method: 'POST', body: { devise: props.devise || 'CDF', lignes, noteId: props.noteId || null },
      })
    } else if (props.noteId) {
      resultat.value = await api<any>(`/notes-frais/${props.noteId}/controle-budgetaire`)
    }
    emit('resultat', resultat.value)
  } catch (e) {
    erreur.value = messageErreurApi(e, 'Contrôle budgétaire indisponible.')
    resultat.value = null
    emit('resultat', null)
  } finally {
    chargement.value = false
  }
}

function planifier() {
  if (minuterie) clearTimeout(minuterie)
  minuterie = setTimeout(controler, 450)
}

watch(() => [props.lignes, props.devise, props.sens], planifier, { deep: true })
watch(() => [props.noteId, props.rafraichir], planifier)
onMounted(controler)
onBeforeUnmount(() => { if (minuterie) clearTimeout(minuterie) })

const lignesAffichees = computed(() => (resultat.value?.lignes || []).filter((l: any) => l.statut !== 'NON_CONCERNE' || (resultat.value?.lignes || []).length === 1))
</script>

<template>
  <div v-if="actif && resultat?.budgetActif !== false && (resultat || chargement || erreur)" class="cb" :class="`cb--${(resultat?.statut || 'X').toLowerCase()}`">
    <div class="cb-tete">
      <v-icon icon="mdi-scale-balance" size="18" />
      <span class="cb-titre">Contrôle budgétaire</span>
      <v-progress-circular v-if="chargement" indeterminate size="14" width="2" class="ml-1" />
      <v-chip v-if="resultat" size="small" :color="statutControle(resultat.statut).color" variant="flat" :prepend-icon="statutControle(resultat.statut).icon">
        {{ statutControle(resultat.statut).label }}
      </v-chip>
      <span v-if="resultat?.budgetReference" class="cb-budget">
        Budget {{ resultat.budgetReference }} — {{ resultat.budgetIntitule }}
      </span>
      <span v-else-if="resultat && resultat.statut !== 'NON_CONCERNE'" class="cb-budget">Aucun budget en exécution pour {{ resultat.exercice }}</span>
    </div>
    <p v-if="erreur" class="cb-erreur">{{ erreur }}</p>
    <div v-for="l in lignesAffichees" :key="l.index" class="cb-ligne">
      <v-icon :icon="statutControle(l.statut).icon" :color="statutControle(l.statut).color" size="16" />
      <div>
        <span class="cb-ligne__titre">Ligne {{ l.index + 1 }} · {{ l.compteNumero }}<span v-if="l.compteLibelle"> {{ l.compteLibelle }}</span></span>
        <span class="cb-ligne__msg">{{ l.message }}</span>
        <span v-if="l.prevuAnnuel !== null && l.prevuAnnuel !== undefined" class="cb-ligne__detail">
          Ligne {{ l.ligneBudgetCompte }} : prévu à fin de mois {{ fmtMontant(l.prevuCumule) }}, réalisé {{ fmtMontant(l.realiseCumule) }},
          engagé {{ fmtMontant(l.engage) }} — prévu annuel {{ fmtMontant(l.prevuAnnuel) }}, disponible sur l’année {{ fmtMontant(l.disponibleAnnuel) }} USD
        </span>
      </div>
    </div>
    <p v-if="resultat?.justificationRequise" class="cb-exige">
      <v-icon icon="mdi-information-outline" size="15" />
      Toute dépense doit être couverte par le budget : cette note doit préciser pourquoi elle ne l’est pas (justification budgétaire).
    </p>
    <p v-for="(a, i) in resultat?.avertissements || []" :key="i" class="cb-avert">{{ a }}</p>
  </div>
</template>

<style scoped>
.cb { border: 1px solid #e5e7eb; border-left: 4px solid #9ca3af; border-radius: 10px; padding: 10px 12px; background: #fafafa; margin: 10px 0; }
.cb--conforme { border-left-color: #16a34a; background: #f0fdf4; }
.cb--depassement { border-left-color: #f59e0b; background: #fffbeb; }
.cb--hors_budget, .cb--sans_budget { border-left-color: #dc2626; background: #fef2f2; }
.cb-tete { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cb-titre { font-weight: 700; font-size: 0.86rem; }
.cb-budget { font-size: 0.76rem; color: #4b5563; }
.cb-ligne { display: flex; gap: 8px; margin-top: 8px; align-items: flex-start; }
.cb-ligne__titre { display: block; font-size: 0.8rem; font-weight: 600; }
.cb-ligne__msg { display: block; font-size: 0.8rem; color: #374151; }
.cb-ligne__detail { display: block; font-size: 0.72rem; color: #6b7280; }
.cb-exige { font-size: 0.8rem; color: #991b1b; margin: 8px 0 0; font-weight: 600; }
.cb-avert { font-size: 0.74rem; color: #92400e; margin: 6px 0 0; }
.cb-erreur { font-size: 0.78rem; color: #b91c1c; margin: 6px 0 0; }
</style>
