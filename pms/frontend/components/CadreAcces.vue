<script setup lang="ts">
/**
 * Cadre des pages d'accès (connexion, mot de passe, appairage, SSO) : à gauche un visuel de marque (dégradé, grille,
 * cartes flottantes qui évoquent l'exploitation), à droite le formulaire. Sous 960 px, le visuel disparaît.
 */
withDefaults(defineProps<{ titre?: string, sousTitre?: string }>(), {
  titre: 'L’hospitalité, en toute simplicité.',
  sousTitre: 'Réservations, réception, ménage et facturation, reliés à la comptabilité de M-FINAPP.',
})
</script>

<template>
  <div class="cadre">
    <aside class="cadre__visuel" aria-hidden="true">
      <div class="cadre__marque">
        <span class="cadre__logo"><LogoMfinapp /></span>
        <span><b>M-FINAPP</b><small>Hôtellerie</small></span>
      </div>
      <div class="cadre__accroche">
        <h2>{{ titre }}</h2>
        <p>{{ sousTitre }}</p>
        <ul>
          <li><v-icon icon="mdi-calendar-month-outline" size="18" />Calendrier centralisé, sans surréservation</li>
          <li><v-icon icon="mdi-cash-register" size="18" />Folio encaissé, écritures passées dans M-FINAPP</li>
          <li><v-icon icon="mdi-calendar-sync-outline" size="18" />Booking.com, Airbnb et réservation directe</li>
        </ul>
      </div>
      <div class="flottants">
        <div class="flottant flottant--a">
          <span class="flottant__icone"><v-icon icon="mdi-login-variant" size="16" /></span>
          <div><b>Arrivée · 14:00</b><small>Chambre 204 · confirmée</small></div>
        </div>
        <div class="flottant flottant--b">
          <div class="flottant__occ"><b>86</b><small>%</small></div>
          <div><b>Occupation</b><small>ce soir</small></div>
        </div>
        <div class="flottant flottant--c">
          <span class="flottant__icone flottant__icone--rose"><v-icon icon="mdi-broom" size="16" /></span>
          <div><b>Ménage terminé</b><small>Unité 12 prête</small></div>
        </div>
      </div>
    </aside>
    <main class="cadre__contenu">
      <div class="cadre__formulaire">
        <slot />
      </div>
    </main>
  </div>
</template>

<style scoped>
.cadre { min-height: 100dvh; display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr); background: var(--d-aurore), var(--d-canvas); }
.cadre__visuel {
  position: relative; overflow: hidden; isolation: isolate; margin: 14px; border-radius: 32px; padding: 40px 44px;
  display: flex; flex-direction: column; justify-content: space-between; color: #fff; background: var(--d-grad-hero);
}
.cadre__visuel::before {
  content: ''; position: absolute; inset: 0; z-index: -1; opacity: 0.6;
  background-image: linear-gradient(rgba(255, 255, 255, 0.07) 1px, transparent 1px), linear-gradient(90deg, rgba(255, 255, 255, 0.07) 1px, transparent 1px);
  background-size: 40px 40px; mask-image: radial-gradient(ellipse 80% 70% at 70% 60%, #000 10%, transparent 75%);
}
.cadre__marque { display: flex; align-items: center; gap: 12px; }
.cadre__marque b { display: block; font-weight: 800; letter-spacing: -0.02em; }
.cadre__marque small { display: block; opacity: 0.75; font-size: 0.8rem; }
.cadre__logo { width: 44px; height: 44px; border-radius: 14px; padding: 9px; display: grid; place-items: center; color: #fff; background: rgba(255, 255, 255, 0.14); border: 1px solid rgba(255, 255, 255, 0.2); }
.cadre__accroche { max-width: 480px; }
.cadre__accroche h2 { font-size: clamp(2rem, 3.4vw, 2.9rem); font-weight: 780; letter-spacing: -0.045em; line-height: 1.04; margin: 0 0 16px; }
.cadre__accroche p { font-size: 1.02rem; opacity: 0.82; line-height: 1.55; margin: 0 0 26px; }
.cadre__accroche ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 12px; font-size: 0.92rem; }
.cadre__accroche li { display: flex; align-items: center; gap: 12px; opacity: 0.92; }
.cadre__accroche li .v-icon { width: 32px; height: 32px; border-radius: 10px; background: rgba(255, 255, 255, 0.12); }

.flottants { position: relative; height: 150px; }
.flottant {
  position: absolute; display: flex; align-items: center; gap: 12px; padding: 12px 16px 12px 12px; border-radius: 18px; font-size: 0.82rem;
  background: rgba(255, 255, 255, 0.12); border: 1px solid rgba(255, 255, 255, 0.2); backdrop-filter: blur(14px);
  box-shadow: 0 20px 40px -24px rgba(0, 0, 0, 0.6); animation: flotte 7s ease-in-out infinite;
}
.flottant b { display: block; font-weight: 700; }
.flottant small { display: block; opacity: 0.75; }
.flottant--a { left: 0; top: 0; }
.flottant--b { left: 38%; top: 54px; animation-delay: -2.3s; }
.flottant--c { right: 0; top: 6px; animation-delay: -4.6s; }
.flottant__icone { width: 34px; height: 34px; border-radius: 11px; display: grid; place-items: center; background: rgba(167, 243, 208, 0.25); }
.flottant__icone--rose { background: rgba(253, 164, 175, 0.25); }
.flottant__occ { display: flex; align-items: baseline; font-variant-numeric: tabular-nums; }
.flottant__occ b { font-size: 1.6rem; letter-spacing: -0.04em; }
@keyframes flotte { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-8px); } }
@media (prefers-reduced-motion: reduce) { .flottant { animation: none; } }

.cadre__contenu { display: grid; place-items: center; padding: 32px 20px; }
.cadre__formulaire { width: 100%; max-width: 460px; }
.cadre__formulaire :deep(.acces__carte) {
  border-radius: 28px !important; border: 1px solid var(--d-line) !important; box-shadow: var(--d-shadow-3) !important; max-width: 100%;
}
.cadre__formulaire :deep(.acces__logo) {
  color: #fff !important; background: var(--d-grad) !important; padding: 12px !important; box-shadow: 0 12px 24px -12px color-mix(in srgb, var(--color-primary) 90%, transparent);
}

@media (max-width: 1099px) {
  .cadre { grid-template-columns: 1fr; }
  .cadre__visuel { display: none; }
}
</style>
