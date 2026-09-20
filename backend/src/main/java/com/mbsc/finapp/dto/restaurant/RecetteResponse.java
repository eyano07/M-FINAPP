package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param coutEstimeParPortion somme des couts de ligne au CMP courant. Une
 *                             estimation d'aide a la decision (marge theorique),
 *                             pas le cout de revient comptable : celui-la ne
 *                             nait qu'a la production, du CMP reel du moment.
 * @param prixVente            prix de vente du plat (en FC), pour situer la marge.
 */
public record RecetteResponse(
    Long platId,
    String platCode,
    String platLibelle,
    String uniteMesure,
    BigDecimal prixVente,
    List<LigneRecetteResponse> lignes,
    BigDecimal coutEstimeParPortion
) {}
