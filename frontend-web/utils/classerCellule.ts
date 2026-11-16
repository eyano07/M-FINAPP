/**
 * Rôle d'une colonne de tableau, pour que le même tableau devienne, sous 960 px, une carte à tuiles lisible
 * (assets/styles/responsive.scss) sans qu'aucune page n'ait à décrire sa carte.
 *
 * Branché une fois pour tous les `v-data-table` par `plugins/vuetify.ts` (`cellProps` par défaut) : la cellule reçoit la
 * classe `mc-<rôle>`. Une page peut toujours donner son propre `cellProps` (au tableau ou à une colonne) pour affiner.
 *
 *  - `actions` : boutons de la ligne → pied de carte ;
 *  - `montant` : colonne alignée à droite (quantités, montants) → chiffres clés, côte à côte ;
 *  - `badge`   : statut, état, actif, sens… → puce à droite de l'en-tête ;
 *  - `texte`   : libellé, désignation, objet… → texte principal sur toute la largeur, sans étiquette ;
 *  - `large`   : fournisseur, adresse, observation… → toute la largeur, avec son étiquette ;
 *  - `tuile`   : tout le reste → petite tuile « étiquette au-dessus, valeur dessous ».
 * La 1re colonne de données (l'identifiant de la ligne : référence, code, nom) devient l'en-tête de la carte : c'est le CSS
 * qui la repère (`:first-child`), la cellule ne connaissant pas sa position.
 */
export type RoleCellule = 'actions' | 'montant' | 'badge' | 'texte' | 'large' | 'tuile'

/** Clés de colonne (sans accents ni majuscules) dont la valeur est une puce de statut. */
const CLES_BADGE = new Set(['statut', 'status', 'etat', 'actif', 'sens', 'priorite', 'type'])

/** Fragments de clé du texte principal d'une ligne (affiché seul, sans étiquette). */
const FRAGMENTS_TEXTE = ['libell', 'designation', 'intitul', 'description', 'objet']

/** Fragments de clé des autres colonnes de texte long (affichées avec leur étiquette, sur toute la largeur). */
const FRAGMENTS_LARGE = [
  'motif', 'observation', 'adresse', 'beneficiaire', 'fournisseur', 'client', 'nomcomplet', 'commentaire', 'detail', 'message',
  'localisation',
]

function normaliser(texte: unknown): string {
  return String(texte ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
}

export function roleCellule(column: { key?: string | null; title?: string | null; align?: string | null } | null | undefined): RoleCellule | null {
  const cle = normaliser(column?.key)
  if (cle === 'data-table-select' || cle === 'data-table-expand' || cle === 'data-table-group') return null
  if (cle === 'actions' || cle === 'action' || normaliser(column?.title) === 'actions') return 'actions'
  if (column?.align === 'end') return 'montant'
  if (CLES_BADGE.has(cle)) return 'badge'
  if (FRAGMENTS_TEXTE.some(f => cle.includes(f))) return 'texte'
  if (cle === 'nom' || FRAGMENTS_LARGE.some(f => cle.includes(f))) return 'large'
  return 'tuile'
}

/** `cellProps` par défaut des `v-data-table` : pose la classe de rôle sur chaque cellule. */
export function classerCellule({ column }: { column?: { key?: string | null; title?: string | null; align?: string | null } }) {
  const role = roleCellule(column)
  return role ? { class: ['mc', `mc-${role}`] } : {}
}
