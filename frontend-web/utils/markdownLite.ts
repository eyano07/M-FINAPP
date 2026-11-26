/**
 * Markdown minimal pour afficher les réponses de l'IA (titres, gras, italique, code, listes à puces ou numérotées
 * avec sous-listes, tableaux simples, séparateurs).
 *
 * L'analyse produit un arbre de données, jamais du HTML : le composant qui l'affiche construit des éléments Vue et
 * des nœuds texte. Aucun contenu venu de l'IA n'est donc interprété comme du code (pas de v-html).
 */

export type Inline =
  | { t: 'texte'; v: string }
  | { t: 'saut' }
  | { t: 'gras'; c: Inline[] }
  | { t: 'italique'; c: Inline[] }
  | { t: 'code'; v: string }

export interface ItemListe { c: Inline[]; sous: Bloc[] }

export type Bloc =
  | { t: 'titre'; niveau: number; c: Inline[] }
  | { t: 'para'; c: Inline[] }
  | { t: 'liste'; ordonnee: boolean; items: ItemListe[] }
  | { t: 'tableau'; entete: Inline[][]; lignes: Inline[][][] }
  | { t: 'codebloc'; v: string }
  | { t: 'regle' }

// Pas de « lookbehind » dans ces motifs : Safari < 16.4 refuserait de charger tout le fichier.
const MOTIF_INLINE = /`([^`\n]+)`|\*\*([\s\S]+?)\*\*|__([\s\S]+?)__|\*([^*\s]|[^*\s][^*]*[^*\s])\*/g

/** Éléments en ligne d'un texte sans saut de ligne : `code`, **gras**, __gras__, *italique*. */
export function enLigne(s: string): Inline[] {
  const sortie: Inline[] = []
  let dernier = 0
  for (const m of s.matchAll(MOTIF_INLINE)) {
    const debut = m.index ?? 0
    if (debut > dernier) sortie.push({ t: 'texte', v: s.slice(dernier, debut) })
    if (m[1] !== undefined) sortie.push({ t: 'code', v: m[1] })
    else if (m[2] !== undefined || m[3] !== undefined) sortie.push({ t: 'gras', c: enLigne((m[2] ?? m[3]) as string) })
    else sortie.push({ t: 'italique', c: enLigne(m[4] as string) })
    dernier = debut + m[0].length
  }
  if (dernier < s.length) sortie.push({ t: 'texte', v: s.slice(dernier) })
  return sortie
}

/** Plusieurs lignes d'un même paragraphe, séparées par des sauts de ligne. */
function paragraphe(lignes: string[]): Inline[] {
  const sortie: Inline[] = []
  lignes.forEach((l, i) => {
    if (i > 0) sortie.push({ t: 'saut' })
    sortie.push(...enLigne(l.trim()))
  })
  return sortie
}

const TITRE = /^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/
const REGLE = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/
const PUCE = /^(\s*)([-*+•]|\d+[.)])\s+(.*)$/
const SEPARATEUR_TABLEAU = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/
const FENCE = /^\s*```/

const indentation = (s: string) => s.replace(/\t/g, '    ').length - s.replace(/\t/g, '    ').trimStart().length

function cellules(ligne: string): string[] {
  let l = ligne.trim()
  if (l.startsWith('|')) l = l.slice(1)
  if (l.endsWith('|')) l = l.slice(0, -1)
  return l.split('|').map(c => c.trim())
}

const debutDeBloc = (l: string, suivante?: string) =>
  TITRE.test(l) || REGLE.test(l) || PUCE.test(l) || FENCE.test(l)
  || (l.includes('|') && suivante !== undefined && SEPARATEUR_TABLEAU.test(suivante) && suivante.includes('-'))

