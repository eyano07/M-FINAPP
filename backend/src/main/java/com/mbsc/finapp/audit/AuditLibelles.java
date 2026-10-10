package com.mbsc.finapp.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Libellé en clair d'une action du journal d'audit : « Création d'une vente », « Import d'un journal comptable —
 * remplace toutes les écritures existantes ». Le journal ne garde que des codes techniques (module « admin/import »,
 * opération « CREER ») ; ce libellé dit ce qui a été fait, sans jamais révéler le contenu saisi.
 *
 * <p>Le libellé est déduit à la lecture de la méthode HTTP, du chemin et des paramètres d'adresse déjà journalisés :
 * il s'applique donc aussi aux lignes écrites avant son introduction. Une route absente du catalogue reçoit un libellé
 * générique construit sur son chemin. Pour ajouter une action : une ligne dans le bloc {@code static} ci-dessous.</p>
 */
public final class AuditLibelles {

    private AuditLibelles() {}

    /** @param variables nombre de segments variables du chemin : moins il y en a, plus la règle est précise */
    private record Regle(String methode, Pattern chemin, String modele, int variables) {}

    private static final List<Regle> REGLES = new ArrayList<>();

    /** Noms affichés des modules (écran Administration › Modules). */
    private static final Map<String, String> MODULES = Map.ofEntries(
        Map.entry("CAISSE", "Caisse"), Map.entry("BANQUE", "Banque"), Map.entry("MOBILE_MONEY", "Mobile Money"),
        Map.entry("COMPTABILITE", "Comptabilité"), Map.entry("RAPPROCHEMENT", "Rapprochement bancaire"),
        Map.entry("BUDGET", "Budget"), Map.entry("FACTURATION_NORMALISEE", "Facture normalisée (DGI)"),
        Map.entry("VENTES", "Ventes"), Map.entry("LOGISTIQUE", "Logistique"), Map.entry("TRANSPORT", "Transport"),
        Map.entry("PATRIMOINE", "Patrimoine"), Map.entry("RESTAURANT", "Restaurant"), Map.entry("DRH", "DRH"),
        Map.entry("DRH_PERSONNEL", "DRH › Personnel"), Map.entry("DRH_PRESENCES", "DRH › Présences"),
        Map.entry("DRH_PAIE", "DRH › Paie"), Map.entry("DRH_MISSIONS", "DRH › Missions & Terrain"));

    /** Ajoute une règle : {@code {}} dans le chemin désigne un segment variable, {@code {0}}, {@code {1}}... le reprennent dans le libellé. */
    private static void r(String methode, String chemin, String modele) {
        int variables = chemin.split("\\{\\}", -1).length - 1;
        REGLES.add(new Regle(methode, Pattern.compile("^" + chemin.replace("{}", "([^/]+)") + "$"), modele, variables));
    }

