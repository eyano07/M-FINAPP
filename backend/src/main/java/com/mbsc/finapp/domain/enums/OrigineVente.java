package com.mbsc.finapp.domain.enums;

/**
 * D'où vient une vente (V152) : la caisse de M-FINAPP (vente, commande du restaurant) ou l'application Hôtellerie
 * (folio de séjour encaissé par la réception). L'origine ne change ni les écritures ni le circuit ; elle permet de
 * retrouver les folios et de réserver leur encaissement à la réception.
 */
public enum OrigineVente {
    CAISSE,
    HOTELLERIE
}
