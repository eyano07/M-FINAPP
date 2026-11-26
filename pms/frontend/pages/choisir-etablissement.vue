<script setup lang="ts">
import { useEtablissementStore } from '~/stores/etablissement'
import { useSessionStore } from '~/stores/session'

/** Choix de l'établissement de travail à la connexion (plusieurs hôtels, motels, résidences d'appartements). */
definePageMeta({ layout: false })
useHead({ title: 'Choisir l’établissement' })

const etablissements = useEtablissementStore()
const session = useSessionStore()
const route = useRoute()
const ICONES: Record<string, string> = { HOTEL: 'mdi-office-building', MOTEL: 'mdi-car-estate', APPARTEMENTS: 'mdi-home-city-outline', MIXTE: 'mdi-domain' }
const TYPES: Record<string, string> = { HOTEL: 'Hôtel', MOTEL: 'Motel', APPARTEMENTS: 'Appartements', MIXTE: 'Hôtel et appartements' }
/** Couverture de chaque carte : un dégradé par nature d'établissement. */
const COUVERTURES: Record<string, string> = {
  HOTEL: 'linear-gradient(135deg, color-mix(in srgb, var(--color-primary) 85%, #34d399), color-mix(in srgb, var(--color-primary) 60%, #064e3b))',
  MOTEL: 'linear-gradient(135deg, #f59e0b, #c2410c)',
  APPARTEMENTS: 'linear-gradient(135deg, #818cf8, #4338ca)',
  MIXTE: 'linear-gradient(135deg, color-mix(in srgb, var(--color-primary) 80%, #34d399), #4338ca)',
}
const prenom = computed(() => session.utilisateur?.prenom ?? '')
const voitTous = computed(() => session.a('DIR_HEBERGEMENT', 'ADMIN', 'DG', 'DFIN', 'DA', 'COMPTABLE'))

onMounted(async () => {
  await etablissements.charger()
  if (etablissements.choisi !== undefined && route.query.changer !== '1') await navigateTo((route.query.suite as string) || '/')
})

async function choisir(id: number | null) {
  etablissements.choisir(id)
  await navigateTo((route.query.suite as string) || '/')
}
</script>

<template>
  <div class="choix">
    <header class="choix__tete">
      <div class="choix__marque"><span class="choix__logo"><LogoMfinapp /></span><span><b>M-FINAPP</b><small>Hôtellerie</small></span></div>
      <v-btn variant="text" prepend-icon="mdi-logout" @click="session.fermer(); navigateTo('/login')">Se déconnecter</v-btn>
    </header>
    <main class="choix__contenu">
      <div class="choix__intro">
        <div class="page-eyebrow">{{ prenom ? `Bienvenue, ${prenom}` : 'Bienvenue' }}</div>
        <h1 class="choix__titre">Où travaillez-vous aujourd’hui ?</h1>
        <p class="choix__sous">Choisissez l’établissement ; vous pourrez en changer à tout moment depuis le menu.</p>
      </div>
      <v-progress-linear v-if="!etablissements.charge" indeterminate color="primary" rounded class="mb-6" />
      <div class="grille d-apparait">
        <button v-for="e in etablissements.liste" :key="e.id" type="button" class="carte" :class="{ 'carte--active': etablissements.choisi === e.id }" @click="choisir(e.id)">
          <span class="carte__couverture" :style="{ background: COUVERTURES[e.type] ?? COUVERTURES.HOTEL }">
            <v-icon :icon="ICONES[e.type] ?? 'mdi-domain'" size="34" />
            <span v-if="etablissements.choisi === e.id" class="carte__coche"><v-icon icon="mdi-check" size="16" />Actuel</span>
          </span>
          <span class="carte__corps">
            <span class="carte__nom">{{ e.nom }}</span>
            <span class="carte__type">{{ TYPES[e.type] ?? e.type }}</span>
            <span class="carte__infos">
              <span><v-icon icon="mdi-bed-outline" size="15" />{{ e.unites }} unité{{ e.unites > 1 ? 's' : '' }}</span>
              <span v-if="e.adresse"><v-icon icon="mdi-map-marker-outline" size="15" />{{ e.adresse }}</span>
            </span>
          </span>
          <span class="carte__aller"><v-icon icon="mdi-arrow-right" size="18" /></span>
        </button>
        <button v-if="voitTous && etablissements.liste.length > 1" type="button" class="carte carte--tous" :class="{ 'carte--active': etablissements.choisi === null }" @click="choisir(null)">
          <span class="carte__couverture carte__couverture--tous"><v-icon icon="mdi-view-grid-outline" size="34" /></span>
          <span class="carte__corps">
            <span class="carte__nom">Tous les établissements</span>
            <span class="carte__type">Vue d’ensemble de la direction</span>
            <span class="carte__infos"><span><v-icon icon="mdi-domain" size="15" />{{ etablissements.liste.length }} établissements</span></span>
          </span>
          <span class="carte__aller"><v-icon icon="mdi-arrow-right" size="18" /></span>
        </button>
      </div>
      <div v-if="etablissements.charge && !etablissements.liste.length" class="d-carte vide">
        <div class="d-vide">
          <div class="d-vide__icone"><v-icon icon="mdi-office-building-plus-outline" size="28" /></div>
          <div class="d-vide__titre">Aucun établissement n’est encore créé</div>
          <div class="d-vide__texte">La direction de l’hébergement crée les hôtels, motels et résidences dans « Établissements et tarifs ».</div>
          <v-btn v-if="session.a('DIR_HEBERGEMENT', 'ADMIN')" color="primary" variant="flat" class="mt-3" prepend-icon="mdi-plus" @click="choisir(null)">Créer le premier</v-btn>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.choix { min-height: 100dvh; background: var(--d-aurore), var(--d-canvas); }
