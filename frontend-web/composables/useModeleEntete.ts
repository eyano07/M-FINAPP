/**
 * Modèles de papier à en-tête proposés à l'administrateur (écran Paramètres). Le modèle choisi habille
 * tous les documents : PDF générés par le serveur (papier à en-tête, ordre de mission, budget) et
 * impressions depuis le navigateur (attribut data-entete posé sur <html>, voir stores/parametres.ts et
 * assets/styles/classroom.scss). Mêmes valeurs que l'énumération ModeleEntete du backend.
 */
export type ModeleEntete = 'CLASSIQUE' | 'BANDEAU' | 'EPURE' | 'CENTRE' | 'LATERAL' | 'ENCADRE'

export const MODELES_ENTETE: { value: ModeleEntete, titre: string, description: string }[] = [
  { value: 'CLASSIQUE', titre: 'Classique', description: 'Logo à gauche, bandeau incliné à droite avec RCCM, ID. Nat et NIF.' },
  { value: 'BANDEAU', titre: 'Bandeau', description: 'Bandeau plein de la couleur du thème, textes en blanc.' },
  { value: 'EPURE', titre: 'Épuré', description: 'Minimaliste : nom en couleur, identifiants en gris, un simple filet.' },
  { value: 'CENTRE', titre: 'Institutionnel', description: 'Logo et nom centrés, double filet : pour les courriers officiels.' },
  { value: 'LATERAL', titre: 'Latéral', description: 'Barre de couleur le long du bord gauche de chaque page.' },
  { value: 'ENCADRE', titre: 'Encadré', description: 'En-tête et pied de page dans des cartouches teintés.' },
]

export const MODELE_ENTETE_DEFAUT: ModeleEntete = 'CLASSIQUE'
