/**
 * Modèles de papier à en-tête proposés à l'administrateur (écran Paramètres). Le modèle choisi habille
 * tous les documents : PDF générés par le serveur (papier à en-tête, ordre de mission, budget) et
 * impressions depuis le navigateur (attribut data-entete posé sur <html>, voir stores/parametres.ts et
 * assets/styles/classroom.scss). Mêmes valeurs que l'énumération ModeleEntete du backend.
 */
export type ModeleEntete = 'CLASSIQUE' | 'BANDEAU' | 'EPURE' | 'CENTRE' | 'LATERAL' | 'ENCADRE'

export const MODELES_ENTETE: { value: ModeleEntete, titre: string, description: string }[] = [
  { value: 'CLASSIQUE', titre: 'Classique', description: 'Logo à gauche, bandeau incliné à droite avec RCCM, ID. Nat et NIF.' },
  { value: 'BANDEAU', titre: 'Bandeau', description: 'Bandeau en dégradé de la couleur du thème, logo sur tuile blanche.' },
  { value: 'EPURE', titre: 'Épuré', description: 'Minimaliste : texte noir, un trait d’accent, identifiants en colonne.' },
  { value: 'CENTRE', titre: 'Institutionnel', description: 'Tout centré, nom espacé et ornement à losange : pour les courriers officiels.' },
  { value: 'LATERAL', titre: 'Latéral', description: 'Rail de couleur sur le bord gauche de chaque page, nom de l’entreprise en vertical.' },
  { value: 'ENCADRE', titre: 'Encadré', description: 'En-tête et pied en cartes arrondies, identifiants en pastilles.' },
]

export const MODELE_ENTETE_DEFAUT: ModeleEntete = 'CLASSIQUE'
