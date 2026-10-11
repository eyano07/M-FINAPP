package com.mbsc.finapp.dto.restaurant;

/**
 * Retrait du stock des portions périmées, enregistrées comme sorties « Périmé ». Les deux filtres sont facultatifs :
 * sans eux, tous les plats périmés sont retirés.
 *
 * @param platId     ne retirer que ce plat
 * @param entrepotId ne retirer que ce qui se trouve dans cet entrepôt
 */
public record RetraitPerimesRequest(
    Long platId,
    Long entrepotId
) {}
