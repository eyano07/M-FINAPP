package com.mbsc.finapp.domain.enums;

/** Cycle de vie d'un bien immobilise. */
public enum StatutImmobilisation {
    /** En service : le bien s'amortit, ses dotations sont comptabilisables. */
    EN_SERVICE,
    /** Cede : vendu a un tiers, sortie comptabilisee (812 / 822). */
    CEDE,
    /** Mis au rebut : sorti sans contrepartie, la VNC restante part en charge. */
    REBUT
}
