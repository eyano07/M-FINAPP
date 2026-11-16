import { useTheme } from 'vuetify'

/** Mode d'affichage choisi par l'utilisateur : clair, sombre (« nuit ») ou celui du système. */
export type ModeAffichage = 'clair' | 'sombre' | 'systeme'

const CLE = 'mbsc_mode_affichage'
const mode = ref<ModeAffichage>('systeme')
const effectif = ref<'clair' | 'sombre'>('clair')
let ecouteur: MediaQueryList | null = null

function lireMode(): ModeAffichage {
  try {
    const v = localStorage.getItem(CLE)
    if (v === 'clair' || v === 'sombre' || v === 'systeme') return v
  } catch { /* stockage indisponible : mode du système */ }
  return 'systeme'
}

function calculer(): 'clair' | 'sombre' {
  if (mode.value !== 'systeme') return mode.value
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'sombre' : 'clair'
}

/**
 * Applique le mode : attribut data-theme sur <html> (lu par les jetons CSS de classroom.scss) et thème Vuetify
 * (composants natifs). Le choix est mémorisé sur ce navigateur.
 */
export function useModeAffichage() {
  let theme: ReturnType<typeof useTheme> | null = null
  try { theme = useTheme() } catch { theme = null }

  function appliquer() {
    if (!import.meta.client) return
    effectif.value = calculer()
    document.documentElement.dataset.theme = effectif.value === 'sombre' ? 'dark' : 'light'
    document.documentElement.style.colorScheme = effectif.value === 'sombre' ? 'dark' : 'light'
    if (theme) theme.global.name.value = effectif.value === 'sombre' ? 'malachiteNuit' : 'classroomLight'
  }

  function initialiser() {
    if (!import.meta.client) return
    mode.value = lireMode()
    if (!ecouteur && window.matchMedia) {
      ecouteur = window.matchMedia('(prefers-color-scheme: dark)')
      ecouteur.addEventListener('change', () => { if (mode.value === 'systeme') appliquer() })
    }
    appliquer()
  }

  function choisir(m: ModeAffichage) {
    mode.value = m
    try { localStorage.setItem(CLE, m) } catch { /* rien à mémoriser */ }
    appliquer()
  }

  return { mode, effectif, choisir, initialiser }
}
