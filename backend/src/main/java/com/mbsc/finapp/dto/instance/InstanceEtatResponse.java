package com.mbsc.finapp.dto.instance;

import java.time.Instant;

/**
 * Etat du serveur pour tout utilisateur connecte (bandeau de la copie en ligne, pastille du serveur local).
 *
 * @param mode           EN_LIGNE, LOCAL, MIROIR ou LOCAL_ARRETE
 * @param lectureSeule   vrai si les saisies sont refusees ici
 * @param derniereSynchro derniere synchronisation reussie (null : jamais)
 * @param enAttente      cote local : changements pas encore envoyes au serveur en ligne
 * @param horsConnexion  cote local : la derniere tentative de synchronisation a echoue
 */
public record InstanceEtatResponse(
    String mode,
    boolean lectureSeule,
    Instant derniereSynchro,
    long enAttente,
    boolean horsConnexion
) {}
