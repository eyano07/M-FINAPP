package com.mbsc.finapp.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Code d'un article de la carte (boisson), composé à partir de son libellé et,
 * s'il existe, de sa société : « Primus 55CL » de « Bracongo » donne
 * {@code BRAC-PRIM-55CL}.
 *
 * <p>Le code se lit en trois blocs séparés par des tirets :</p>
 * <ul>
 *   <li><b>la société</b>, si elle est renseignée : les 4 premières lettres d'un nom
 *       d'un seul mot (« Bracongo » : BRAC), les initiales d'un nom de plusieurs mots
 *       (« Brasserie du Congo » : BC) ; les mots vides (de, du, la...) et les formes
 *       juridiques (SARL, SA...) sont ignorés ;</li>
 *   <li><b>le libellé</b> : ses trois premiers mots significatifs, tronqués à 4
 *       lettres (« Coca Cola » : COCA-COLA) ;</li>
 *   <li><b>la contenance</b>, si le libellé en porte une (55CL, 33CL, 1L, 15L pour
 *       « 1,5 L »).</li>
 * </ul>
 *
 * <p>Sans accents, en majuscules, avec seulement des lettres, chiffres et tirets : le
 * code reste lisible sur un ticket, une facture ou un export. S'il existe déjà, un
 * numéro est ajouté (-2, -3...) ; un code ne dépasse jamais {@value #LONGUEUR_MAX}
 * caractères, la taille de la colonne {@code articles.code}.</p>
 */
public final class GenerateurCodeArticle {

    /** Taille de la colonne articles.code. */
    static final int LONGUEUR_MAX = 40;

    private static final int LETTRES_SOCIETE = 4;
    private static final int INITIALES_MAX = 4;
    private static final int MOTS_LIBELLE = 3;
    private static final int LETTRES_PAR_MOT = 4;
    private static final int LONGUEUR_MESURE_MAX = 8;

    private static final Set<String> MOTS_VIDES = Set.of(
        "DE", "DU", "DES", "LA", "LE", "LES", "ET", "D", "L", "AU", "AUX", "EN", "A", "UN", "UNE",
        "THE", "OF", "AND");
    private static final Set<String> FORMES_JURIDIQUES = Set.of(
        "SARL", "SA", "SAS", "SASU", "SPRL", "SNC", "SCS", "LTD", "LLC", "INC", "CO", "CIE");
    private static final Set<String> UNITES = Set.of("CL", "ML", "DL", "L", "KG", "G", "MG", "CM", "MM", "M");

    /** La virgule ou le point entre deux chiffres (1,5 L) : retirés, « 1,5L » devient « 15L ». */
    private static final Pattern SEPARATEUR_DECIMAL = Pattern.compile("(?<=\\d)[.,](?=\\d)");
    private static final Pattern NON_ALPHANUMERIQUE = Pattern.compile("[^A-Z0-9]+");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");

    private GenerateurCodeArticle() {
    }

    /**
     * Code libre le plus proche de la base déduite du libellé et de la société.
     *
     * @param existe dit si un code est déjà pris (en pratique {@code articleRepository::existsByCode})
     * @return le code, ou une chaîne vide si le libellé ne contient ni lettre ni chiffre
     */
    public static String generer(String libelle, String societe, Predicate<String> existe) {
        String base = codeDeBase(libelle, societe);
        if (base.isEmpty() || !existe.test(base)) {
            return base;
        }
        for (int n = 2; n < 10_000; n++) {
            String suffixe = "-" + n;
            String candidat = tronquer(base, LONGUEUR_MAX - suffixe.length()) + suffixe;
            if (!existe.test(candidat)) {
                return candidat;
            }
        }
        throw new IllegalStateException("Aucun code disponible pour « " + base + " »");
    }

    /** Le code avant toute vérification d'unicité. */
    static String codeDeBase(String libelle, String societe) {
        List<String> jetons = fusionnerMesures(jetons(libelle));
        if (jetons.isEmpty()) {
            return "";
        }
        List<String> mots = jetons.stream()
            .filter(t -> !contientChiffre(t) && !MOTS_VIDES.contains(t))
            .limit(MOTS_LIBELLE)
            .map(t -> tronquer(t, LETTRES_PAR_MOT))
            .toList();
        String mesure = jetons.stream().filter(GenerateurCodeArticle::contientChiffre).findFirst()
            .map(t -> tronquer(t, LONGUEUR_MESURE_MAX)).orElse(null);

        List<String> blocs = new ArrayList<>();
        String abreviationSociete = abreviationSociete(societe);
        if (!abreviationSociete.isEmpty()) {
            blocs.add(abreviationSociete);
        }
        if (mots.isEmpty() && mesure == null) {
            // Libellé fait uniquement de mots vides (« De la ») : à défaut de mieux, son premier mot.
            blocs.add(tronquer(jetons.get(0), LETTRES_PAR_MOT));
        }
        blocs.addAll(mots);
        if (mesure != null) {
            blocs.add(mesure);
        }
        return tronquer(String.join("-", blocs), LONGUEUR_MAX);
    }

    /** « Bracongo » : BRAC ; « Brasserie du Congo SARL » : BC ; vide si la société n'est pas renseignée. */
    static String abreviationSociete(String societe) {
        List<String> mots = jetons(societe).stream()
            .filter(t -> contientLettre(t) && !FORMES_JURIDIQUES.contains(t) && !MOTS_VIDES.contains(t))
            .toList();
        if (mots.isEmpty()) {
            return "";
        }
        if (mots.size() == 1) {
            return tronquer(mots.get(0), LETTRES_SOCIETE);
        }
        StringBuilder initiales = new StringBuilder();
        for (int i = 0; i < Math.min(INITIALES_MAX, mots.size()); i++) {
            initiales.append(mots.get(i).charAt(0));
        }
        return initiales.toString();
    }

    /** Majuscules sans accents ni ligatures, apostrophes et ponctuation comprises dans les séparateurs. */
    static String normaliser(String texte) {
        if (texte == null) {
            return "";
        }
        String s = texte.strip()
            .replace("œ", "oe").replace("Œ", "OE").replace("æ", "ae").replace("Æ", "AE").replace("ß", "ss");
        return ACCENTS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private static List<String> jetons(String texte) {
        String s = SEPARATEUR_DECIMAL.matcher(normaliser(texte)).replaceAll("");
        List<String> jetons = new ArrayList<>();
        for (String t : NON_ALPHANUMERIQUE.split(s)) {
            if (!t.isEmpty()) {
                jetons.add(t);
            }
        }
        return jetons;
    }

    /** « 33 » suivi de « CL » devient « 33CL » : une contenance écrite avec une espace reste un seul bloc. */
    private static List<String> fusionnerMesures(List<String> jetons) {
        List<String> fusionnes = new ArrayList<>();
        for (int i = 0; i < jetons.size(); i++) {
            String t = jetons.get(i);
            if (t.chars().allMatch(Character::isDigit) && i + 1 < jetons.size() && UNITES.contains(jetons.get(i + 1))) {
                fusionnes.add(t + jetons.get(i + 1));
                i++;
            } else {
                fusionnes.add(t);
            }
        }
        return fusionnes;
    }

    private static boolean contientChiffre(String s) {
        return s.chars().anyMatch(Character::isDigit);
    }

    private static boolean contientLettre(String s) {
        return s.chars().anyMatch(Character::isLetter);
    }

    /** Coupe à {@code max} caractères, sans laisser de tiret en bout de code. */
    private static String tronquer(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String coupe = s.substring(0, max);
        return coupe.endsWith("-") ? coupe.substring(0, coupe.length() - 1) : coupe;
    }
}