/** Analyse un texte Markdown en blocs. Tolérant : tout ce qui n'est pas reconnu reste du texte. */
export function analyserMarkdown(texte: string): Bloc[] {
  const lignes = (texte ?? '').replace(/\r\n?/g, '\n').split('\n')
  const blocs: Bloc[] = []
  let i = 0
  while (i < lignes.length) {
    const ligne = lignes[i]!
    if (!ligne.trim()) { i++; continue }

    if (FENCE.test(ligne)) {
      const code: string[] = []
      i++
      while (i < lignes.length && !FENCE.test(lignes[i]!)) code.push(lignes[i++]!)
      i++
      blocs.push({ t: 'codebloc', v: code.join('\n') })
      continue
    }

    const titre = TITRE.exec(ligne)
    if (titre) {
      blocs.push({ t: 'titre', niveau: titre[1]!.length, c: enLigne(titre[2]!) })
      i++
      continue
    }

    if (REGLE.test(ligne)) {
      blocs.push({ t: 'regle' })
      i++
      continue
    }

    const suivante = lignes[i + 1]
    if (ligne.includes('|') && suivante !== undefined && suivante.includes('-') && SEPARATEUR_TABLEAU.test(suivante)) {
      const entete = cellules(ligne).map(enLigne)
      const corps: Inline[][][] = []
      i += 2
      while (i < lignes.length && lignes[i]!.trim() && lignes[i]!.includes('|')) corps.push(cellules(lignes[i++]!).map(enLigne))
      blocs.push({ t: 'tableau', entete, lignes: corps })
      continue
    }

    if (PUCE.test(ligne)) {
      i = analyserListe(lignes, i, blocs)
      continue
    }

    const para: string[] = []
    while (i < lignes.length && lignes[i]!.trim() && (para.length === 0 || !debutDeBloc(lignes[i]!, lignes[i + 1]))) {
      para.push(lignes[i++]!)
    }
    blocs.push({ t: 'para', c: paragraphe(para) })
  }
  return blocs
}

type Liste = Extract<Bloc, { t: 'liste' }>
/** Un niveau d'indentation de la liste en cours, avec le dernier élément qui y a été ajouté. */
interface Niveau { indent: number; bloc: Liste; dernier: ItemListe | null }

/** Liste (éventuellement imbriquée par l'indentation) commençant à la ligne `debut` ; renvoie l'index de la ligne suivante. */
function analyserListe(lignes: string[], debut: number, blocs: Bloc[]): number {
  const pile: Niveau[] = []
  let racine: Liste | null = null
  let dernierItem: ItemListe | null = null
  let i = debut
  while (i < lignes.length) {
    const ligne = lignes[i]!
    const puce = PUCE.exec(ligne)
    if (puce) {
      const indent = indentation(puce[1]!)
      const ordonnee = /\d/.test(puce[2]!)
      while (pile.length > 0 && pile[pile.length - 1]!.indent > indent) pile.pop()
      let niveau = pile[pile.length - 1]
      if (!niveau) {
        const liste: Liste = { t: 'liste', ordonnee, items: [] }
        racine = racine ?? liste
        niveau = { indent, bloc: liste, dernier: null }
        pile.push(niveau)
      } else if (niveau.indent < indent && niveau.dernier) {
        // Plus indentée que l'élément précédent du niveau courant : sous-liste de cet élément.
        const sous: Liste = { t: 'liste', ordonnee, items: [] }
        niveau.dernier.sous.push(sous)
        niveau = { indent, bloc: sous, dernier: null }
        pile.push(niveau)
      }
      dernierItem = { c: enLigne(puce[3]!.trim()), sous: [] }
      niveau.bloc.items.push(dernierItem)
      niveau.dernier = dernierItem
      i++
      continue
    }
    // Suite d'une puce trop longue, renvoyée à la ligne avec une indentation.
    if (ligne.trim() && dernierItem && indentation(ligne) > 0 && !debutDeBloc(ligne, lignes[i + 1])) {
      dernierItem.c.push({ t: 'texte', v: ' ' }, ...enLigne(ligne.trim()))
      i++
      continue
    }
    break
  }
  if (racine) blocs.push(racine)
  return i
}
