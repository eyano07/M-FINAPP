<script setup lang="ts">
/**
 * Bandeau du mode du serveur (V132) : copie en ligne d'un serveur local (ce qu'on peut y faire, fraîcheur des données)
 * ou ancien serveur local (lecture seule). Rien sur un serveur principal.
 */
import { useInstanceStore } from '~/stores/instance'

const instance = useInstanceStore()

const aJourAu = computed(() => {
  if (!instance.derniereSynchro) return null
  const d = new Date(instance.derniereSynchro)
  return d.toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
})
</script>

<template>
  <div v-if="instance.estCopie" class="bandeau-instance bandeau-instance--copie" role="status">
    <v-icon icon="mdi-cloud-sync-outline" size="20" class="bandeau-instance__icone" />
    <div class="bandeau-instance__texte">
      <strong>Copie en ligne du serveur de l'entreprise</strong>
      <span v-if="aJourAu"> — données à jour au {{ aJourAu }}.</span>
      <span v-else> — en attente de la première synchronisation.</span>
      <span class="bandeau-instance__detail">
        Les saisies se font sur le réseau de l'entreprise. Ici : consultation, création et validation des notes de frais
        (appliquées au serveur principal à la synchronisation suivante).
      </span>
    </div>
  </div>
  <div v-else-if="instance.estArrete" class="bandeau-instance bandeau-instance--arrete" role="status">
    <v-icon icon="mdi-cloud-off-outline" size="20" class="bandeau-instance__icone" />
    <div class="bandeau-instance__texte">
      <strong>Ce serveur n'est plus le serveur principal</strong>
      <span class="bandeau-instance__detail">Consultation seulement : utilisez le serveur en ligne pour toute saisie.</span>
    </div>
  </div>
</template>

<style scoped>
.bandeau-instance {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 0.86rem;
  line-height: 1.45;
}
.bandeau-instance--copie { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e3a8a; }
.bandeau-instance--arrete { background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; }
.bandeau-instance__icone { flex-shrink: 0; margin-top: 1px; }
.bandeau-instance__texte { min-width: 0; }
.bandeau-instance__detail { display: block; opacity: 0.85; }
@media print { .bandeau-instance { display: none; } }
</style>
