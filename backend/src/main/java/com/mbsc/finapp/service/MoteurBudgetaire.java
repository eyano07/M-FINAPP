package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.enums.StatutControleBudget;
import com.mbsc.finapp.domain.enums.TypeCompte;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Calculs budgetaires purs (aucun acces a la base), testables isolement.
 *
 * <ul>
 *   <li><b>Ventilation</b> : un montant annuel se repartit sur les douze mois de l'exercice, a parts egales ou
 *       selon un profil (la saisonnalite reelle de l'annee precedente) ; la somme des mois est toujours
 *       exactement le montant annuel, au centime pres.</li>
 *   <li><b>Periodes</b> : trimestres, semestres et annee sont des totaux des mois — jamais saisis a part,
 *       donc toujours coherents entre eux.</li>
 *   <li><b>Comptes</b> (SYSCOHADA revise) : une ligne budgetaire porte sur un compte du plan comptable et
 *       couvre ses sous-comptes (« 604 » couvre 6041, 6042, 6042.14...). Une depense est rattachee a la ligne
 *       la plus precise ; deux lignes d'un meme budget ne peuvent pas se recouvrir.</li>
 *   <li><b>Realise</b> : charges et investissements se lisent au debit, produits au credit.</li>
 *   <li><b>Controle</b> : disponible = prevu cumule a fin de mois - realise cumule - engage.</li>
 * </ul>
 */
public final class MoteurBudgetaire {

    public static final int MOIS = 12;
    private static final BigDecimal DOUZE = BigDecimal.valueOf(MOIS);
    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    private MoteurBudgetaire() {
    }

    // ---------------------------------------------------------------------
    // Ventilation d'un montant annuel
    // ---------------------------------------------------------------------

    /** Parts egales au centime pres ; les centimes restants vont sur decembre (la somme est exacte). */
    public static BigDecimal[] repartirUniformement(BigDecimal annuel) {
        BigDecimal total = arrondi(nz(annuel));
        BigDecimal part = total.divide(DOUZE, 2, RoundingMode.DOWN);
        BigDecimal[] mois = new BigDecimal[MOIS];
        Arrays.fill(mois, part);
        mois[MOIS - 1] = total.subtract(part.multiply(BigDecimal.valueOf(MOIS - 1)));
        return mois;
    }

    /**
     * Repartition proportionnelle a un profil de douze poids positifs ou nuls (par exemple le realise mois par
     * mois de l'annee precedente). Profil absent, nul ou incomplet : parts egales.
     */
    public static BigDecimal[] repartirSelonProfil(BigDecimal annuel, BigDecimal[] poids) {
        BigDecimal total = arrondi(nz(annuel));
        if (poids == null || poids.length != MOIS) {
            return repartirUniformement(total);
        }
        BigDecimal somme = BigDecimal.ZERO;
        for (BigDecimal p : poids) {
            somme = somme.add(nz(p).max(BigDecimal.ZERO));
        }
        if (somme.signum() <= 0) {
            return repartirUniformement(total);
        }
        BigDecimal[] mois = new BigDecimal[MOIS];
        BigDecimal reparti = BigDecimal.ZERO;
        for (int i = 0; i < MOIS - 1; i++) {
            // Arrondi par defaut sur les onze premiers mois : decembre recoit le reste, jamais negatif.
            mois[i] = total.multiply(nz(poids[i]).max(BigDecimal.ZERO)).divide(somme, 2, RoundingMode.DOWN);
            reparti = reparti.add(mois[i]);
        }
        mois[MOIS - 1] = total.subtract(reparti);
        return mois;
    }

    // ---------------------------------------------------------------------
    // Periodes : mois -> trimestres -> semestres -> annee
    // ---------------------------------------------------------------------

    public static BigDecimal total(BigDecimal[] mensuel) {
        return somme(mensuel, 0, MOIS);
    }

    /** T1 (janv.-mars), T2, T3, T4. */
    public static BigDecimal[] trimestres(BigDecimal[] mensuel) {
        return new BigDecimal[] {somme(mensuel, 0, 3), somme(mensuel, 3, 6), somme(mensuel, 6, 9), somme(mensuel, 9, 12)};
    }

    /** S1 (janv.-juin), S2 (juil.-dec.). */
    public static BigDecimal[] semestres(BigDecimal[] mensuel) {
        return new BigDecimal[] {somme(mensuel, 0, 6), somme(mensuel, 6, 12)};
    }

    /** Total de janvier jusqu'au mois indique inclus (0 : rien ; 12 ou plus : l'annee). */
    public static BigDecimal cumul(BigDecimal[] mensuel, int jusquAuMois) {
        return somme(mensuel, 0, Math.max(0, Math.min(MOIS, jusquAuMois)));
    }

    /** Tableau de douze montants a partir d'une table mois (1-12) -> montant ; les mois absents valent 0. */
    public static BigDecimal[] mensuelDepuis(Map<Integer, BigDecimal> parMois) {
        BigDecimal[] mois = zeros();
        if (parMois != null) {
            parMois.forEach((m, v) -> {
                if (m != null && m >= 1 && m <= MOIS) {
                    mois[m - 1] = nz(v);
                }
            });
        }
        return mois;
    }

    public static BigDecimal[] zeros() {
        BigDecimal[] mois = new BigDecimal[MOIS];
        Arrays.fill(mois, BigDecimal.ZERO);
        return mois;
    }

    /** Somme terme a terme de deux series mensuelles. */
    public static BigDecimal[] additionner(BigDecimal[] a, BigDecimal[] b) {
        BigDecimal[] r = new BigDecimal[MOIS];
        for (int i = 0; i < MOIS; i++) {
            r[i] = nz(a == null ? null : a[i]).add(nz(b == null ? null : b[i]));
        }
        return r;
    }

    // ---------------------------------------------------------------------
    // Comptes SYSCOHADA
    // ---------------------------------------------------------------------

    /**
     * Comptes qui peuvent porter une ligne budgetaire : investissements (classe 2), charges (6), produits (7)
     * et H.A.O. (8). Exclus : amortissements et depreciations d'immobilisations (28, 29), qui ne sont jamais
     * des depenses (la dotation se budgete en 68), et les comptes a un seul chiffre (une classe entiere
     * melangerait des natures differentes).
     */
    public static boolean estBudgetable(String numero, Integer classe) {
        if (numero == null || numero.length() < 2 || classe == null) {
            return false;
        }
        if (classe != 2 && classe != 6 && classe != 7 && classe != 8) {
            return false;
        }
        return !numero.startsWith("28") && !numero.startsWith("29");
    }

    /**
     * true si une depense imputee a ce compte est soumise au controle budgetaire : charges (classe 6 et charges
     * H.A.O.) et investissements (classe 2). Les autres imputations d'une note de frais ne sont pas des
     * depenses budgetaires : reglement d'une dette fournisseur (40), avance au personnel (42), tresorerie (5)...
     */
    public static boolean estDepenseBudgetaire(String numero, Integer classe, TypeCompte type) {
        if (numero == null || classe == null) {
            return false;
        }
        return switch (classe) {
            case 6 -> true;
            case 2 -> !numero.startsWith("28") && !numero.startsWith("29");
            case 8 -> type == TypeCompte.CHARGE;
            default -> false;
        };
    }

    /** Compte de la ligne budgetaire la plus precise qui couvre ce compte (plus long prefixe), ou null. */
    public static String ligneCouvrant(String numeroCompte, Collection<String> comptesDesLignes) {
        if (numeroCompte == null || comptesDesLignes == null) {
            return null;
        }
        String meilleure = null;
        for (String c : comptesDesLignes) {
            if (c != null && numeroCompte.startsWith(c) && (meilleure == null || c.length() > meilleure.length())) {
                meilleure = c;
            }
        }
        return meilleure;
    }

    /**
     * Couples de lignes qui se recouvrent (meme compte, ou l'un sous-compte de l'autre) : interdits dans un
     * meme budget, faute de quoi une depense pourrait etre comptee deux fois ou ne plus savoir ou se ranger.
     */
    public static List<String[]> chevauchements(List<String> comptes) {
        List<String[]> conflits = new ArrayList<>();
        for (int i = 0; i < comptes.size(); i++) {
            for (int j = i + 1; j < comptes.size(); j++) {
                String a = comptes.get(i);
                String b = comptes.get(j);
                if (a != null && b != null && (a.startsWith(b) || b.startsWith(a))) {
                    conflits.add(a.length() <= b.length() ? new String[] {a, b} : new String[] {b, a});
                }
            }
        }
        return conflits;
    }

    /** Montant realise d'un mouvement : charges et investissements au debit, produits au credit. */
    public static BigDecimal realise(TypeCompte type, BigDecimal debit, BigDecimal credit) {
        return type == TypeCompte.PRODUIT ? nz(credit).subtract(nz(debit)) : nz(debit).subtract(nz(credit));
    }

    /** Grandes masses du budget, dans l'ordre de presentation. */
    public enum Section { PRODUITS, CHARGES, INVESTISSEMENTS }

    public static Section section(String numero, TypeCompte type) {
        if (numero != null && numero.startsWith("2")) {
            return Section.INVESTISSEMENTS;
        }
        return type == TypeCompte.PRODUIT ? Section.PRODUITS : Section.CHARGES;
    }

    /** Nature SYSCOHADA d'une ligne : son compte a deux chiffres (60 Achats, 61 Transports, 66 Personnel...). */
    public static String nature(String numero) {
        return numero == null || numero.length() < 2 ? numero : numero.substring(0, 2);
    }

    // ---------------------------------------------------------------------
    // Controle d'une depense
    // ---------------------------------------------------------------------

    /** Position d'une ligne budgetaire a fin de mois (cumuls depuis janvier) et sur l'annee. */
    public record Disponibilite(BigDecimal prevuCumule, BigDecimal realiseCumule, BigDecimal engage,
                                BigDecimal disponibleCumule, BigDecimal prevuAnnuel, BigDecimal disponibleAnnuel) {
    }

    /**
     * @param prevu      les douze montants prevus
     * @param realise    les douze montants realises (grand livre)
     * @param engage     engagements en cours (notes approuvees non payees) a imputer sur la ligne
     * @param mois       mois de la depense (1-12)
     */
    public static Disponibilite disponibilite(BigDecimal[] prevu, BigDecimal[] realise, BigDecimal engage, int mois) {
        BigDecimal prevuCumule = cumul(prevu, mois);
        BigDecimal realiseCumule = cumul(realise, mois);
        BigDecimal eng = nz(engage);
        BigDecimal prevuAnnuel = total(prevu);
        return new Disponibilite(prevuCumule, realiseCumule, eng,
            prevuCumule.subtract(realiseCumule).subtract(eng),
            prevuAnnuel,
            prevuAnnuel.subtract(total(realise)).subtract(eng));
    }

    /** CONFORME si le montant tient dans le disponible cumule a fin de mois, DEPASSEMENT sinon. */
    public static StatutControleBudget statut(BigDecimal montant, Disponibilite d) {
        return nz(montant).compareTo(d.disponibleCumule()) <= 0 ? StatutControleBudget.CONFORME : StatutControleBudget.DEPASSEMENT;
    }

    /** Taux d'execution en pourcentage (une decimale), null si rien n'est prevu. */
    public static BigDecimal taux(BigDecimal realise, BigDecimal prevu) {
        if (prevu == null || prevu.signum() == 0) {
            return null;
        }
        return nz(realise).multiply(CENT).divide(prevu, 1, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------

    private static BigDecimal somme(BigDecimal[] mensuel, int debut, int fin) {
        BigDecimal s = BigDecimal.ZERO;
        if (mensuel == null) {
            return s;
        }
        for (int i = debut; i < fin && i < mensuel.length; i++) {
            s = s.add(nz(mensuel[i]));
        }
        return s;
    }

    private static BigDecimal arrondi(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
