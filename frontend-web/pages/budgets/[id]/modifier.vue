<script setup lang="ts">
definePageMeta({ module: 'BUDGET', roles: ['ADMIN', 'DFIN'] })
const route = useRoute()
const id = computed(() => Number(route.params.id))
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1 class="page-title">Modifier le budget</h1>
        <p class="page-sub">Seul un budget en brouillon se modifie ; une fois approuvé, il se révise</p>
      </div>
      <v-btn variant="text" prepend-icon="mdi-arrow-left" :to="`/budgets/${id}`">Retour au budget</v-btn>
    </div>
    <BudgetEditeurBudget :budget-id="id" @enregistre="(b: number) => navigateTo(`/budgets/${b}`)" @annule="navigateTo(`/budgets/${id}`)" />
  </div>
</template>
