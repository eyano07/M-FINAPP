<script setup lang="ts">
// Inventaire physique annuel des immobilisations (audit SYSCOHADA du 10/11/2026, C-22).
definePageMeta({ module: 'PATRIMOINE' })
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Inventaires des immobilisations</h1>
        <p class="page-sub">
          Chaque bien en service est constaté présent ou absent, avec son état ; le procès-verbal validé par la
          comptabilité est exigé à la clôture de l'exercice.
        </p>
      </div>
    </div>
    <CommunInventairePhysique type="IMMOBILISATIONS" />
  </div>
</template>
