package com.mbsc.finapp.domain.enums;

/** Niveau d'acces d'un role a un {@link Module}, ordonne du plus faible au plus fort. */
public enum NiveauPermission {
    AUCUN,
    LECTURE,
    ECRITURE;

    public boolean auMoins(NiveauPermission requis) {
        return this.ordinal() >= requis.ordinal();
    }
}
