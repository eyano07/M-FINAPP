package com.mbsc.finapp.domain.enums;

/**
 * Façon dont une sortie de stock (vente, casse, péremption) se répartit sur
 * les lots d'un article — voir {@code LotStockService}. Purement un outil de
 * gestion/traçabilité : n'affecte jamais la comptabilité, qui reste au coût
 * moyen pondéré.
 *
 * <p>Plus un paramètre : la méthode est fixée par type d'article, FIFO pour
 * les boissons et CMP pour les provisions (voir {@code
 * LotStockService.methode}, migration V93). Seules les deux méthodes
 * reconnues par le SYSCOHADA pour les biens interchangeables existent ; le
 * LIFO a été retiré (migration V90).</p>
 */
public enum MethodeSortieLots {
    /** Premier entré, premier sorti : le lot le plus ancien s'épuise en premier. */
    FIFO,
    /**
     * Coût moyen pondéré : chaque sortie prélève sur tous les lots au prorata
     * de ce qu'il leur reste, comme si chaque unité était un mélange de tous
     * les lots. Chaque lot perd la même proportion, et la valeur des lots suit
     * celle du stock en comptabilité, qui sort elle aussi au coût moyen.
     */
    CMP
}
