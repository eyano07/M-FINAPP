package com.mbsc.finapp.dto.comptabilite;

import java.math.BigDecimal;

/**
 * Résultat analytique d'une activité sur la période (devise de base) : chiffre d'affaires HT (98x1, crédits moins débits),
 * coût des ventes (98x2, débits moins crédits), marge et taux de marge en % du chiffre d'affaires ({@code null} sans
 * chiffre d'affaires). Une vente annulée dans la période se compense avec sa contre-passation.
 */
public record ResultatActiviteResponse(
    String activite,
    String libelle,
    String compteNumero,
    BigDecimal chiffreAffaires,
    BigDecimal coutDesVentes,
    BigDecimal marge,
    BigDecimal tauxMarge
) {}
