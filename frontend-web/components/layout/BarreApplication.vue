<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { usePermissionsStore } from '~/stores/permissions'
import { estApplicationMobile } from '~/utils/applicationMobile'

/**
 * Application Android (17/11/2026) : barre flottante en bas de l'écran, à portée du pouce, pour ceux qui y saisissent.
 * - Caissier : Accueil, Caisse, « + » (nouvelle vente, note de décaissement ou d'encaissement), Ventes, Notes.
 * - Comptable : Accueil, Notes, « + » (note de décaissement), Pièces, Balance (consultation).
 * Chaque onglet n'apparaît que si son module est ouvert au compte (même règle que le menu latéral).
 */
const auth = useAuthStore()
const permissions = usePermissionsStore()
const route = useRoute()
const router = useRouter()

const caissier = computed(() => auth.hasRole('CAISSIER'))
const comptable = computed(() => auth.hasRole('COMPTABLE'))
const visible = computed(() => estApplicationMobile() && auth.isAuthenticated && (caissier.value || comptable.value))

interface Onglet { titre: string; icone: string; to: string }

const onglets = computed<Onglet[]>(() => {
  const liste: Onglet[] = [{ titre: 'Accueil', icone: 'mdi-home-variant-outline', to: '/dashboard' }]
  if (caissier.value) {
    if (permissions.peutVoir('CAISSE')) liste.push({ titre: 'Caisse', icone: 'mdi-cash-register', to: '/caisse' })
    if (permissions.peutVoir('VENTES')) liste.push({ titre: 'Ventes', icone: 'mdi-shopping-outline', to: '/ventes' })
    liste.push({ titre: 'Notes', icone: 'mdi-receipt-text-outline', to: '/notes-frais' })
    return liste
  }
  liste.push({ titre: 'Notes', icone: 'mdi-receipt-text-outline', to: '/notes-frais' })
  if (permissions.peutVoir('COMPTABILITE')) {
    liste.push({ titre: 'Pièces', icone: 'mdi-file-document-outline', to: '/comptabilite/pieces' })
    liste.push({ titre: 'Balance', icone: 'mdi-scale-balance', to: '/comptabilite/balance-verification' })
  }
  return liste
})
// Le « + » au milieu : autant d'onglets de chaque côté (le premier côté prend l'onglet en trop).
const gauche = computed(() => onglets.value.slice(0, Math.ceil(onglets.value.length / 2)))
const droite = computed(() => onglets.value.slice(Math.ceil(onglets.value.length / 2)))

function actif(to: string) {
  return route.path === to || route.path.startsWith(to + '/')
}

interface Action { titre: string; detail: string; icone: string; teinte: string; aller: () => void }

const feuille = ref(false)
const actions = computed<Action[]>(() => {
  const liste: Action[] = []
  if (caissier.value && permissions.peutModifier('VENTES')) {
    liste.push({
      titre: 'Nouvelle vente', detail: 'Facturer un client, encaisser', icone: 'mdi-cart-plus', teinte: 'vente',
      aller: () => router.push('/ventes/nouvelle'),
    })
  }
  liste.push({
    titre: 'Note de décaissement', detail: 'Sortie d\'argent à faire valider', icone: 'mdi-arrow-up-circle-outline', teinte: 'sortie',
    aller: () => router.push({ path: '/notes-frais', query: { nouvelle: 'DECAISSEMENT' } }),
  })
  if (caissier.value) liste.push({
    titre: 'Note d\'encaissement', detail: 'Entrée d\'argent en trésorerie', icone: 'mdi-arrow-down-circle-outline', teinte: 'entree',
    aller: () => router.push({ path: '/notes-frais', query: { nouvelle: 'ENCAISSEMENT' } }),
  })
  return liste
})

function choisir(a: Action) {
  feuille.value = false
  a.aller()
}
</script>

