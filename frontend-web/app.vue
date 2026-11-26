<template>
  <div>
    <NuxtLayout>
      <NuxtPage />
    </NuxtLayout>
    <!-- Boîte de confirmation et de saisie, commune à toutes les pages (voir composables/useDialogues.ts). -->
    <CommunBoiteDialogue />
  </div>
</template>