.choix__tete { display: flex; align-items: center; justify-content: space-between; padding: 18px clamp(16px, 4vw, 40px); }
.choix__marque { display: flex; align-items: center; gap: 12px; }
.choix__marque b { display: block; font-weight: 800; letter-spacing: -0.02em; }
.choix__marque small { display: block; font-size: 0.78rem; color: var(--d-ink-faint); font-weight: 600; }
.choix__logo { width: 42px; height: 42px; border-radius: 14px; padding: 9px; display: grid; place-items: center; color: #fff; background: var(--d-grad);
  box-shadow: 0 10px 22px -12px color-mix(in srgb, var(--color-primary) 90%, transparent); }
.choix__contenu { max-width: 1080px; margin: 0 auto; padding: clamp(16px, 5vh, 56px) 20px 56px; }
.choix__intro { margin-bottom: 30px; }
.choix__titre { font-size: clamp(2rem, 4.2vw, 3rem); font-weight: 780; letter-spacing: -0.045em; line-height: 1.05; margin: 0 0 10px; }
.choix__sous { font-size: 1rem; color: var(--d-ink-soft); margin: 0; }
.grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 18px; }
.carte {
  position: relative; display: flex; flex-direction: column; text-align: left; padding: 0; border: 0; cursor: pointer; font: inherit; color: inherit;
  border-radius: 24px; overflow: hidden; background: var(--d-surface); box-shadow: var(--d-shadow-2), inset 0 0 0 1px var(--d-line);
  transition: transform 0.22s cubic-bezier(0.2, 0.7, 0.2, 1), box-shadow 0.22s;
}
.carte:hover { transform: translateY(-4px); box-shadow: var(--d-shadow-3), inset 0 0 0 1px var(--d-line); }
.carte:focus-visible { outline: 3px solid color-mix(in srgb, var(--color-primary) 50%, transparent); outline-offset: 3px; }
.carte--active { box-shadow: var(--d-shadow-2), inset 0 0 0 2px var(--color-primary); }
.carte__couverture {
  position: relative; height: 120px; display: flex; align-items: flex-end; padding: 18px; color: #fff;
}
.carte__couverture::after {
  content: ''; position: absolute; inset: 0; opacity: 0.5;
  background-image: radial-gradient(circle at 85% 15%, rgba(255, 255, 255, 0.35), transparent 45%),
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: auto, 22px 22px, 22px 22px;
}
.carte__couverture .v-icon { position: relative; z-index: 1; }
.carte__couverture--tous { background: linear-gradient(135deg, #334155, #0f172a); }
.carte__coche {
  position: absolute; z-index: 1; top: 14px; right: 14px; display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; border-radius: 999px;
  font-size: 0.74rem; font-weight: 700; background: rgba(255, 255, 255, 0.22); backdrop-filter: blur(6px);
}
.carte__corps { display: flex; flex-direction: column; gap: 2px; padding: 16px 18px 20px; }
.carte__nom { font-weight: 760; font-size: 1.1rem; letter-spacing: -0.025em; }
.carte__type { font-size: 0.84rem; color: var(--d-ink-soft); }
.carte__infos { display: flex; flex-wrap: wrap; gap: 6px 14px; margin-top: 10px; font-size: 0.8rem; color: var(--d-ink-faint); }
.carte__infos span { display: inline-flex; align-items: center; gap: 5px; }
.carte__aller {
  position: absolute; right: 16px; bottom: 18px; width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center;
  color: var(--color-primary); background: color-mix(in srgb, var(--color-primary) 10%, var(--d-surface)); transition: transform 0.2s;
}
.carte:hover .carte__aller { transform: translateX(3px); }
.vide { max-width: 560px; }
</style>
