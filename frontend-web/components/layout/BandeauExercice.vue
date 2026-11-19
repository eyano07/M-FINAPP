<script setup lang="ts">
/**
 * Bandeau de l'exercice consulté (19/11/2026) : affiché quand l'utilisateur a choisi, dans la barre du haut, un autre
 * exercice que l'exercice en cours. Exercice clôturé : lecture seule, impression permise selon les droits.
 */
import { useExerciceStore } from '~/stores/exercice'

const exercice = useExerciceStore()
const dateCloture = computed(() => {
  const d = exercice.choisi?.dateCloture
  return d ? new Date(d + 'T00:00:00').toLocaleDateString('fr-FR') : null
})
</script>

<template>
  <div v-if="exercice.enConsultation" class="bandeau-exercice"
    :class="exercice.lectureSeule ? 'bandeau-exercice--clos' : 'bandeau-exercice--ouvert'" role="status">
    <v-icon :icon="exercice.lectureSeule ? 'mdi-lock-outline' : 'mdi-calendar-clock'" size="20" class="bandeau-exercice__icone" />
    <div class="bandeau-exercice__texte">
      <strong>Exercice {{ exercice.anneeChoisie }}</strong>
      <template v-if="exercice.lectureSeule">
        <span> — clôturé<template v-if="dateCloture"> au {{ dateCloture }}</template> : consultation en lecture seule.</span>
        <span class="bandeau-exercice__detail">
          Vous voyez les données de cet exercice et pouvez imprimer ou exporter les documents permis par vos droits.
          Aucune saisie n'est possible.
        </span>
      </template>
      <template v-else-if="exercice.choisi?.statut === 'A_CLOTURER'">
        <span> — pas encore clôturé.</span>
        <span class="bandeau-exercice__detail">Les écritures de fin d'exercice restent possibles jusqu'au verrou de période.</span>
      </template>
      <template v-else>
        <span> — exercice à venir.</span>
      </template>
    </div>
    <v-btn size="small" variant="tonal" class="bandeau-exercice__retour" prepend-icon="mdi-arrow-u-left-top"
      @click="exercice.choisir(exercice.anneeCourante)">
      Revenir à {{ exercice.anneeCourante }}
    </v-btn>
  </div>
</template>

<style scoped>
.bandeau-exercice {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 0.86rem;
  line-height: 1.45;
}
.bandeau-exercice--clos { background: #f5f3ff; border: 1px solid #ddd6fe; color: #4c1d95; }
.bandeau-exercice--ouvert { background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; }
.bandeau-exercice__icone { flex-shrink: 0; }
.bandeau-exercice__texte { min-width: 0; flex: 1; }
.bandeau-exercice__detail { display: block; opacity: 0.85; }
.bandeau-exercice__retour { flex-shrink: 0; text-transform: none; letter-spacing: 0; }
@media (max-width: 599px) {
  .bandeau-exercice { flex-wrap: wrap; }
  .bandeau-exercice__retour { margin-left: 30px; }
}
@media print { .bandeau-exercice { display: none; } }
</style>

<style>
html[data-theme='dark'] .bandeau-exercice--clos { background: color-mix(in srgb, #b9a7f5 11%, var(--surface)) !important; color: #c4b5fd !important; border-color: color-mix(in srgb, #b9a7f5 28%, transparent) !important; }
html[data-theme='dark'] .bandeau-exercice--ouvert { background: color-mix(in srgb, #f0a77a 11%, var(--surface)) !important; color: #f0a77a !important; border-color: color-mix(in srgb, #f0a77a 28%, transparent) !important; }
</style>