<template>
  <template v-if="visible">
    <nav class="barre-app" aria-label="Navigation principale">
      <NuxtLink v-for="o in gauche" :key="o.to" :to="o.to" class="barre-app__onglet" :class="{ 'est-actif': actif(o.to) }">
        <span class="barre-app__pastille"><v-icon :icon="o.icone" size="22" /></span>
        <span class="barre-app__titre">{{ o.titre }}</span>
      </NuxtLink>

      <button type="button" class="barre-app__plus" :class="{ 'est-ouvert': feuille }" aria-label="Nouvelle opération" @click="feuille = !feuille">
        <v-icon icon="mdi-plus" size="28" />
      </button>

      <NuxtLink v-for="o in droite" :key="o.to" :to="o.to" class="barre-app__onglet" :class="{ 'est-actif': actif(o.to) }">
        <span class="barre-app__pastille"><v-icon :icon="o.icone" size="22" /></span>
        <span class="barre-app__titre">{{ o.titre }}</span>
      </NuxtLink>
    </nav>

    <v-bottom-sheet v-model="feuille" content-class="feuille-app">
      <div class="feuille-app__corps">
        <div class="feuille-app__poignee" />
        <p class="feuille-app__entete">Nouvelle opération</p>
        <button v-for="a in actions" :key="a.titre" type="button" class="feuille-app__action" @click="choisir(a)">
          <span class="feuille-app__icone" :class="'teinte-' + a.teinte"><v-icon :icon="a.icone" size="24" /></span>
          <span class="feuille-app__textes">
            <span class="feuille-app__titre">{{ a.titre }}</span>
            <span class="feuille-app__detail">{{ a.detail }}</span>
          </span>
          <v-icon icon="mdi-chevron-right" size="20" class="feuille-app__fleche" />
        </button>
      </div>
    </v-bottom-sheet>
  </template>
</template>

<style scoped>
.barre-app {
  position: fixed;
  left: 12px;
  right: 12px;
  bottom: calc(12px + env(safe-area-inset-bottom, 0px));
  /* Sous le menu latéral et son voile (couches Vuetify ≥ 1000) : le menu ouvert passe par-dessus la barre. */
  z-index: 900;
  height: 68px;
  display: flex;
  align-items: center;
  justify-content: space-around;
  padding: 0 6px;
  border-radius: 24px;
  background: var(--surface);
  border: 1px solid color-mix(in srgb, var(--color-primary) 22%, var(--hairline));
  box-shadow: 0 10px 30px -12px color-mix(in srgb, var(--color-primary) 45%, transparent),
              0 2px 6px rgba(0, 0, 0, 0.06);
  max-width: 520px;
  margin: 0 auto;
  /* Couche à part : la barre fixe n'est pas repeinte à chaque image du défilement. */
  transform: translateZ(0);
  will-change: transform;
}

.barre-app__onglet {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  text-decoration: none;
  color: var(--ink-faint);
  -webkit-tap-highlight-color: transparent;
  outline: none;
}

.barre-app__pastille {
  width: 44px;
  height: 32px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.barre-app__titre {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.barre-app__onglet.est-actif {
  color: var(--color-primary);
}

.barre-app__onglet.est-actif .barre-app__pastille {
  background: color-mix(in srgb, var(--color-primary) 14%, transparent);
}

.barre-app__plus {
  flex: 0 0 auto;
  width: 56px;
  height: 56px;
  margin: 0 4px;
  border: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  background: var(--color-primary);
  box-shadow: 0 8px 18px -6px color-mix(in srgb, var(--color-primary) 70%, transparent);
  transition: transform 0.2s ease;
  outline: none;
  -webkit-tap-highlight-color: transparent;
}

.barre-app__plus:active {
  transform: scale(0.94);
}

.barre-app__plus.est-ouvert {
  transform: rotate(45deg);
}

/* Feuille d'actions du « + » */
.feuille-app__corps {
  background: var(--surface);
  color: var(--ink);
  border-radius: 24px 24px 0 0;
  padding: 10px 16px calc(20px + env(safe-area-inset-bottom, 0px));
}

.feuille-app__poignee {
  width: 40px;
  height: 4px;
  border-radius: 999px;
  background: var(--hairline);
  margin: 0 auto 14px;
}

.feuille-app__entete {
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ink-faint);
  margin: 0 4px 8px;
}

.feuille-app__action {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 8px;
  border: 0;
  border-radius: 16px;
  background: transparent;
  color: inherit;
  text-align: left;
}

.feuille-app__action:active {
  background: var(--surface-2);
}

.feuille-app__icone {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.teinte-vente { background: color-mix(in srgb, var(--color-primary) 14%, transparent); color: var(--color-primary); }
.teinte-sortie { background: color-mix(in srgb, #d14343 14%, transparent); color: #d14343; }
.teinte-entree { background: color-mix(in srgb, #2f6fd6 14%, transparent); color: #2f6fd6; }

.feuille-app__textes {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.feuille-app__titre {
  font-size: 15px;
  font-weight: 650;
}

.feuille-app__detail {
  font-size: 12.5px;
  color: var(--ink-faint);
}

.feuille-app__fleche {
  color: var(--ink-faint);
}
</style>
