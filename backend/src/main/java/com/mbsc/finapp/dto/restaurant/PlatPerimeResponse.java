package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Portions d'un plat périmées dans un entrepôt : la date d'expiration de leur production est passée et elles n'ont pas
 * été vendues (voir {@code PeremptionPlats}).
 *
 * @param quantitePerimee portions périmées encore en stock
 * @param coutMoyen       coût de production moyen d'une portion, en USD (devise du grand livre)
 * @param valeur          coût qui sortira du stock au retrait : quantité × coût moyen
 * @param perimeDepuis    la plus ancienne des dates d'expiration dépassées
 * @param lots            productions concernées, de la plus récente à la plus ancienne
 */
public record PlatPerimeResponse(
    Long platId,
    String platCode,
    String platLibelle,
    String uniteMesure,
    Long entrepotId,
    String entrepotCode,
    BigDecimal quantitePerimee,
    BigDecimal coutMoyen,
    BigDecimal valeur,
    LocalDate perimeDepuis,
    List<LotPerime> lots
) {
    /** Part périmée d'une production. */
    public record LotPerime(
        String productionReference,
        LocalDate dateProduction,
        LocalDate dateExpiration,
        BigDecimal quantite
    ) {}
}
