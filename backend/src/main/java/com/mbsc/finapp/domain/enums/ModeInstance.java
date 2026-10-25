package com.mbsc.finapp.domain.enums;

/**
 * Mode de fonctionnement de ce serveur (V132, voir {@code InstanceService}).
 */
public enum ModeInstance {
    /** Fonctionnement habituel : serveur principal, accessible par Internet. Par defaut. */
    EN_LIGNE,
    /** Serveur du reseau local de l'entreprise devenu principal : il recopie ses donnees vers le serveur en ligne. */
    LOCAL,
    /** Serveur en ligne devenu copie consultable du serveur local appaire (validations DFIN / DA relayees). */
    MIROIR,
    /** Ancien serveur local (mode desactive ou appairage revoque) : lecture seule, plus aucune saisie. */
    LOCAL_ARRETE;

    /** Vrai si les saisies sont refusees ici (copie en ligne, ancien serveur local). */
    public boolean lectureSeule() {
        return this == MIROIR || this == LOCAL_ARRETE;
    }
}
