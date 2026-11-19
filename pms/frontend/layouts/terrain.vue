<script setup lang="ts">
import { useSessionStore } from '~/stores/session'
import { useEtablissementStore } from '~/stores/etablissement'

/** Cadre « terrain » (téléphone, tablette) : barre réduite, gros boutons, aucune navigation superflue. */
const session = useSessionStore()
const etablissements = useEtablissementStore()
onMounted(() => { if (!etablissements.charge) etablissements.charger() })
const complet = computed(() => session.a('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE'))
</script>

<template>
  <v-app>
    <header class="terrain-barre">
      <span class="terrain-barre__logo"><LogoMfinapp /></span>
      <div class="terrain-barre__texte">
        <b>{{ etablissements.nom }}</b>
        <small>{{ session.nomComplet }}</small>
      </div>
      <v-btn v-if="complet" icon="mdi-view-dashboard-outline" variant="text" to="/" aria-label="Vue complète" />
      <v-btn icon="mdi-logout" variant="text" aria-label="Se déconnecter" @click="session.fermer(); navigateTo('/login')" />
    </header>
    <v-main>
      <div class="terrain-contenu"><slot /></div>
    </v-main>
  </v-app>
</template>

<style scoped>
.terrain-barre {
  position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 12px; padding: 12px 10px 12px 16px; color: #fff;
  background: var(--d-grad-hero); box-shadow: 0 10px 30px -20px rgba(0, 0, 0, 0.6);
}
.terrain-barre__logo { width: 38px; height: 38px; border-radius: 12px; padding: 8px; display: grid; place-items: center; color: #fff; background: rgba(255, 255, 255, 0.14); }
.terrain-barre__texte { flex: 1; min-width: 0; }
.terrain-barre__texte b { display: block; font-size: 0.95rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.terrain-barre__texte small { display: block; font-size: 0.75rem; opacity: 0.8; }
.terrain-barre .v-btn { color: #fff; }
.terrain-contenu { max-width: 720px; margin: 0 auto; padding: 16px 14px 96px; }
</style>
