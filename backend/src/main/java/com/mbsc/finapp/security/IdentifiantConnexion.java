package com.mbsc.finapp.security;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identifiant de connexion d'un compte : une adresse e-mail <em>ou</em> un nom d'utilisateur
 * (ex. « dfin_bruno »). Il est conserve dans la colonne {@code users.email} et circule sous le
 * nom {@code email} dans l'API (application web, client lourd) : le nom du champ n'a pas change,
 * mais ce n'est plus forcement une adresse. Les envois de courriels passent exclusivement par le
 * « Mail de notification » ({@code emailNotification}) du compte.
 *
 * <p>Deux regles distinctes :</p>
 * <ul>
 *   <li>{@link #MOTIF_CREATION} — a la creation d'un compte : 3 a 150 caracteres parmi lettres,
 *       chiffres et {@code . _ - @ +}, sans espace. L'identifiant figure dans les journaux, les
 *       jetons et les en-tetes imprimes : on n'y accepte ni espace ni caractere de balisage.</li>
 *   <li>{@link #MOTIF_SAISIE} — a la connexion : permissive, pour que les comptes existants
 *       (adresses e-mail de toute forme) continuent de se connecter, mais sans caractere de
 *       controle, de format ni de separation. La route de connexion etant publique, retirer le
 *       controle {@code @Email} sans cette garde laisserait un appelant anonyme glisser un saut de
 *       ligne dans les journaux (falsification de journal).</li>
 * </ul>
 *
 * <p>La casse est ignoree : l'identifiant est enregistre en minuscules ({@link #normaliser}) et
 * retrouve sans tenir compte de la casse (voir {@link AppUserDetailsService}).</p>
 */
public final class IdentifiantConnexion {

    /** Creation : 3 a 150 caracteres (taille de la colonne), lettres, chiffres et . _ - @ + uniquement. */
    public static final String MOTIF_CREATION = "[\\p{L}\\p{N}._@+\\-]{3,150}";

    public static final String MESSAGE_CREATION =
        "L'identifiant doit comporter 3 à 150 caractères : lettres, chiffres et . _ - @ + (sans espace).";

    /** Connexion : aucun caractere de controle, de format (invisible) ni de separation (espace, saut de ligne). */
    public static final String MOTIF_SAISIE = "[^\\p{Cc}\\p{Cf}\\p{Z}]+";

    public static final String MESSAGE_SAISIE = "Identifiant invalide : ni espace ni caractère spécial.";

    /** « quelque chose@quelque chose » : les comptes existants ont tous ete valides par {@code @Email}. */
    private static final Pattern ADRESSE = Pattern.compile("[^@\\s<>]+@[^@\\s<>]+");

    private IdentifiantConnexion() {
    }

    /** Forme enregistree : sans espaces autour, en minuscules ({@code null} reste {@code null}). */
    public static String normaliser(String identifiant) {
        return identifiant == null ? null : identifiant.trim().toLowerCase(Locale.ROOT);
    }

    /** Vrai si l'identifiant est lui-meme une adresse e-mail utilisable comme destinataire. */
    public static boolean estUneAdresse(String identifiant) {
        return identifiant != null && ADRESSE.matcher(identifiant.trim()).matches();
    }

    /**
     * Adresse e-mail a imprimer ou a utiliser pour joindre l'utilisateur : son identifiant s'il en
     * est une, sinon son « Mail de notification », sinon rien ({@code null}).
     */
    public static String adresseDeContact(String identifiant, String adresseNotification) {
        if (estUneAdresse(identifiant)) {
            return identifiant.trim();
        }
        return adresseNotification == null || adresseNotification.isBlank() ? null : adresseNotification.trim();
    }
}
