package com.mbsc.finapp.service.emcf;

/** Dispositif de facturation normalisée (e-MCF) : certification d'une facture et test de connexion. */
public interface EmcfClient {

    /** @param json contenu déjà sérialisé au format d'échange (voir {@link EmcfJson}) */
    EmcfReponse certifier(ConfigEmcf config, EmcfDemande demande, String json) throws ErreurEmcf;

    /** Vérifie que le dispositif répond ; renvoie un message à afficher. */
    String tester(ConfigEmcf config) throws ErreurEmcf;
}
