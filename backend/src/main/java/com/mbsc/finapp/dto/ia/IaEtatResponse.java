package com.mbsc.finapp.dto.ia;

import java.time.Instant;
import java.util.List;

/**
 * État de l'IA pour l'écran Administration. Les clés ne sont jamais renvoyées : seulement leur fin, pour
 * reconnaître celle qui est enregistrée.
 *
 * @param iaDesactivee {@code APP_IA_ENABLED=false} : toute l'IA est coupée au niveau du serveur
 */
public record IaEtatResponse(boolean iaDesactivee, List<Fournisseur> fournisseurs) {

    /**
     * @param id          {@code openai} ou {@code anthropic}
     * @param etat        NON_CONFIGURE, ACTIF, CREDIT_EPUISE ou CLE_REFUSEE
     * @param cleFin      4 derniers caractères de la clé enregistrée ({@code null} sans clé)
     * @param viaEnvironnement clé fournie par une variable d'environnement et non par l'écran
     * @param depuis      date du premier refus (crédit épuisé ou clé refusée)
     */
    public record Fournisseur(String id, String libelle, String role, boolean cleConfiguree, String cleFin,
                              boolean viaEnvironnement, String modele, String modeleParDefaut, String etat,
                              Instant depuis) {}
}
