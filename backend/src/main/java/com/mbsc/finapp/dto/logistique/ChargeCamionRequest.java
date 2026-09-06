package com.mbsc.finapp.dto.logistique;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Frais accessoire a incorporer au cout d'acquisition d'un camion de minerais.
 *
 * @param compteChargeNumero compte de charge par nature (611 transports sur
 *                           achats, 6288 services exterieurs divers...). Choisi
 *                           a chaque fois : le peage, le pont bascule et le
 *                           transport ne relevent pas du meme compte.
 */
public record ChargeCamionRequest(
    @NotBlank @Size(max = 200) String libelle,
    @NotBlank String compteChargeNumero,
    @NotNull @Positive BigDecimal montant,
    @NotNull LocalDate dateCharge,

    /**
     * true pour appliquer ce meme frais a TOUS les camions encore en stock du
     * meme minerais, et pas au seul camion designe. Beaucoup de frais sont
     * factures au chargement (pont bascule, autorisation) et se repetent donc
     * a l'identique sur chaque camion d'un arrivage : les saisir un par un
     * etait fastidieux et source d'oublis. Chaque camion recoit malgre tout sa
     * PROPRE ligne de charge, avec ses propres ecritures : elle reste ensuite
     * modifiable ou supprimable camion par camion, la saisie groupee n'etant
     * qu'un raccourci de saisie, jamais un lien permanent entre les camions.
     *
     * <p>Les camions deja vendus sont exclus : leur cout d'acquisition est
     * fige, une depense posterieure releve des charges de la periode.</p>
     */
    Boolean appliquerATousLesCamions
) {}
