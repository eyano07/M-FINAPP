import { defineStore } from 'pinia'
import { useTheme } from 'vuetify'
import type { ModeleEntete } from '~/composables/useModeleEntete'

export interface Parametres {
  nom: string
  nomComplet?: string | null
  slogan?: string | null
  adresse?: string | null
  telephone?: string | null
  email?: string | null
  rccm?: string | null
  idNat?: string | null
  nif?: string | null
  logoUrl?: string | null
  couleurPrimaire?: string
  /** Modèle de papier à en-tête des documents imprimés et PDF (voir composables/useModeleEntete.ts). */
  modeleEntete?: ModeleEntete
  /** Régime de TVA : false = entreprise non assujettie (factures sans TVA, mention « non applicable »). */
  assujettiTva?: boolean
  /** Adresse du serveur pour les applications Android (URL ou IP), saisie par l'administrateur : QR de « À propos ». */
  adresseServeur?: string | null
}

const DEFAUT: Parametres = {
  nom: 'MBSC Finapp', nomComplet: null, slogan: null, adresse: null, telephone: null,
  email: null, rccm: null, idNat: null, nif: null, logoUrl: null,
  couleurPrimaire: '#15803D',
  modeleEntete: 'CLASSIQUE',
  assujettiTva: true,
}

function hexToRgbTriplet(hex: string): string | null {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  return m ? [m[1], m[2], m[3]].map(h => parseInt(h, 16)).join(',') : null
}

