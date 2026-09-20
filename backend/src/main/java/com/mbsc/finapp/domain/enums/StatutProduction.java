package com.mbsc.finapp.domain.enums;

/** Cycle de vie d'une production de plats. */
public enum StatutProduction {
    /**
     * Produite : les ingredients sont sortis du stock et les portions y sont
     * entrees, chacune avec sa piece comptable. Une production nait dans cet
     * etat — il n'y a pas de brouillon, la saisie et l'effet sont le meme
     * instant (meme convention que les mouvements internes de StockService).
     */
    VALIDEE,
    /** Extournee : les deux mouvements de stock ont ete annules. */
    ANNULEE
}
