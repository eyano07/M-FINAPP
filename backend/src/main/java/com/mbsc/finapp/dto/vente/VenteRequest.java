package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeReglement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Saisie d'une vente.
 *
 * @param clientId        client du repertoire (facultatif)
 * @param clientNom       nom libre, utilise si aucun client n'est choisi
 * @param devise          devise de saisie des prix unitaires (CDF par defaut)
 * @param etablissementId banque ou operateur encaisseur, requis pour un
 *                        reglement BANQUE ou MOBILE_MONEY
 * @param entrepotId      entrepot de sortie, requis des qu'une ligne porte
 *                        une marchandise
 */
public record VenteRequest(

    LocalDate dateVente,

    Long clientId,

    @Size(max = 200)
    String clientNom,

    @NotNull(message = "Le mode de reglement est obligatoire")
    ModeReglement modeReglement,

    Devise devise,

    Long etablissementId,

    Long entrepotId,

    @NotEmpty(message = "Une vente doit comporter au moins une ligne")
    @Valid
    List<LigneVenteRequest> lignes
) {}