    static {
        // --- Administration -------------------------------------------------------------------------------------
        r("POST", "/admin/audit/interroger", "Question posée à l'agent IA du journal d'audit");
        r("PUT", "/admin/facturation-normalisee", "Modification des paramètres de la facture normalisée (DGI)");
        r("DELETE", "/admin/facturation-normalisee/jeton", "Retrait du jeton de la facture normalisée (DGI)");
        r("POST", "/admin/facturation-normalisee/tester", "Test de la connexion à la facture normalisée (DGI)");
        r("PUT", "/admin/ia/{}", "Modification de la clé et des réglages de l'IA ({0})");
        r("DELETE", "/admin/ia/{}/cle", "Retrait de la clé de l'IA ({0})");
        r("POST", "/admin/ia/{}/tester", "Test de la clé de l'IA ({0})");
        r("POST", "/admin/import/journal/correction", "Génération d'un fichier de journal corrigé (rien n'est écrit)");
        r("POST", "/admin/import/journal/correction-auto", "Correction automatique d'un fichier de journal (comptes manquants créés)");
        r("PUT", "/admin/mail", "Modification des paramètres de messagerie (e-mails)");
        r("DELETE", "/admin/mail/mot-de-passe", "Retrait du mot de passe de la messagerie");
        r("POST", "/admin/mail/tester", "Envoi d'un e-mail de test");
        r("PUT", "/admin/modules/{}", "Activation ou désactivation du module « {0} »");
        r("PUT", "/admin/permissions/{}/{}", "Modification des droits du rôle {0} sur le module « {1} »");
        r("PUT", "/admin/roles/{}/libelle", "Modification du libellé affiché du rôle {0}");
        r("POST", "/admin/taux-change", "Enregistrement d'un taux de change");
        r("POST", "/admin/taux-tva", "Enregistrement d'un taux de TVA");
        r("PUT", "/admin/taux-tva/assujettissement", "Modification de l'assujettissement à la TVA");
        r("POST", "/admin/users", "Création d'un utilisateur");
        r("PUT", "/admin/users/{}", "Modification d'un utilisateur");
        r("PATCH", "/admin/users/{}/actif", "Activation ou désactivation d'un utilisateur");
        r("PUT", "/parametres", "Modification des informations de l'entreprise");
        r("POST", "/parametres/logo", "Remplacement du logo de l'entreprise");
        r("PUT", "/profil", "Modification du profil personnel");
        r("PUT", "/profil/mot-de-passe", "Changement du mot de passe personnel");
        r("POST", "/profil/photo", "Remplacement de la photo de profil");
        r("POST", "/ia/suggestion-compte", "Suggestion de compte comptable par l'IA");
        r("POST", "/sync/batch", "Synchronisation d'opérations du poste de caisse");

        // --- Trésorerie ------------------------------------------------------------------------------------------
        r("POST", "/etablissements", "Création d'une banque ou d'un opérateur mobile money");
        r("DELETE", "/etablissements/{}", "Suppression d'une banque ou d'un opérateur mobile money");
        r("PUT", "/etablissements/{}/devise", "Changement de la devise d'une banque ou d'un opérateur mobile money");
        r("POST", "/banque/transactions", "Enregistrement d'une opération de banque");
        r("POST", "/banque/notes/{}/payer", "Paiement d'une note de frais par la banque");
        r("POST", "/caisse/transactions", "Enregistrement d'une opération de caisse");
        r("POST", "/caisse/achats", "Achat de marchandises payé en caisse");
        r("POST", "/caisse/notes/{}/encaisser", "Encaissement d'une note en caisse");
        r("POST", "/caisse/notes/{}/payer", "Paiement d'une note de frais en caisse");
        r("POST", "/caisse/reglements-minerais", "Règlement de camions de minerai en caisse");
        r("POST", "/mobile-money/transactions", "Enregistrement d'une opération de mobile money");
        r("POST", "/mobile-money/notes/{}/payer", "Paiement d'une note de frais par mobile money");

        // --- Notes de frais --------------------------------------------------------------------------------------
        r("POST", "/notes-frais", "Création d'une note de frais");
        r("PUT", "/notes-frais/{}", "Modification d'une note de frais");
        r("POST", "/notes-frais/controle-budgetaire", "Contrôle budgétaire d'une note de frais (simulation)");
        r("PUT", "/notes-frais/parametres-priorite", "Modification des seuils de priorité des notes de frais");
        r("POST", "/notes-frais/reglement-camions-minerai", "Création d'une note de règlement de camions de minerai");
        r("POST", "/notes-frais/{}/soumettre", "Soumission d'une note de frais");
        r("POST", "/notes-frais/{}/verifier", "Vérification d'une note de frais");
        r("POST", "/notes-frais/{}/valider", "Validation d'une note de frais");
        r("POST", "/notes-frais/{}/rejeter", "Rejet d'une note de frais");
        r("POST", "/notes-frais/{}/transmettre", "Transmission d'une note de frais à la caisse");
        r("POST", "/notes-frais/{}/annuler", "Annulation d'une note de frais");
        r("POST", "/notes-frais/{}/priorite", "Définition de la priorité d'une note de frais");
        r("POST", "/notes-frais/{}/observation", "Ajout d'une observation sur une note de frais");
        r("PUT", "/notes-frais/{}/comptes", "Modification des comptes d'imputation d'une note de frais");
        r("PUT", "/notes-frais/{}/objet", "Modification de l'objet d'une note de frais");
        r("PUT", "/notes-frais/{}/justification-budget", "Justification d'un dépassement de budget sur une note de frais");
        r("POST", "/notes-frais/{}/pieces-jointes", "Ajout d'une pièce jointe à une note de frais");
        r("DELETE", "/notes-frais/{}/pieces-jointes/{}", "Suppression d'une pièce jointe d'une note de frais");

        // --- Budgets ---------------------------------------------------------------------------------------------
        r("POST", "/budgets", "Création d'un budget");
        r("PUT", "/budgets/{}", "Modification d'un budget");
        r("DELETE", "/budgets/{}", "Suppression d'un budget");
        r("POST", "/budgets/proposition", "Demande d'une proposition de budget à l'IA");
        r("POST", "/budgets/repartition", "Calcul d'une répartition de budget");
        r("POST", "/budgets/{}/soumettre", "Soumission d'un budget");
        r("POST", "/budgets/{}/approuver", "Approbation d'un budget");
        r("POST", "/budgets/{}/rejeter", "Rejet d'un budget");
        r("POST", "/budgets/{}/demarrer", "Démarrage d'un budget");
        r("POST", "/budgets/{}/cloturer", "Clôture d'un budget");
        r("POST", "/budgets/{}/reprendre", "Reprise d'un budget");
        r("POST", "/budgets/{}/reviser", "Révision d'un budget");

        // --- Comptabilité ----------------------------------------------------------------------------------------
        r("POST", "/comptabilite/pieces", "Saisie d'une pièce comptable");
        r("PUT", "/comptabilite/pieces/{}", "Modification d'une pièce comptable (brouillon)");
        r("DELETE", "/comptabilite/pieces/{}", "Suppression d'une pièce comptable (brouillon)");
        r("POST", "/comptabilite/pieces/{}/comptabiliser", "Comptabilisation d'une pièce comptable");
        r("POST", "/comptabilite/pieces/{}/annuler", "Annulation (extourne) d'une pièce comptable");
        r("PATCH", "/comptabilite/pieces/{}/solde-ouverture", "Marquage d'une pièce comptable comme solde d'ouverture");
        r("PUT", "/comptabilite/cloture", "Modification de la date de clôture comptable");
        r("POST", "/comptabilite/cloture/exercice", "Clôture de l'exercice comptable");
        r("POST", "/comptabilite/etats-financiers/analyse-ia", "Analyse des états financiers par l'IA");
        r("POST", "/comptabilite/provisions", "Constitution d'une provision");
        r("POST", "/comptabilite/provisions/{}/reprendre", "Reprise d'une provision");
        r("POST", "/comptabilite/tva/declarer", "Déclaration de TVA (arrêté de la période)");
        r("POST", "/comptes", "Ajout d'un compte au plan comptable");
        r("PUT", "/comptes/{}", "Modification d'un compte du plan comptable");
        r("DELETE", "/comptes/{}", "Suppression d'un compte du plan comptable");
        r("PATCH", "/comptes/{}/toggle-actif", "Activation ou désactivation d'un compte du plan comptable");
        r("POST", "/patrimoine/immobilisations", "Enregistrement d'une immobilisation");
        r("POST", "/patrimoine/immobilisations/{}/sortie", "Sortie d'une immobilisation (cession ou mise au rebut)");
        r("POST", "/patrimoine/amortissements/comptabiliser", "Comptabilisation des dotations aux amortissements");

        // --- Rapprochement bancaire ------------------------------------------------------------------------------
        r("POST", "/rapprochements", "Création d'un rapprochement bancaire");
        r("POST", "/rapprochements/extraction", "Lecture d'un relevé bancaire par l'IA");
        r("DELETE", "/rapprochements/{}", "Suppression d'un rapprochement bancaire");
        r("POST", "/rapprochements/{}/valider", "Validation d'un rapprochement bancaire");
        r("POST", "/rapprochements/{}/devalider", "Dévalidation d'un rapprochement bancaire");
        r("PUT", "/rapprochements/{}/soldes", "Modification des soldes d'un rapprochement bancaire");
        r("POST", "/rapprochements/{}/lignes", "Ajout d'une ligne de relevé à un rapprochement");
        r("DELETE", "/rapprochements/{}/lignes/{}", "Suppression d'une ligne de relevé d'un rapprochement");
        r("POST", "/rapprochements/{}/lignes/{}/regularisation", "Régularisation d'une ligne de relevé");
        r("POST", "/rapprochements/{}/pointage-automatique", "Pointage automatique d'un rapprochement bancaire");
        r("POST", "/rapprochements/{}/pointages", "Pointage d'une ligne de relevé avec une écriture");
        r("DELETE", "/rapprochements/{}/pointages/{}", "Annulation d'un pointage de rapprochement");

        // --- Ventes et clients -----------------------------------------------------------------------------------
        r("POST", "/ventes", "Création d'une vente");
        r("POST", "/ventes/{}/lignes", "Ajout d'une ligne à une vente");
        r("POST", "/ventes/{}/valider", "Validation d'une vente");
        r("POST", "/ventes/{}/annuler", "Annulation d'une vente");
        r("POST", "/ventes/{}/regler", "Règlement d'une vente à crédit");
        r("POST", "/ventes/{}/facture-normalisee/retransmettre", "Retransmission d'une facture normalisée (DGI)");
        r("POST", "/clients", "Création d'un client");
        r("PUT", "/clients/{}", "Modification d'un client");

        // --- Logistique et transport -----------------------------------------------------------------------------
        r("POST", "/logistique/articles", "Création d'un article de stock");
        r("PUT", "/logistique/articles/{}", "Modification d'un article de stock");
        r("POST", "/logistique/entrepots", "Création d'un entrepôt");
        r("PUT", "/logistique/entrepots/{}", "Modification d'un entrepôt");
        r("POST", "/logistique/mouvements", "Création d'un mouvement de stock");
        r("POST", "/logistique/mouvements/{}/valider", "Validation d'un mouvement de stock");
        r("POST", "/logistique/mouvements/{}/annuler", "Annulation d'un mouvement de stock");
        r("POST", "/logistique/minerais/camions", "Réception d'un camion de minerai");
        r("DELETE", "/logistique/minerais/camions/{}", "Suppression d'un camion de minerai");
        r("POST", "/logistique/minerais/camions/{}/charges", "Ajout d'une charge à un camion de minerai");
        r("POST", "/logistique/minerais/camions/{}/dupliquer", "Duplication d'un camion de minerai");
        r("DELETE", "/logistique/minerais/camions/charges/{}", "Suppression d'une charge d'un camion de minerai");
        r("DELETE", "/logistique/minerais/camions/modeles/{}", "Suppression d'un modèle de charges de camion");
        r("POST", "/transport/vehicules", "Création d'un véhicule");
        r("PUT", "/transport/vehicules/{}", "Modification d'un véhicule");
        r("POST", "/transport/trajets", "Création d'un trajet");
        r("PUT", "/transport/trajets/{}", "Modification d'un trajet");
        r("POST", "/transport/depenses", "Enregistrement d'une dépense de transport");
        r("POST", "/transport/depenses/{}/comptabiliser", "Comptabilisation d'une dépense de transport");
        r("POST", "/transport/depenses/{}/annuler-comptabilisation", "Annulation de la comptabilisation d'une dépense de transport");

        // --- DRH -------------------------------------------------------------------------------------------------
        r("POST", "/drh/employes", "Création d'un employé");
        r("PUT", "/drh/employes/{}", "Modification d'un employé");
        r("PATCH", "/drh/employes/{}/desactiver", "Désactivation d'un employé");
        r("PATCH", "/drh/employes/{}/reactiver", "Réactivation d'un employé");
        r("POST", "/drh/missions", "Création d'une mission");
        r("PUT", "/drh/missions/{}", "Modification d'une mission");
        r("DELETE", "/drh/missions/{}", "Suppression d'une mission");
        r("POST", "/drh/rotations", "Création d'une rotation de superviseur");
        r("PUT", "/drh/rotations/{}", "Modification d'une rotation de superviseur");
        r("DELETE", "/drh/rotations/{}", "Suppression d'une rotation de superviseur");
        r("POST", "/drh/sites", "Création d'un site");
        r("PUT", "/drh/sites/{}", "Modification d'un site");
        r("POST", "/drh/sorties", "Création d'une sortie de travailleur");
        r("PUT", "/drh/sorties/{}", "Modification d'une sortie de travailleur");
        r("DELETE", "/drh/sorties/{}", "Suppression d'une sortie de travailleur");
        r("PUT", "/drh/presences", "Enregistrement des présences");
        r("POST", "/drh/presences/tout-present", "Marquage de tous les agents comme présents");
        r("POST", "/drh/agents-presence-manuelle", "Ajout d'un agent à la fiche de présence manuelle");
        r("PUT", "/drh/agents-presence-manuelle/{}", "Modification d'un agent de la fiche de présence manuelle");
        r("DELETE", "/drh/agents-presence-manuelle/{}", "Retrait d'un agent de la fiche de présence manuelle");
        r("DELETE", "/drh/agents-presence-manuelle", "Vidage de la fiche de présence manuelle");
        r("PUT", "/drh/parametres-paie", "Modification des paramètres de paie");
        r("POST", "/drh/bulletins", "Création d'un bulletin de paie");
        r("PUT", "/drh/bulletins/{}", "Modification d'un bulletin de paie");
        r("DELETE", "/drh/bulletins/{}", "Suppression d'un bulletin de paie");
        r("POST", "/drh/bulletins/{}/valider", "Validation d'un bulletin de paie");
        r("POST", "/drh/bulletins/{}/devalider", "Dévalidation d'un bulletin de paie");
        r("POST", "/drh/bulletins/{}/annuler", "Annulation d'un bulletin de paie");
        r("POST", "/drh/bulletins/simuler", "Simulation d'un bulletin de paie (rien n'est enregistré)");
        r("POST", "/drh/bulletins/cloturer", "Clôture d'une période de paie");
        r("POST", "/drh/bulletins/rouvrir", "Réouverture d'une période de paie");
        r("POST", "/drh/bulletins/reglement/note-paie", "Création de la note de frais des salaires");
        r("POST", "/drh/bulletins/reglement/notes-impots/{}", "Création de la note de frais de l'organisme {0} (retenues sur salaires)");
        r("POST", "/drh/bulletins/reglement/notes/{}/annuler", "Annulation d'une note de règlement de paie");

        // --- Restaurant ------------------------------------------------------------------------------------------
        r("PUT", "/restaurant/parametres", "Modification des paramètres du restaurant");
        r("POST", "/restaurant/carte", "Création d'un article de la carte");
        r("PUT", "/restaurant/carte/{}", "Modification d'un article de la carte");
        r("DELETE", "/restaurant/carte/{}", "Suppression d'un article de la carte");
        r("POST", "/restaurant/emballages", "Création d'un type d'emballage consigné");
        r("PUT", "/restaurant/emballages/{}", "Modification d'un type d'emballage consigné");
        r("POST", "/restaurant/emballages/mouvements", "Enregistrement d'un mouvement d'emballages");
        r("POST", "/restaurant/emballages/mouvements/{}/annuler", "Annulation d'un mouvement d'emballages");
        r("POST", "/restaurant/plats/sorties", "Enregistrement d'une sortie de plats");
        r("POST", "/restaurant/plats/sorties/{}/annuler", "Annulation d'une sortie de plats");
        r("POST", "/restaurant/productions", "Enregistrement d'une production de plats");
        r("POST", "/restaurant/productions/{}/annuler", "Annulation d'une production de plats");
        r("POST", "/restaurant/provisions", "Création d'une provision (matière première)");
        r("PUT", "/restaurant/provisions/{}", "Modification d'une provision (matière première)");
        r("POST", "/restaurant/provisions/entrees", "Réception de provisions");
        r("POST", "/restaurant/provisions/sorties", "Enregistrement d'une sortie de provisions");
        r("POST", "/restaurant/provisions/mouvements/{}/annuler", "Annulation d'un mouvement de provisions");
        r("PUT", "/restaurant/recettes/{}", "Enregistrement de la recette d'un plat");
        r("PUT", "/restaurant/recettes/{}/prix-vente", "Modification du prix de vente d'un plat");
        r("POST", "/restaurant/salles", "Création d'une salle");
        r("PUT", "/restaurant/salles/{}", "Modification d'une salle");
        r("DELETE", "/restaurant/salles/{}", "Suppression d'une salle");
        r("PUT", "/restaurant/salles/{}/plan", "Enregistrement du plan d'une salle");
        r("POST", "/restaurant/tables/{}/regler-addition", "Règlement de l'addition d'une table");
        r("PUT", "/restaurant/tables/{}/statut", "Changement du statut d'une table");

        // La règle la plus précise l'emporte : « /notes-frais/parametres-priorite » avant « /notes-frais/{} ».
        REGLES.sort(Comparator.comparingInt(Regle::variables));
    }

