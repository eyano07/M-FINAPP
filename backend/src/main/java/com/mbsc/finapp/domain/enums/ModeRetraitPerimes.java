package com.mbsc.finapp.domain.enums;

/**
 * Comment les plats périmés quittent le stock : à la main par le responsable (qui est alors prévenu par une notification),
 * ou automatiquement par la tâche planifiée. Choix de l'administrateur, voir {@code ParametresRestaurant}.
 */
public enum ModeRetraitPerimes {
    /** Une notification prévient le responsable, qui retire les portions périmées (Sorties de plats). */
    MANUEL,
    /** La tâche planifiée retire elle-même les portions périmées, puis prévient. */
    AUTOMATIQUE
}
