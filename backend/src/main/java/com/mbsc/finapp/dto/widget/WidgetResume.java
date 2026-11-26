package com.mbsc.finapp.dto.widget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Résumé lu par les widgets de l'écran d'accueil Android : les notes de frais qui attendent le compte (selon ses rôles)
 * et ses propres notes en cours ; la trésorerie si l'administrateur l'a activée et que le rôle la consulte.
 */
public record WidgetResume(
    String utilisateur,
    Instant genereLe,
    Notes notes,
    /** null : widget Trésorerie non activé par le compte (Mon profil), ou rôle sans widget Trésorerie. */
    Tresorerie tresorerie,
    /** Pourquoi la trésorerie n'est pas affichée (null si elle l'est). */
    String tresorerieMessage,
    /** Notifications non lues du compte : pastille rouge sur l'icône de l'application. */
    long notificationsNonLues
) {
    /**
     * @param aTraiter     notes qui attendent une action du compte (vérifier, valider, transmettre, payer…)
     * @param mesEnCours   notes créées par le compte, ni payées ni annulées
     * @param mesRejetees  parmi elles, celles rejetées par le DA
     * @param lignes       quelques notes, celles à traiter d'abord
     */
    public record Notes(int aTraiter, int mesEnCours, int mesRejetees, List<Ligne> lignes) {}

    /**
     * @param etat   ce qu'il faut en faire (« À vérifier », « À payer ») ou où elle en est (« Chez le DA »)
     * @param statut étape du circuit (StatutNote) : le widget en dessine le pipeline DFIN · DA · Caisse · Payée
     */
    public record Ligne(Long id, String reference, String objet, BigDecimal montant, String devise, String etat,
                        boolean aTraiter, String statut) {}

    /**
     * Mini tableau de bord : soldes en devise de base (USD) par canal, et mouvements du jour (entrées, sorties) sur la
     * caisse, les banques et le mobile money.
     */
    public record Tresorerie(String devise, BigDecimal total, List<Canal> canaux, BigDecimal entreesJour,
                             BigDecimal sortiesJour,
                             /** Résultat net de l'exercice en cours (produits − charges) : DFIN, DA, Admin seulement, sinon null. */
                             BigDecimal resultatNet,
                             /** Libellé du résultat (« Résultat net 2026 ») ; null avec resultatNet. */
                             String resultatLibelle) {
        public Tresorerie(String devise, BigDecimal total, List<Canal> canaux, BigDecimal entreesJour, BigDecimal sortiesJour) {
            this(devise, total, canaux, entreesJour, sortiesJour, null, null);
        }

        public Tresorerie avecResultat(BigDecimal resultat, String libelle) {
            return new Tresorerie(devise, total, canaux, entreesJour, sortiesJour, resultat, libelle);
        }
    }

    public record Canal(String nom, BigDecimal solde) {}
}