/** Luminance relative WCAG d'une couleur « #rrggbb » (0 = noir, 1 = blanc) ; 0 si le format est invalide. */
function luminanceRelative(hex: string): number {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  if (!m) return 0
  const [r, g, b] = [m[1], m[2], m[3]].map((h) => {
    const v = parseInt(h, 16) / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Assombrit un triplet "r,g,b" — approximation du darken-1 que Vuetify calcule lui-meme a la generation du theme. */
function assombrirRgbTriplet(rgb: string, facteur = 0.15): string {
  return rgb.split(',').map(v => Math.round(Number(v) * (1 - facteur))).join(',')
}

/**
 * Applique la couleur de marque a deux niveaux : le theme Vuetify (tout ce
 * qui utilise color="primary") et une variable CSS globale --color-primary
 * (les accents codes en dur dans les <style> scoped — bordures d'entete
 * d'impression, degrades... — y font desormais reference au lieu d'un hex
 * fixe). Cote client uniquement : document/useTheme n'existent pas en SSR.
 *
 * "success" suit la meme couleur que "primary" : ce theme les a toujours
 * definis identiques (voir plugins/vuetify.ts), et une bonne partie des
 * boutons d'action de l'appli (ex. "Nouveau conditionnement") utilisent
 * color="success" comme simple variante de la couleur de marque plutot que
 * comme indicateur de statut — les figer romprait l'uniformisation voulue.
 * Les badges de statut reellement semantiques (paye/valide/rejete...) sont
 * codes en hex direct ailleurs et restent donc inchanges par ce reglage.
 */
/** Version éclaircie de la couleur de marque pour le thème nuit (mélange avec un vert très clair), en "#rrggbb". */
function eclaircirPourNuit(hex: string): string {
  const rgb = hexToRgbTriplet(hex)
  if (!rgb) return '#3FBF7F'
  const cible = [196, 246, 220]
  return '#' + rgb.split(',').map((v, i) => Math.round(Number(v) * 0.6 + cible[i] * 0.4).toString(16).padStart(2, '0')).join('')
}

/** Mélange `part` (0 à 1) de la couleur de marque dans un noir donné : fonds du thème nuit teintés de la marque. */
function fondNuit(hex: string, part: number, noir: [number, number, number]): string {
  const rgb = hexToRgbTriplet(hex)
  if (!rgb) return '#' + noir.map(v => v.toString(16).padStart(2, '0')).join('')
  return '#' + rgb.split(',').map((v, i) => Math.round(Number(v) * part + noir[i] * (1 - part)).toString(16).padStart(2, '0')).join('')
}

/**
 * Fonds du thème nuit dérivés de la couleur choisie par l'administrateur : la page, les cartes, les filets et la barre
 * latérale sont une version très sombre de SA couleur (et non plus un vert-noir fixe).
 */
function fondsNuit(couleur: string) {
  return {
    canvas: fondNuit(couleur, 0.10, [6, 8, 7]),
    surface: fondNuit(couleur, 0.15, [10, 13, 12]),
    surface2: fondNuit(couleur, 0.19, [14, 18, 16]),
    hairline: fondNuit(couleur, 0.28, [20, 26, 23]),
    nav: fondNuit(couleur, 0.12, [3, 5, 4]),
  }
}

function appliquerCouleurPrimaire(couleur: string) {
  if (!import.meta.client || !couleur) return
  const nuit = eclaircirPourNuit(couleur)
  document.documentElement.style.setProperty('--color-primary', couleur)
  // Texte posé sur la couleur de marque (bandeau imprimé) : sombre sur une couleur claire, blanc sinon.
  document.documentElement.style.setProperty('--color-on-primary', luminanceRelative(couleur) > 0.42 ? '#111827' : '#ffffff')
  document.documentElement.style.setProperty('--color-primary-nuit', nuit)
  const fonds = fondsNuit(couleur)
  document.documentElement.style.setProperty('--nuit-canvas', fonds.canvas)
  document.documentElement.style.setProperty('--nuit-surface', fonds.surface)
  document.documentElement.style.setProperty('--nuit-surface-2', fonds.surface2)
  document.documentElement.style.setProperty('--nuit-hairline', fonds.hairline)
  document.documentElement.style.setProperty('--nuit-nav', fonds.nav)

  try {
    const theme = useTheme()
    for (const [nom, c] of [['classroomLight', couleur], ['malachiteNuit', nuit]] as const) {
      const cible = theme.themes.value[nom]
      if (cible) {
        cible.colors.primary = c
        cible.colors.success = c
        if (nom === 'malachiteNuit') {
          cible.colors.background = fonds.canvas
          cible.colors.surface = fonds.surface
          cible.colors['surface-bright'] = fonds.surface2
        }
      }
    }
  } catch {
    // useTheme() exige un contexte de composant actif : sans consequence si
    // ce n'est pas encore le cas, la variable CSS ci-dessus reste appliquee.
  }

  // Vuetify n'expose "primary"/"success" aux composants natifs (v-btn
  // color="primary"...) qu'via une CSS custom property (--v-theme-primary,
  // format "r,g,b") definie par une regle .v-theme--<nom> QUE CHAQUE
  // COMPOSANT PORTE INDIVIDUELLEMENT (pas un seul conteneur racine) : muter
  // theme.themes.value ci-dessus ne regenere pas cette regle pour les
  // elements deja montes. On la patche donc directement via le CSSOM —
  // seul moyen d'atteindre tous les elements, presents et futurs, sans les
  // cibler un par un.
  const sheet = (document.getElementById('vuetify-theme-stylesheet') as HTMLStyleElement | null)?.sheet
  for (const [nom, c] of [['classroomLight', couleur], ['malachiteNuit', nuit]] as const) {
    const rgb = hexToRgbTriplet(c)
    if (!rgb || !sheet) continue
    const regle = Array.from(sheet.cssRules).find(
      (r): r is CSSStyleRule => r instanceof CSSStyleRule && r.selectorText === `.v-theme--${nom}`)
    regle?.style.setProperty('--v-theme-primary', rgb)
    regle?.style.setProperty('--v-theme-success', rgb)
    regle?.style.setProperty('--v-theme-primary-darken-1', assombrirRgbTriplet(rgb))
    if (nom === 'malachiteNuit') {
      // Cartes, tableaux, menus : la surface Vuetify suit elle aussi la couleur choisie.
      for (const [variable, valeur] of [['--v-theme-background', fonds.canvas], ['--v-theme-surface', fonds.surface],
        ['--v-theme-surface-bright', fonds.surface2]] as const) {
        const t = hexToRgbTriplet(valeur)
        if (t) regle?.style.setProperty(variable, t)
      }
    }
  }
}

/** Modele de papier a en-tete des impressions : lu par les regles html[data-entete] de classroom.scss. */
function appliquerModeleEntete(modele: string) {
  if (!import.meta.client) return
  document.documentElement.dataset.entete = modele || 'CLASSIQUE'
}

/**
 * Identite visuelle de l'entreprise. Chargee via $fetch brut (pas useApi()) :
 * l'endpoint est public et doit rester lisible depuis /login, avant toute
 * authentification — donc avant qu'un jeton existe.
 */
export const useParametresStore = defineStore('parametres', {
  state: () => ({
    parametres: DEFAUT as Parametres,
    charge: false,
  }),

  actions: {
    async charger() {
      const config = useRuntimeConfig()
      const apiBase = config.public.apiBase as string
      try {
        const data = await $fetch<Parametres>('/parametres', { baseURL: apiBase })
        // Le backend renvoie un chemin relatif a la racine de l'API (meme
        // convention que les autres telechargements de fichiers de l'appli) :
        // sans ce prefixe, un <img :src="logoUrl"> le resoudrait relatif a
        // l'origine du site (le frontend Nuxt), pas au backend derriere /api.
        this.parametres = {
          ...data,
          logoUrl: data.logoUrl ? `${apiBase}${data.logoUrl}` : null,
        }
        appliquerCouleurPrimaire(this.parametres.couleurPrimaire || DEFAUT.couleurPrimaire!)
        appliquerModeleEntete(this.parametres.modeleEntete || DEFAUT.modeleEntete!)
      } catch {
        // Best-effort : conserve les valeurs par defaut si l'appel echoue
        // (ex. backend indisponible au tout premier chargement).
      } finally {
        this.charge = true
      }
    },

    /** Aperçu immediat depuis la page d'admin, avant meme d'enregistrer. */
    previsualiserCouleur(couleur: string) {
      appliquerCouleurPrimaire(couleur)
    },

    /** Aperçu du modele de papier a en-tete (impressions) depuis la page d'admin, avant d'enregistrer. */
    previsualiserModeleEntete(modele: string) {
      appliquerModeleEntete(modele)
    },
  },
})
