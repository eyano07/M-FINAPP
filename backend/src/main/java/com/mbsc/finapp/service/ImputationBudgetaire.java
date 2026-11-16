package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Budget;
import com.mbsc.finapp.domain.LigneBudget;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Imputation d'une depense sur un budget organise par service (calcul pur, sans acces a la base).
 *
 * <p>Le budget reste unique : ses <b>lignes consolidees</b> (un compte, tous services confondus) recoivent toutes
 * les depenses, comme avant les services. Chaque service a en plus son <b>enveloppe</b> : ses propres lignes. Les
 * lignes sans service (« lignes generales ») forment l'enveloppe commune.</p>
 * <ul>
 *   <li>Une depense rattachee a un service s'impute sur la ligne de ce service la plus precise couvrant son
 *       compte ; a defaut, sur la ligne generale couvrant le compte ; a defaut, elle est hors budget pour le
 *       service.</li>
 *   <li>Une depense sans service s'impute sur la ligne generale couvrant son compte. Si seules des lignes de
 *       services couvrent le compte, elle n'appartient a aucune enveloppe (« non rattachee ») et ne compte que
 *       dans le budget consolide.</li>
 * </ul>
 *
 * <p>Un meme compte peut figurer dans plusieurs services : il se cumule dans le budget consolide. Un compte et
 * l'un de ses sous-comptes ne peuvent pas coexister dans le budget, meme dans des services differents (voir
 * {@link #chevauchements}) : chaque compte mouvemente se range ainsi sur une seule ligne consolidee.</p>
 */
public final class ImputationBudgetaire {

    /** Enveloppe d'imputation : la ligne d'un service sur un compte, ou la ligne generale (serviceId null). */
    public record Enveloppe(Long serviceId, String compte) {
    }

    /** Lignes par service ; la cle null regroupe les lignes generales (HashMap : cle null admise). */
    private final Map<Long, Set<String>> comptesParService = new HashMap<>();
    private final Set<String> comptesConsolides = new TreeSet<>();

    public ImputationBudgetaire(Collection<Enveloppe> lignes) {
        for (Enveloppe l : lignes) {
            comptesParService.computeIfAbsent(l.serviceId(), s -> new LinkedHashSet<>()).add(l.compte());
            comptesConsolides.add(l.compte());
        }
    }

    public static ImputationBudgetaire de(Budget budget) {
        return new ImputationBudgetaire(budget.getLignes().stream().map(ImputationBudgetaire::enveloppeDe).toList());
    }

    public static Enveloppe enveloppeDe(LigneBudget l) {
        return new Enveloppe(l.getService() == null ? null : l.getService().getId(), l.getCompte().getNumero());
    }

    /** true si au moins une ligne est rattachee a un service. */
    public boolean parService() {
        return comptesParService.keySet().stream().anyMatch(java.util.Objects::nonNull);
    }

    /** true si le budget a des lignes generales (sans service). */
    public boolean aDesLignesGenerales() {
        return comptesParService.containsKey(null);
    }

    /** Comptes des lignes consolidees (un par compte, tous services confondus). */
    public Set<String> comptesConsolides() {
        return comptesConsolides;
    }

    /** Ligne consolidee couvrant le compte (la plus precise), ou null : la depense est hors budget. */
    public String ligneConsolidee(String compte) {
        return MoteurBudgetaire.ligneCouvrant(compte, comptesConsolides);
    }

    /**
     * Enveloppe sur laquelle s'impute une depense de ce compte, rattachee a ce service (null : aucun service) ;
     * null si aucune enveloppe ne la couvre.
     */
    public Enveloppe enveloppe(String compte, Long serviceId) {
        if (serviceId != null) {
            String ligne = MoteurBudgetaire.ligneCouvrant(compte, comptesParService.get(serviceId));
            if (ligne != null) {
                return new Enveloppe(serviceId, ligne);
            }
        }
        String generale = MoteurBudgetaire.ligneCouvrant(compte, comptesParService.get(null));
        return generale == null ? null : new Enveloppe(null, generale);
    }

    /**
     * Couples de lignes qui se recouvrent dans le budget consolide : un compte et l'un de ses sous-comptes, quel
     * que soit leur service (une depense serait comptee deux fois). Un meme compte dans deux services differents
     * n'est pas un recouvrement : il se cumule.
     */
    public static List<String[]> chevauchements(Collection<String> comptes) {
        List<String> distincts = List.copyOf(new TreeSet<>(comptes));
        return MoteurBudgetaire.chevauchements(distincts);
    }
}
