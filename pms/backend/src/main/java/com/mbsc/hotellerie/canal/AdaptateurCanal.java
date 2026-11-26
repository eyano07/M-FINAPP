package com.mbsc.hotellerie.canal;

import java.util.List;

/**
 * Source des réservations d'un canal de vente. La phase 2 lit les calendriers iCal ({@link AdaptateurIcal}) ; une API
 * certifiée (Booking.com Connectivity, Airbnb, Expedia) ou un agrégateur se brancheront derrière la même interface.
 */
public interface AdaptateurCanal {

    /** Événements actuellement publiés par le canal pour cette adresse. */
    List<Ical.Evenement> lire(String url);
}