    /**
     * Libellé d'une action d'écriture.
     *
     * @param methode méthode HTTP
     * @param chemin  chemin sans le contexte, par ex. {@code /admin/import/journal}
     * @param requete chaîne de requête déjà débarrassée des paramètres sensibles (peut être null)
     */
    public static String decrire(String methode, String chemin, String requete) {
        String m = methode == null ? "" : methode.toUpperCase(Locale.ROOT);
        String c = chemin == null ? "" : chemin.strip();
        if (c.length() > 1 && c.endsWith("/")) c = c.substring(0, c.length() - 1);

        if (m.equals("POST") && c.equals("/admin/import/journal")) return importJournal(requete);

        for (Regle regle : REGLES) {
            if (!regle.methode.equals(m)) continue;
            Matcher mat = regle.chemin.matcher(c);
            if (mat.matches()) return remplir(regle.modele, mat);
        }
        return generique(m, c);
    }

    /** Vrai si la route a son libellé dans le catalogue (sinon {@link #decrire} retombe sur un libellé générique). */
    public static boolean connu(String methode, String chemin) {
        String m = methode == null ? "" : methode.toUpperCase(Locale.ROOT);
        String c = chemin == null ? "" : chemin.strip();
        if (m.equals("POST") && c.equals("/admin/import/journal")) return true;
        return REGLES.stream().anyMatch(r -> r.methode.equals(m) && r.chemin.matcher(c).matches());
    }

