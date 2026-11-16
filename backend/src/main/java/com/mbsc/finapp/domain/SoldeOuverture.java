package com.mbsc.finapp.domain;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Soldes d'ouverture (reprise des à-nouveaux) — règle posée par l'utilisateur le 13/11/2026 : « dans le journal
 * comptable, toute opération du 1er janvier doit être considérée comme solde d'ouverture », et son libellé le dit
 * (« Capital souscrit, non appelé - Solde d'ouverture »).
 *
 * <p>En SYSCOHADA l'exercice coïncide avec l'année civile : le 1er janvier est le jour d'ouverture, et la balance
 * générale présente pour chaque compte son solde à l'ouverture de l'exercice, distinct des mouvements de la période.
 * Seuls les comptes de bilan (classes 1 à 5) ont un solde d'ouverture : les comptes de gestion (classes 6, 7 et 8)
 * s'ouvrent à zéro. Une pièce du 1er janvier qui porte une charge ou un produit reste donc un mouvement — la classer
 * en ouverture la retirerait du résultat de l'exercice.</p>
 *
 * <p>La règle vaut pour les pièces du journal, importées ou saisies. Les pièces qu'une opération génère (vente,
 * stock, caisse, extourne d'ouverture d'une régularisation...) ne passent pas par elle : ce sont des mouvements.</p>
 */
public final class SoldeOuverture {

    /** Mention portée par le libellé d'une écriture d'ouverture, tel qu'il se lit au journal. */
    public static final String MENTION = "Solde d'ouverture";

    private SoldeOuverture() {
    }

    /** Le 1er janvier : jour d'ouverture de l'exercice. */
    public static boolean estJourOuverture(LocalDate date) {
        return date != null && date.getMonthValue() == 1 && date.getDayOfMonth() == 1;
    }

    /** Compte de bilan : classes 1 à 5 du plan SYSCOHADA (la classe est le premier chiffre du numéro). */
    public static boolean estCompteDeBilan(String numero) {
        if (numero == null || numero.isBlank()) {
            return false;
        }
        char classe = numero.strip().charAt(0);
        return classe >= '1' && classe <= '5';
    }

    /**
     * Vrai si une pièce du journal datée de {@code date} et portant ces comptes est un solde d'ouverture : elle est
     * du 1er janvier et ne mouvemente que des comptes de bilan.
     */
    public static boolean concerne(LocalDate date, Collection<String> numerosComptes) {
        return estJourOuverture(date) && numerosComptes != null && !numerosComptes.isEmpty()
            && numerosComptes.stream().allMatch(SoldeOuverture::estCompteDeBilan);
    }

    /**
     * Libellé tel qu'il se lit au journal : celui d'une écriture d'ouverture porte la mention « - Solde d'ouverture ».
     * La mention est ajoutée à l'affichage, jamais en base : le libellé d'une pièce comptabilisée est intangible
     * (V135), alors que le marqueur d'ouverture, lui, peut se poser ou se retirer. Sans effet si le libellé la porte
     * déjà.
     */
    public static String libelle(String libelle, boolean soldeOuverture) {
        if (!soldeOuverture) {
            return libelle;
        }
        if (libelle == null || libelle.isBlank()) {
            return MENTION;
        }
        String propre = libelle.strip();
        String compare = propre.toLowerCase().replace('’', '\'');
        if (compare.endsWith(MENTION.toLowerCase())) {
            return propre;
        }
        return propre + " - " + MENTION;
    }

    /** Libellé d'une écriture, avec la mention si sa pièce est un solde d'ouverture. */
    public static String libelle(EcritureGrandLivre ecriture) {
        PieceComptable piece = ecriture.getPiece();
        return libelle(ecriture.getLibelle(), piece != null && piece.isSoldeOuverture());
    }
}
