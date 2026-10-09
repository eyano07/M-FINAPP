<script setup lang="ts">
definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN'] })
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Nouveau budget</h1>
        <p class="page-sub">Budget annuel ventilé par mois : saisissez-le ligne par ligne ou faites-le proposer par l’IA à partir du réalisé</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" to="/budgets">Retour aux budgets</v-btn>
    </div>
    <BudgetEditeurBudget @enregistre="(id: number) => navigateTo(`/budgets/${id}`)" @annule="navigateTo('/budgets')" />
  </div>
</template>