    /** Libellé d'un événement de connexion ou de déconnexion. */
    public static String authentification(String operation, boolean reussi, String detail) {
        if ("DECONNEXION".equals(operation)) return "Déconnexion";
        if ("CONNEXION".equals(operation)) {
            return reussi ? "Connexion à l'application"
                : "Connexion refusée" + (detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")");
        }
        return "Authentification" + (operation == null ? "" : " : " + operation.toLowerCase(Locale.ROOT));
    }

    // -----------------------------------------------------------------------------------------------------------------

    /** Import d'un journal : analyse (simulation) ou import réel, et ce qu'il fait des écritures déjà présentes. */
    private static String importJournal(String requete) {
        boolean simulation = !"false".equalsIgnoreCase(parametre(requete, "simulation"));   // par défaut le serveur simule
        if (simulation) return "Analyse d'un fichier de journal comptable (simulation : rien n'est écrit)";
        String mode = parametre(requete, "modeImport");
        if ("REMPLACER".equalsIgnoreCase(mode)) return "Import d'un journal comptable — remplace toutes les écritures existantes";
        if ("AJOUTER".equalsIgnoreCase(mode)) return "Import d'un journal comptable — ajoute aux écritures existantes";
        return "Import d'un journal comptable (mode Remplacer ou Ajouter non enregistré)";
    }

