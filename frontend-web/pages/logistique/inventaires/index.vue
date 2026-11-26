<script setup lang="ts">
// Inventaire physique annuel des stocks (audit SYSCOHADA du 10/11/2026, C-22).
definePageMeta({ module: 'LOGISTIQUE', roles: ['LOGISTIQUE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'ADMIN'] })
</script>

<template>
  <div>
    <div class="page-head no-print">
      <div>
        <h1 class="page-title">Inventaire des entrepôts</h1>
        <p class="page-sub">
          Comptage par entrepôt, validation par la comptabilité (une autre personne que celle qui l'a ouvert) :
          chaque écart est passé en stock et en comptabilité au coût moyen.
        </p>
      </div>
    </div>
    <CommunInventairePhysique type="STOCK" />
  </div>
</template>
