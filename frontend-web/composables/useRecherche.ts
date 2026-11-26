/**
 * Recherche dans une liste déjà chargée (carte du restaurant, fiches techniques...).
 *
 * Insensible à la casse et aux accents : « biere » retrouve « Bière ». Plusieurs mots se
 * cumulent : « primus bracongo » ne garde que ce qui contient les deux, dans n'importe quel
 * champ et dans n'importe quel ordre.
 */

/** Minuscules sans accents ni espaces superflus. */
export function normaliserTexte(texte: string | null | undefined): string {
  return (texte ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/œ/gi, 'oe')
    .replace(/æ/gi, 'ae')
    .toLowerCase()
    .trim()
}

/**
 * Vrai si chaque mot de la saisie figure dans l'un des textes ; une saisie vide
 * correspond à tout.
 */
export function correspondRecherche(textes: Array<string | null | undefined>, saisie: string): boolean {
  const mots = normaliserTexte(saisie).split(/\s+/).filter(Boolean)
  if (!mots.length) return true
  const ensemble = textes.map(normaliserTexte).join(' ')
  return mots.every(mot => ensemble.includes(mot))
}