    private static String parametre(String requete, String nom) {
        if (requete == null) return null;
        for (String p : requete.split("&")) {
            int egal = p.indexOf('=');
            if (egal > 0 && p.substring(0, egal).equals(nom)) return p.substring(egal + 1);
        }
        return null;
    }

    private static String remplir(String modele, Matcher mat) {
        String texte = modele;
        for (int i = 1; i <= mat.groupCount(); i++) {
            texte = texte.replace("{" + (i - 1) + "}", valeur(mat.group(i)));
        }
        return texte;
    }

    /** Valeur d'un segment variable : nom de module lisible, fournisseur d'IA avec sa casse, sinon telle quelle. */
    private static String valeur(String segment) {
        String module = MODULES.get(segment);
        if (module != null) return module;
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "openai" -> "OpenAI";
            case "anthropic" -> "Anthropic";
            default -> segment;
        };
    }

    /** Libellé de repli pour une route absente du catalogue : le verbe HTTP et le chemin lisible. */
    private static String generique(String methode, String chemin) {
        String verbe = switch (methode) {
            case "POST" -> "Action";
            case "PUT", "PATCH" -> "Modification";
            case "DELETE" -> "Suppression";
            default -> methode;
        };
        StringBuilder objet = new StringBuilder();
        for (String segment : chemin.split("/")) {
            if (segment.isBlank() || segment.matches("\\d+")) continue;
            if (objet.length() > 0) objet.append(" › ");
            objet.append(segment.replace('-', ' '));
        }
        return objet.length() == 0 ? verbe : verbe + " sur " + objet;
    }
}
