import { defineStore } from 'pinia'

/** Préférences d'affichage enregistrées sur le compte (liste blanche côté serveur : ProfilService). */
export type ClePreference = 'notes.affichage'

const PREFIXE_CACHE = 'mbsc_prefs_'

/** Identifiant de connexion de l'utilisateur courant (clé de la copie gardée dans ce navigateur). */
function identifiantCourant(): string | null {
  const email = useAuthStore().user?.email
  return email ? String(email).toLowerCase() : null
}

function lireCache(identifiant: string | null): Record<string, string> {
  if (!identifiant || !import.meta.client) return {}
  try {
    const brut = JSON.parse(localStorage.getItem(PREFIXE_CACHE + identifiant) || '{}')
    return brut && typeof brut === 'object' ? brut : {}
  } catch {
    return {}
  }
}

function ecrireCache(identifiant: string | null, valeurs: Record<string, string>) {
  if (!identifiant || !import.meta.client) return
  try {
    localStorage.setItem(PREFIXE_CACHE + identifiant, JSON.stringify(valeurs))
  } catch {
    // stockage plein ou refusé : le serveur garde la préférence
  }
}

/**
 * Préférences d'affichage de l'utilisateur, enregistrées sur son COMPTE (GET/PUT /profil/preferences) : elles le suivent d'un appareil à
 * l'autre et survivent à la déconnexion. Une copie par utilisateur est gardée dans le navigateur pour le premier affichage (pas de
 * clignotement le temps de la réponse) ; la réponse du serveur fait foi. Un serveur qui n'a pas encore la route (version plus ancienne)
 * ne bloque rien : le choix reste appliqué et gardé dans ce navigateur.
 */
export const usePreferencesStore = defineStore('preferences', {
  state: () => ({
    valeurs: {} as Record<string, string>,
    /** Vrai une fois la réponse du serveur reçue (ou en échec). */
    charge: false,
  }),

  getters: {
    /** Valeur de la préférence, ou `defaut` tant qu'aucun choix n'a été fait. */
    valeur: (s) => (cle: ClePreference, defaut: string): string => s.valeurs[cle] ?? defaut,
    /** Vrai dès qu'on peut décider de l'affichage sans risque de clignotement : copie locale ou réponse du serveur. */
    connu: (s): boolean => s.charge || Object.keys(s.valeurs).length > 0,
  },

  actions: {
    /** Première image : la copie gardée dans ce navigateur pour cet utilisateur. */
    hydrater() {
      if (Object.keys(this.valeurs).length === 0) this.valeurs = lireCache(identifiantCourant())
    },

    /** Charge les préférences du compte (à l'ouverture de session). Ne lève jamais. */
    async charger() {
      this.hydrater()
      const locales = { ...this.valeurs }
      try {
        const api = useApi()
        const distantes = await api<Record<string, string>>('/profil/preferences')
        this.valeurs = { ...locales, ...distantes }
        ecrireCache(identifiantCourant(), this.valeurs)
        // Un choix fait sur cet appareil avant que le serveur sache les enregistrer rejoint le compte.
        for (const [cle, valeur] of Object.entries(locales)) {
          if (!(cle in distantes)) void this.definir(cle as ClePreference, valeur)
        }
      } catch {
        // route absente (serveur plus ancien) ou réseau coupé : on garde la copie locale
      } finally {
        this.charge = true
      }
    },

    /** Applique le choix tout de suite à l'écran, le garde dans ce navigateur, puis l'enregistre sur le compte. */
    async definir(cle: ClePreference, valeur: string) {
      this.valeurs = { ...this.valeurs, [cle]: valeur }
      ecrireCache(identifiantCourant(), this.valeurs)
      try {
        const api = useApi()
        const distantes = await api<Record<string, string>>('/profil/preferences', { method: 'PUT', body: { cle, valeur } })
        this.valeurs = { ...this.valeurs, ...distantes }
        ecrireCache(identifiantCourant(), this.valeurs)
      } catch {
        // le choix reste appliqué ici ; il sera de nouveau proposé au serveur à la prochaine ouverture de session
      }
    },

    /** À la déconnexion : le compte suivant ne doit pas hériter des choix du précédent (la copie locale reste, par utilisateur). */
    reinitialiser() {
      this.valeurs = {}
      this.charge = false
    },
  },
})
