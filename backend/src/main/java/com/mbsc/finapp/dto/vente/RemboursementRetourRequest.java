package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.enums.CanalRemboursement;

import java.time.LocalDate;

/**
 * Remboursement d'un retour approuve (caissier).
 *
 * @param canal           CAISSE, BANQUE ou MOBILE_MONEY ; absent, celui du reglement de la vente. Ignore pour une vente a
 *                        credit pas encore reglee : sa creance diminue (CREANCE), aucun argent ne sort
 * @param etablissementId banque ou operateur, pour BANQUE et MOBILE_MONEY (absent : celui de la vente si c'est le meme canal)
 * @param dateRemboursement aujourd'hui si absente
 */
public record RemboursementRetourRequest(
    CanalRemboursement canal,
    Long etablissementId,
    LocalDate dateRemboursement
) {}
