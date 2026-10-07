import { defineStore } from 'pinia'
import { useTheme } from 'vuetify'

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
  /** Régime de TVA : false = entreprise non assujettie (factures sans TVA, mention « non applicable »). */
  assujettiTva?: boolean
}

const DEFAUT: Parametres = {
  nom: 'MBSC Finapp', nomComplet: null, slogan: null, adresse: null, telephone: null,
  email: null, rccm: null, idNat: null, nif: null, logoUrl: null,
  couleurPrimaire: '#16A34A',
  assujettiTva: true,
}

function hexToRgbTriplet(hex: string): string | null {
  const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  return m ? [m[1], m[2], m[3]].map(h => parseInt(h, 16)).join(',') : null
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
function appliquerCouleurPrimaire(couleur: string) {
  if (!import.meta.client || !couleur) return
  document.documentElement.style.setProperty('--color-primary', couleur)

  let nomTheme = 'classroomLight'
  try {
    const theme = useTheme()
    nomTheme = theme.name.value || nomTheme
    const cible = theme.themes.value[nomTheme] ?? theme.themes.value.classroomLight
    if (cible) {
      cible.colors.primary = couleur
      cible.colors.success = couleur
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
  const rgb = hexToRgbTriplet(couleur)
  const sheet = (document.getElementById('vuetify-theme-stylesheet') as HTMLStyleElement | null)?.sheet
  if (rgb && sheet) {
    const regle = Array.from(sheet.cssRules).find(
      (r): r is CSSStyleRule => r instanceof CSSStyleRule && r.selectorText === `.v-theme--${nomTheme}`)
    regle?.style.setProperty('--v-theme-primary', rgb)
    regle?.style.setProperty('--v-theme-success', rgb)
    regle?.style.setProperty('--v-theme-primary-darken-1', assombrirRgbTriplet(rgb))
  }
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
  },
})
