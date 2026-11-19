import { defineStore } from 'pinia'

/** Exercice comptable (année civile), tel que renvoyé par GET /exercices. */
export interface Exercice {
  annee: number
  debut: string
  fin: string
  statut: 'CLOTURE' | 'A_CLOTURER' | 'EN_COURS' | 'A_VENIR'
  courant: boolean
  lectureSeule: boolean
  dateCloture: string | null
}

const CLE = 'mbsc_exercice_'

const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

function cle(): string | null {
  const email = useAuthStore().user?.email
  return email ? CLE + String(email).toLowerCase() : null
}

/**
 * Exercice consulté (19/11/2026), choisi dans la barre du haut du site. Par défaut l'exercice en cours ; le choix est
 * gardé pour la session du navigateur (sessionStorage : on revient à l'exercice en cours à la prochaine ouverture).
 * Les pages prennent ses bornes comme période par défaut ; un exercice clôturé se consulte en lecture seule : le site
 * envoie l'en-tête X-Exercice et le serveur refuse les saisies (ExerciceConsulteFilter), l'impression restant ouverte.
 */
export const useExerciceStore = defineStore('exercice', {
  state: () => ({
    liste: [] as Exercice[],
    annee: null as number | null,
    charge: false,
  }),

  getters: {
    anneeCourante: (s): number => s.liste.find(e => e.courant)?.annee ?? new Date().getFullYear(),
    anneeChoisie(): number { return this.annee ?? this.anneeCourante },
    choisi(): Exercice | undefined { return this.liste.find(e => e.annee === this.anneeChoisie) },
    /** Vrai quand l'utilisateur consulte un autre exercice que l'exercice en cours. */
    enConsultation(): boolean { return this.anneeChoisie !== this.anneeCourante },
    /** Exercice clôturé définitivement : lecture et impression seulement. */
    lectureSeule(): boolean { return !!this.choisi?.lectureSeule },
    /** Premier jour de l'exercice consulté (AAAA-MM-JJ). */
    debut(): string { return `${this.anneeChoisie}-01-01` },
    /** Dernier jour à afficher : aujourd'hui pour l'exercice en cours, le 31 décembre sinon. */
    fin(): string {
      const aujourdhui = iso(new Date())
      if (this.anneeChoisie === new Date().getFullYear()) return aujourdhui
      return `${this.anneeChoisie}-12-31`
    },
  },

  actions: {
    /** Reprend l'exercice choisi plus tôt dans cette session du navigateur (avant le premier affichage de la page). */
    hydrater() {
      if (!import.meta.client || this.annee !== null) return
      try {
        const k = cle()
        const garde = k ? sessionStorage.getItem(k) : null
        if (garde && /^\d{4}$/.test(garde) && Number(garde) !== new Date().getFullYear()) this.annee = Number(garde)
      } catch { /* stockage indisponible : exercice en cours */ }
    },

    /** Charge la liste des exercices (à l'ouverture du site). Ne lève jamais. */
    async charger() {
      this.hydrater()
      try {
        this.liste = await useApi()<Exercice[]>('/exercices')
        if (this.annee !== null && !this.liste.some(e => e.annee === this.annee)) this.annee = null
      } catch {
        // serveur plus ancien ou injoignable : exercice en cours, sans liste
      } finally {
        this.charge = true
      }
    },

    /**
     * Pour les listes servies en entier par le serveur : vrai si la date (AAAA-MM-JJ…) tombe dans l'exercice consulté.
     * Exercice en cours : tout passe, pour ne pas cacher ce qui est encore en circuit depuis l'an dernier.
     */
    dansExercice(date?: string | null): boolean {
      if (!this.enConsultation) return true
      return !!date && date.startsWith(String(this.anneeChoisie))
    },

    choisir(annee: number) {
      this.annee = annee === this.anneeCourante ? null : annee
      try {
        const k = cle()
        if (k) {
          if (this.annee === null) sessionStorage.removeItem(k)
          else sessionStorage.setItem(k, String(this.annee))
        }
      } catch { /* stockage indisponible */ }
    },

    oublier() {
      this.annee = null
      this.liste = []
      this.charge = false
    },
  },
})
