package com.mbsc.finapp.domain.enums;

/**
 * Action faite sur la copie en ligne d'un serveur local, mise en attente puis appliquee par le serveur local (V133).
 * Aucune ne touche a la comptabilite.
 */
public enum TypeActionDistante {
    /** DFIN : verification d'une note soumise. */
    VERIFIER("Vérifier"),
    /** DFIN : transmission a la caisse d'une note validee. */
    TRANSMETTRE("Transmettre à la caisse"),
    /** DA : validation d'une note verifiee. */
    VALIDER("Valider"),
    /** DA : rejet (avec motif). */
    REJETER("Rejeter"),
    /** DA : observation sur la note. */
    OBSERVATION("Observation"),
    /** DA : priorite de paiement. */
    PRIORITE("Priorité"),
    /** Emetteur : soumission d'un brouillon. */
    SOUMETTRE("Soumettre"),
    /** Creation d'une note de frais (avec ses pieces jointes, soumise ou non). */
    CREER_NOTE("Nouvelle note"),
    /** ADMIN : activation ou desactivation d'un compte (deja effective sur la copie en ligne). */
    ACTIVER_UTILISATEUR("Activation d'un compte");

    private final String libelle;

    TypeActionDistante(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }

    /** Action sur une note existante. */
    public boolean surUneNote() {
        return this != CREER_NOTE && this != ACTIVER_UTILISATEUR;
    }
}
