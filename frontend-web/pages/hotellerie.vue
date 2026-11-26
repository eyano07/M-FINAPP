<script setup lang="ts">
/**
 * Hôtellerie (V152) : ouvre l'application Hôtellerie (PMS) dans un nouvel onglet, déjà connecté, par un code à usage
 * unique émis par M-FINAPP (POST /hotellerie/sso). Page d'accueil des rôles de l'hébergement.
 */
definePageMeta({ module: 'HOTELLERIE' })
useHead({ title: 'Hôtellerie' })

const api = useApi()
const auth = useAuthStore()
const lien = ref<string | null>(null)
const chargement = ref(true)
const ouverture = ref(false)
const erreur = ref('')

onMounted(async () => {
  try {
    lien.value = (await api<{ url: string | null }>('/hotellerie/lien')).url
  } catch (e) {
    erreur.value = messageErreurApi(e, "Impossible de lire l'adresse de l'hôtellerie.")
  } finally {
    chargement.value = false
  }
})

async function ouvrir() {
  erreur.value = ''
  ouverture.value = true
  // Onglet ouvert tout de suite (dans le clic) : ouvert après l'appel réseau, le navigateur le bloquerait.
  const onglet = window.open('', '_blank')
  try {
    const r = await api<{ url: string }>('/hotellerie/sso', { method: 'POST' })
    if (onglet) onglet.location.href = r.url
    else window.location.href = r.url
  } catch (e) {
    onglet?.close()
    erreur.value = messageErreurApi(e, "L'hôtellerie n'a pas pu être ouverte.")
  } finally {
    ouverture.value = false
  }
}
</script>

<template>
  <div class="hotellerie">
    <v-card class="classroom-card hotellerie__carte pa-8">
      <div class="hotellerie__icone"><v-icon icon="mdi-bed-outline" size="40" /></div>
      <h1 class="page-title mt-4 mb-1">M-FINAPP Hôtellerie</h1>
      <p class="page-sub mb-6">Réservations, calendrier, arrivées et départs, folios, ménage — hôtel, motel et appartements.</p>
      <v-alert v-if="erreur" type="error" variant="tonal" class="mb-4 text-left">{{ erreur }}</v-alert>
      <v-alert v-else-if="!chargement && !lien" type="info" variant="tonal" class="mb-4 text-left">
        L'hôtellerie n'est pas encore reliée à M-FINAPP.
        <template v-if="auth.hasAnyRole(['ADMIN'])">
          Saisissez son adresse et générez la clé dans <NuxtLink to="/admin/hotellerie">Administration › Hôtellerie</NuxtLink>.
        </template>
        <template v-else>Demandez à l'administrateur de la relier.</template>
      </v-alert>
      <v-btn color="primary" size="large" rounded="lg" prepend-icon="mdi-open-in-new" :disabled="!lien" :loading="ouverture || chargement"
        @click="ouvrir">
        Ouvrir l'hôtellerie
      </v-btn>
      <p class="text-caption text-medium-emphasis mt-4">
        Vous y êtes connecté avec votre compte M-FINAPP : vos droits y sont ceux de votre rôle.
      </p>
    </v-card>
  </div>
</template>

<style scoped>
.hotellerie { display: flex; justify-content: center; padding-top: 4vh; }
.hotellerie__carte { max-width: 560px; width: 100%; text-align: center; }
.hotellerie__icone {
  width: 76px; height: 76px; margin: 0 auto; border-radius: 22px; display: grid; place-items: center;
  color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 12%, transparent);
}
</style>
