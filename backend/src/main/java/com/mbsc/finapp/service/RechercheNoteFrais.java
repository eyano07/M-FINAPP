package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.NoteFrais;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Recherche textuelle dans la liste des notes de frais : par reference, par
 * libelle (objet) de la note et par compte impute sur l'une de ses lignes.
 *
 * <p>Les trois criteres se combinent (ET) ; un critere vide est ignore. La
 * comparaison ignore la casse et les accents (« deplacement » retrouve
 * « Déplacement »). La reference et le libelle se cherchent par fragment ; le
 * compte par son nom (fragment) ou par le debut de son numero (« 604 »
 * retrouve 6041, 6042...).</p>
 *
 * <p>Les textes saisis sont normalises une fois a la construction : les
 * champs de ce record sont donc deja en minuscules sans accents, ou
 * {@code null} quand le critere est absent.</p>
 */
record RechercheNoteFrais(String reference, String libelle, String compte) {

    /** Construit la recherche a partir des saisies brutes ({@code null} ou blanc = critere absent). */
    static RechercheNoteFrais de(String reference, String libelle, String compte) {
        return new RechercheNoteFrais(normaliser(reference), normaliser(libelle), normaliser(compte));
    }

    boolean estVide() {
        return reference == null && libelle == null && compte == null;
    }

    /** Vrai si la note satisfait tous les criteres renseignes. */
    boolean correspond(NoteFrais note) {
        return contient(note.getReference(), reference)
            && contient(note.getObjet(), libelle)
            && (compte == null
                || note.getLignes().stream().anyMatch(l -> compteCorrespond(l.getCompteImputation())));
    }

    /** Les lignes sans compte (imputation par defaut au paiement) ne correspondent a aucune recherche de compte. */
    private boolean compteCorrespond(CompteOHADA c) {
        if (c == null) {
            return false;
        }
        String numero = normaliser(c.getNumero());
        return contient(c.getLibelle(), compte) || (numero != null && numero.startsWith(compte));
    }

    private static boolean contient(String texte, String recherche) {
        if (recherche == null) {
            return true;
        }
        String normalise = normaliser(texte);
        return normalise != null && normalise.contains(recherche);
    }

    /** Minuscules sans accents, espaces de tete et de queue retires ; {@code null} si le texte est vide. */
    static String normaliser(String texte) {
        if (texte == null) {
            return null;
        }
        String sansAccents = Normalizer.normalize(texte.strip(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        return sansAccents.isEmpty() ? null : sansAccents;
    }
}
