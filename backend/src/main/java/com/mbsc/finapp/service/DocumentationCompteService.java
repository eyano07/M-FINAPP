package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Fiche de documentation des comptes créés par l'entreprise ou par l'application.
 *
 * <p>Les comptes du référentiel SYSCOHADA portent leur fiche officielle (contenu,
 * commentaires, fonctionnement, exclusions, contrôle — migration V23). Les comptes
 * ajoutés ensuite n'en avaient aucune : ni ceux saisis dans l'écran Plan comptable,
 * ni ceux que l'application ouvre seule (comptes propres à chaque article du
 * restaurant, comptes des établissements de trésorerie, comptes manquants d'un
 * journal importé). Chaque création passe désormais par ce service, qui rédige une
 * fiche dans les mêmes rubriques :</p>
 * <ul>
 *   <li><b>contenu</b> : ce que le compte enregistre, selon son rôle ;</li>
 *   <li><b>commentaires</b> : son origine — quand, par qui, à partir de quoi ;</li>
 *   <li><b>fonctionnement</b>, <b>exclusions</b>, <b>contrôle</b> : règles propres au
 *       rôle du compte, ou reprises de la fiche du compte parent documenté le plus
 *       proche (extrait, avec renvoi vers la fiche complète).</li>
 * </ul>
 *
 * <p>Une fiche existante n'est jamais écrasée.</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentationCompteService {

    private static final Logger log = LoggerFactory.getLogger(DocumentationCompteService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int LONGUEUR_EXTRAIT = 600;
    private static final String MENTION_AUTOMATIQUE = "Fiche rédigée automatiquement par l'application à la création du compte.";

    /** Rôle d'un compte ouvert pour un article du restaurant (voir RestaurantService.genererCompteDedie). */
    public enum RoleCompteArticle { STOCK, VARIATION, PRODUIT, ACHAT }

    private final CompteOHADARepository compteRepository;
    private final CurrentUserProvider currentUser;

    // ------------------------------------------------------------------
    // Points d'entrée : un par mode de création d'un compte
    // ------------------------------------------------------------------

    /** Compte ajouté depuis l'écran Plan comptable ; {@code precision} : note facultative de l'utilisateur. */
    public void documenterSaisieManuelle(CompteOHADA compte, String precision) {
        if (compte.estCommente()) {
            return;
        }
        documenterSousCompte(compte, "ouvert par l'entreprise", precision,
            "Compte créé manuellement le " + aujourdhui() + parQui() + " depuis l'écran Plan comptable.");
    }

    /** Compte créé par l'import d'un journal comptable dont une ligne utilisait un numéro absent du plan. */
    public void documenterImport(CompteOHADA compte) {
        if (compte.estCommente()) {
            return;
        }
        documenterSousCompte(compte, "créé à l'import du journal comptable", null,
            "Compte créé automatiquement le " + aujourdhui() + parQui()
            + " lors de l'import du journal comptable : le fichier utilisait ce numéro, absent du plan. "
            + "Son intitulé vient du fichier (à défaut, de son compte parent) : à vérifier dans l'écran Plan comptable.");
    }

    /** Compte de trésorerie ouvert avec un établissement (banque ou mobile money). */
    public void documenterEtablissement(CompteOHADA compte, String nom, boolean banque) {
        if (compte.estCommente()) {
            return;
        }
        documenterTresorerie(compte, nom, banque,
            "Compte créé automatiquement le " + aujourdhui() + parQui()
            + " lors de l'ajout de l'établissement « " + nom + " » (Administration → Établissements).");
    }

    /**
     * Compte propre à un article du restaurant, ouvert sous la racine {@code racine}.
     *
     * @param role    stock, variation de stock, ventes ou achats de l'article
     * @param type    PLAT, BOISSON ou PROVISION
     */
    public void documenterCompteArticle(CompteOHADA compte, CompteOHADA racine, RoleCompteArticle role,
                                        TypeArticle type, String code, String libelleArticle) {
        if (compte.estCommente()) {
            return;
        }
        String article = articleNomme(type, libelleArticle);   // « de la boisson « Primus » », « du plat « … » »
        String codeTxt = StringUtils.hasText(code) ? " (code " + code + ")" : "";
        CompteOHADA ancetre = ancetreCommente(compte);

        switch (role) {
            case STOCK -> {
                compte.setContenu(switch (type) {
                    case PLAT -> "Compte de stock propre au plat « " + libelleArticle + " »" + codeTxt
                        + " : valeur des portions produites en cuisine et pas encore vendues, à leur coût de production (fiche technique).";
                    case PROVISION -> "Compte de stock propre à la provision « " + libelleArticle + " »" + codeTxt
                        + " : valeur des quantités en stock, au coût moyen pondéré, transport et manutention compris.";
                    default -> "Compte de stock propre à la boisson « " + libelleArticle + " »" + codeTxt
                        + " : valeur des bouteilles en stock, au coût moyen pondéré, transport et manutention compris.";
                } + "\nInventaire permanent : le stock est suivi en continu, à chaque entrée et à chaque sortie.");
                compte.setFonctionnement(switch (type) {
                    case PLAT -> "Débité à chaque production, pour le coût des provisions consommées (contrepartie : variation des stocks de produits finis 736x).\n"
                        + "Crédité à chaque sortie (vente, perte, repas offert) au coût de production moyen.";
                    case PROVISION -> "Débité à l'entrée en stock, au paiement de la note d'achat de provisions (contrepartie : variation des stocks 6032x), frais d'approche compris.\n"
                        + "Crédité à chaque sortie (production de plats, péremption, perte) au coût moyen pondéré.";
                    default -> "Débité à l'entrée en stock, au paiement de la note d'achat de boissons (contrepartie : variation des stocks 6031x), frais d'approche compris.\n"
                        + "Crédité à chaque sortie (vente, casse, péremption, bouteille offerte) au coût moyen pondéré.";
                } + "\nSon solde, toujours débiteur ou nul, est la valeur du stock de l'article.");
                compte.setControle("Le solde doit égaler la valeur du stock " + article
                    + " (pages de stock consolidé du restaurant) et être confirmé par l'inventaire physique.");
            }
            case VARIATION -> {
                compte.setContenu(switch (type) {
                    case PLAT -> "Variation du stock du plat « " + libelleArticle + " »" + codeTxt
                        + " : production stockée (portions entrées en stock) et déstockée (portions sorties).";
                    case PROVISION -> "Variation du stock de la provision « " + libelleArticle + " »" + codeTxt
                        + " : avec son compte d'achat 6021x, il donne la consommation réelle de la provision sur la période.";
                    default -> "Variation du stock de la boisson « " + libelleArticle + " »" + codeTxt
                        + " : avec son compte d'achat 6011x, il forme le coût d'achat des bouteilles vendues ou perdues sur la période.";
                });
                compte.setFonctionnement(switch (type) {
                    case PLAT -> "Crédité à chaque production (portions entrées en stock, contrepartie 361x).\n"
                        + "Débité à chaque sortie (vente, perte, repas offert) au coût de production moyen.";
                    case PROVISION -> "Crédité à l'entrée en stock (contrepartie : stock 331x).\n"
                        + "Débité à chaque sortie (production de plats, péremption, perte) au coût moyen pondéré.";
                    default -> "Crédité à l'entrée en stock (contrepartie : stock 3111x), pour le coût d'achat frais d'approche compris.\n"
                        + "Débité à chaque sortie (vente, casse, péremption, cadeau) au coût moyen pondéré.";
                });
                compte.setControle("Rapprocher des entrées et sorties de stock " + article
                    + " sur la période (mouvements de stock et grand livre du stock).");
            }
            case PRODUIT -> {
                compte.setContenu("Chiffre d'affaires des ventes " + article + codeTxt + ", hors taxes.");
                compte.setFonctionnement("Crédité du prix de vente hors taxes à la validation de chaque vente "
                    + "(contrepartie : caisse, banque, mobile money ou client) ; la TVA facturée va en 4431.\n"
                    + "Débité seulement par l'extourne d'une vente annulée.");
                compte.setControle("Rapprocher des tickets et factures de vente de l'article et de l'écran Analyses des ventes.");
            }
            case ACHAT -> {
                compte.setContenu(switch (type) {
                    case PLAT -> "Compte d'achat créé avec le plat « " + libelleArticle + " »" + codeTxt
                        + " par symétrie avec les boissons. Un plat se produit en cuisine à partir des provisions "
                        + "(fiche technique) : ce compte reste en principe sans mouvement.";
                    case PROVISION -> "Achats de la provision « " + libelleArticle + " »" + codeTxt
                        + ", hors taxes et hors frais d'approche (ces frais vont en 6025).";
                    default -> "Achats de la boisson « " + libelleArticle + " »" + codeTxt
                        + ", hors taxes et hors frais d'approche (ces frais vont en 6015).";
                });
                compte.setFonctionnement("Débité du prix d'achat hors taxes au paiement de la note d'achat "
                    + "(contrepartie : caisse, banque ou mobile money).\n"
                    + "Crédité seulement par une extourne ou par la régularisation d'un écart de change (656 / 756).");
                compte.setControle("Chaque débit correspond à une note d'achat payée (référence NF-…) "
                    + "accompagnée de la facture du fournisseur en pièce jointe.");
            }
        }
        compte.setExclusions(repris("Mêmes exclusions que pour le compte", ancetre, ancetre == null ? null : ancetre.getExclusions()));
        compte.setCommentaires(String.join("\n",
            "Compte créé automatiquement le " + aujourdhui() + parQui() + " lors de la création " + article + codeTxt
                + ", sous la racine " + racine.getNumero() + " « " + racine.getLibelle() + " ».",
            "Chaque article du restaurant reçoit ses propres comptes (stock, variation de stock, achats"
                + (type == TypeArticle.PROVISION ? "" : ", ventes")
                + ") pour être suivi séparément dans le grand livre.",
            MENTION_AUTOMATIQUE));
    }

    /**
     * Rattrapage : rédige la fiche des comptes ajoutés avant ce service — comptes manuels
     * sans aucune rubrique, et comptes de trésorerie des établissements (y compris ceux
     * installés d'office avec l'application). Sans effet une fois tous documentés.
     *
     * @return nombre de comptes documentés
     */
    @Transactional
    public int documenterComptesExistants() {
        int n = 0;
        for (CompteOHADA c : compteRepository.findAllByOrderByNumeroAsc()) {
            if (c.estCommente()) {
                continue;
            }
            String libelle = c.getLibelle() == null ? "" : c.getLibelle();
            boolean tresorerie = c.getClasse() != null && c.getClasse() == 5;
            boolean banque = tresorerie && libelle.startsWith("Banque — ");
            boolean mobileMoney = tresorerie && libelle.startsWith("Mobile Money — ");
            // Comptes de trésorerie des établissements : ceux créés avec un établissement
            // (manuels) et ceux installés d'office avec l'application (V18 : Equity Bank,
            // Rawbank, Airtel...), absents du SYSCOHADA officiel malgré leur marquage.
            if (banque || mobileMoney) {
                String nom = libelle.substring(libelle.indexOf("— ") + 2);
                documenterTresorerie(c, nom, banque, c.isManuel()
                    ? "Compte de trésorerie ouvert avec l'établissement « " + nom + " », avant la rédaction "
                      + "automatique des fiches (" + aujourdhui() + ") : sa date et son auteur de création ne sont pas connus."
                    : "Compte de trésorerie installé avec l'application (plan comptable de départ) pour "
                      + "l'établissement « " + nom + " » ; il ne fait pas partie du référentiel SYSCOHADA officiel.");
            } else if (c.isManuel()) {
                documenterSousCompte(c, "ouvert par l'entreprise", null,
                    "Compte ajouté au plan par l'entreprise avant la rédaction automatique des fiches "
                    + "(" + aujourdhui() + ") : sa date et son auteur de création ne sont pas connus.");
            } else {
                continue;   // subdivision du référentiel officiel : hors champ
            }
            n++;
        }
        if (n > 0) {
            log.info("Fiches de documentation rédigées pour {} compte(s) existant(s)", n);
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Rédaction
    // ------------------------------------------------------------------

    /** Sous-compte de l'entreprise (saisie ou import) : rôle déduit de son parent, règles reprises de la fiche officielle. */
    private void documenterSousCompte(CompteOHADA compte, String commentOuvert, String precision, String origine) {
        CompteOHADA parent = compte.getParent();
        CompteOHADA ancetre = ancetreCommente(compte);

        StringBuilder contenu = new StringBuilder();
        if (parent != null) {
            contenu.append("Sous-compte de ").append(parent.getNumero()).append(" « ").append(parent.getLibelle())
                .append(" », ").append(commentOuvert)
                .append(" pour suivre à part les opérations que désigne son intitulé. Il enregistre la même nature ")
                .append("d'opérations que son compte parent, limitée à cet objet.");
        } else {
            contenu.append("Compte ").append(commentOuvert)
                .append(" pour suivre à part les opérations que désigne son intitulé.");
        }
        if (StringUtils.hasText(precision)) {
            contenu.append("\nPrécision de l'entreprise : ").append(precision.trim());
        }
        if (ancetre != null && StringUtils.hasText(ancetre.getContenu())) {
            contenu.append('\n').append(repris("Rappel du compte", ancetre, ancetre.getContenu()));
        }
        compte.setContenu(contenu.toString());

        String regleParent = parent == null ? null
            : "Fonctionne comme son compte parent " + parent.getNumero()
              + " : débité et crédité selon les mêmes règles, pour les seules opérations de cet objet.";
        String fonctionnement = joindre(regleParent,
            repris("Rappel du compte", ancetre, ancetre == null ? null : ancetre.getFonctionnement()));
        compte.setFonctionnement(fonctionnement);
        compte.setExclusions(repris("Mêmes exclusions que pour le compte", ancetre, ancetre == null ? null : ancetre.getExclusions()));
        compte.setControle(joindre(
            "Pièces justificatives de chaque opération imputée (facture, note de frais, reçu, relevé).",
            repris("Comme pour le compte", ancetre, ancetre == null ? null : ancetre.getControle())));
        compte.setCommentaires(joindre(origine,
            compte.getType() != null && parent != null
                ? "Il hérite du type (" + compte.getType() + ") et de la classe (" + compte.getClasse()
                  + ") de son compte parent " + parent.getNumero() + "."
                : null,
            MENTION_AUTOMATIQUE));
    }

    private void documenterTresorerie(CompteOHADA compte, String nom, boolean banque, String origine) {
        String nature = banque ? "banque" : "mobile money";
        compte.setContenu("Compte de trésorerie propre à l'établissement « " + nom + " » (" + nature + ") : "
            + "il enregistre les mouvements de fonds de ce compte — encaissements, paiements de notes de frais, "
            + "virements, frais de l'établissement.");
        compte.setFonctionnement("Débité des entrées de fonds (encaissements, virements reçus, approvisionnements).\n"
            + "Crédité des sorties (paiements de notes de frais, virements émis, retraits, frais).\n"
            + "Son solde, normalement débiteur, est l'avoir disponible sur ce compte. Les opérations y sont passées "
            + "par l'écran " + (banque ? "Banque" : "Mobile Money") + ", jamais depuis l'écran des pièces comptables.");
        compte.setExclusions("Ne pas y passer les opérations d'un autre établissement, ni celles de la caisse (571).");
        compte.setControle("Rapprocher chaque mois le solde du relevé de l'établissement (état de rapprochement) ; "
            + "chaque mouvement correspond à une pièce (note de frais, avis de virement, relevé).");
        compte.setCommentaires(joindre(origine, MENTION_AUTOMATIQUE));
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Compte documenté le plus proche en remontant les parents (le compte lui-même exclu). */
    private CompteOHADA ancetreCommente(CompteOHADA compte) {
        CompteOHADA a = compte.getParent();
        for (int garde = 0; a != null && garde < 10; garde++) {
            if (a.estCommente()) {
                return a;
            }
            a = a.getParent();
        }
        return null;
    }

    /**
     * « Rappel du compte 604 « … » : » puis l'extrait de sa fiche, ligne par ligne (les puces
     * du référentiel restent des puces à l'écran), et un renvoi vers la fiche complète si
     * l'extrait est coupé. {@code null} sans texte à reprendre.
     */
    private String repris(String introduction, CompteOHADA ancetre, String texte) {
        if (ancetre == null || !StringUtils.hasText(texte)) {
            return null;
        }
        String ex = extrait(texte);
        String renvoi = ex.endsWith("…") ? "\n(Texte complet : fiche du compte " + ancetre.getNumero() + ".)" : "";
        return introduction + " " + ancetre.getNumero() + " « " + ancetre.getLibelle() + " » :\n" + ex + renvoi;
    }

    /**
     * Début d'un texte du référentiel, en gardant ses lignes : lignes entières tant que
     * l'extrait reste sous {@link #LONGUEUR_EXTRAIT} caractères ; une première ligne trop
     * longue est coupée en fin de phrase. Se termine par « … » si le texte est coupé.
     */
    private static String extrait(String texte) {
        StringBuilder sb = new StringBuilder();
        for (String ligne : texte.trim().split("\\s*\\n\\s*")) {
            if (ligne.isBlank()) {
                continue;
            }
            if (sb.isEmpty() && ligne.length() > LONGUEUR_EXTRAIT) {
                String debut = ligne.substring(0, LONGUEUR_EXTRAIT);
                int fin = debut.lastIndexOf(". ");
                return (fin > LONGUEUR_EXTRAIT / 3 ? debut.substring(0, fin + 1) : debut.trim()) + " …";
            }
            if (!sb.isEmpty() && sb.length() + 1 + ligne.length() > LONGUEUR_EXTRAIT) {
                return sb.append("\n…").toString();
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(ligne);
        }
        return sb.toString();
    }

    private static String joindre(String... parties) {
        StringBuilder sb = new StringBuilder();
        for (String p : parties) {
            if (StringUtils.hasText(p)) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(p);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String articleNomme(TypeArticle type, String libelle) {
        return switch (type) {
            case PLAT -> "du plat « " + libelle + " »";
            case PROVISION -> "de la provision « " + libelle + " »";
            default -> "de la boisson « " + libelle + " »";
        };
    }

    private static String aujourdhui() {
        return LocalDate.now().format(DATE);
    }

    /** « par Prénom Nom » si un utilisateur est connecté, sinon rien (traitement automatique). */
    private String parQui() {
        try {
            User u = currentUser.requireUser();
            String nom = (StringUtils.hasText(u.getPrenom()) ? u.getPrenom() + " " : "")
                + (StringUtils.hasText(u.getNom()) ? u.getNom() : "");
            return " par " + (StringUtils.hasText(nom) ? nom.trim() : u.getEmail());
        } catch (IllegalStateException e) {
            return "";
        }
    }
}
