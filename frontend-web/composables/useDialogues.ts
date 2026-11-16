import { shallowRef } from 'vue'

// Fenêtres de confirmation et de saisie de l'application.
//
// Elles remplacent confirm() et prompt() du navigateur — la boîte grise « localhost indique… » —, dont ni l'apparence
// ni le texte des boutons ne se règlent. Une seule boîte (components/commun/BoiteDialogue.vue, montée une fois dans
// app.vue) affiche la demande en cours ; une page l'appelle comme l'ancien confirm(), avec await :
//
//   const dialogues = useDialogues()
//   if (!(await dialogues.confirmer({ titre: 'Supprimer ce brouillon ?', bouton: 'Supprimer', nature: 'danger' }))) return
//   const motif = await dialogues.saisir({ titre: 'Rouvrir le rapprochement', libelle: 'Motif (obligatoire)', obligatoire: true })
//   if (motif === null) return   // abandon

/** Gravité de l'action : règle l'icône, la couleur et le bouton de validation. */
export type NatureDialogue = 'danger' | 'attention' | 'info'

export interface OptionsDialogue {
  /** La question, courte : « Annuler cette pièce ? ». */
  titre: string
  /** Ses conséquences, en une phrase ou plusieurs paragraphes. */
  message?: string | string[]
  /** Texte du bouton de validation : le verbe de l'action (« Supprimer », « Annuler la pièce »), jamais « OK ». */
  bouton?: string
  /** Texte du bouton d'abandon (« Annuler » par défaut ; « Retour » quand l'action elle-même s'appelle annuler). */
  abandon?: string
  /**
   * `danger` : suppression, annulation, retrait — rouge, le focus reste sur l'abandon ;
   * `attention` : perte de modifications, action lourde mais réversible — ambre, focus sur l'abandon ;
   * `info` (défaut) : simple validation — couleur de l'application, focus sur le bouton de validation.
   */
  nature?: NatureDialogue
  /** Icône mdi ; celle de la nature par défaut. */
  icone?: string
}

export interface OptionsSaisie extends OptionsDialogue {
  /** Libellé du champ (« Motif », « Code à 6 chiffres »…). */
  libelle: string
  /** Le bouton de validation reste inactif tant que le champ est vide. */
  obligatoire?: boolean
  /** Zone de texte de plusieurs lignes (défaut) ou champ d'une seule ligne (Entrée valide). */
  multiligne?: boolean
  placeholder?: string
  maxlength?: number
  inputmode?: 'text' | 'numeric' | 'decimal'
}

export interface DemandeDialogue {
  options: OptionsDialogue
  saisie: OptionsSaisie | null
  resoudre: (valeur: boolean | string | null) => void
}

/** Demande affichée par la boîte (une seule à la fois) ; lue par components/commun/BoiteDialogue.vue. */
export const demandeDialogue = shallowRef<DemandeDialogue | null>(null)

function ouvrir(demande: DemandeDialogue) {
  // Une demande encore ouverte est abandonnée : sa promesse ne reste jamais en suspens.
  demandeDialogue.value?.resoudre(null)
  demandeDialogue.value = demande
}

export function useDialogues() {
  /** Pose une question ; `true` si l'utilisateur valide, `false` s'il abandonne (bouton, Échap ou clic à côté). */
  function confirmer(options: OptionsDialogue): Promise<boolean> {
    return new Promise((resolve) => {
      ouvrir({ options, saisie: null, resoudre: valeur => resolve(valeur === true) })
    })
  }

  /** Demande un texte ; le texte saisi (sans espaces autour), ou `null` si l'utilisateur abandonne. */
  function saisir(options: OptionsSaisie): Promise<string | null> {
    return new Promise((resolve) => {
      ouvrir({ options, saisie: options, resoudre: valeur => resolve(typeof valeur === 'string' ? valeur : null) })
    })
  }

  return { confirmer, saisir }
}
