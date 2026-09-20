package com.mbsc.finapp.dto.restaurant;

import java.math.BigDecimal;

/**
 * @param coutMoyenActuel CMP courant de la provision, tous entrepots confondus.
 *                        Indicatif : le cout reellement retenu est celui du
 *                        moment de la production, dans son entrepot.
 * @param coutLigne       {@code quantite x coutMoyenActuel}, pour une portion.
 */
public record LigneRecetteResponse(
    Long id,
    Long provisionId,
    String provisionCode,
    String provisionLibelle,
    String uniteMesure,
    BigDecimal quantite,
    BigDecimal coutMoyenActuel,
    BigDecimal coutLigne
) {}
