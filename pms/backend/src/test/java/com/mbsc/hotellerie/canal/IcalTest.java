package com.mbsc.hotellerie.canal;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcalTest {

    private static final ZoneId LUBUMBASHI = ZoneId.of("Africa/Lubumbashi");

    @Test
    void litUnCalendrierAirbnbEtBooking() {
        String airbnb = """
            BEGIN:VCALENDAR\r
            PRODID:-//Airbnb Inc//Hosting Calendar 1.0//EN\r
            BEGIN:VEVENT\r
            DTEND;VALUE=DATE:20261205\r
            DTSTART;VALUE=DATE:20261201\r
            UID:1418fb94e984-7ed2c3f8d0e7b7e2a7c5c2ea4d1c9b3b@airbnb.com\r
            SUMMARY:Reserved\r
            END:VEVENT\r
            BEGIN:VEVENT\r
            DTSTART:20261210T120000Z\r
            DTEND:20261212T100000Z\r
            UID:abc\r
             -suite@booking.com\r
            SUMMARY:CLOSED - Not available\r
            STATUS:CANCELLED\r
            END:VEVENT\r
            END:VCALENDAR\r
            """;
        List<Ical.Evenement> e = Ical.lire(airbnb, LUBUMBASHI);
        assertThat(e).hasSize(2);
        assertThat(e.get(0).debut()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(e.get(0).fin()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(e.get(0).annule()).isFalse();
        assertThat(e.get(1).uid()).isEqualTo("abc-suite@booking.com");
        assertThat(e.get(1).debut()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(e.get(1).annule()).isTrue();
    }

    @Test
    void ecritUnCalendrierRelisible() {
        String texte = Ical.ecrire("Hôtel du Lac — 101", List.of(
            new Ical.Evenement("RES-2026-000001@hotellerie", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 23), "Réservé", false)));
        assertThat(texte).startsWith("BEGIN:VCALENDAR\r\n").contains("DTSTART;VALUE=DATE:20261120").contains("DTEND;VALUE=DATE:20261123");
        List<Ical.Evenement> relu = Ical.lire(texte, LUBUMBASHI);
        assertThat(relu).singleElement().satisfies(x -> {
            assertThat(x.uid()).isEqualTo("RES-2026-000001@hotellerie");
            assertThat(x.fin()).isEqualTo(LocalDate.of(2026, 11, 23));
        });
        for (String ligne : texte.split("\r\n")) assertThat(ligne.length()).isLessThanOrEqualTo(75);
    }

    @Test
    void refuseLesAdressesInternes() {
        for (String url : List.of("http://backend:8080/api", "http://localhost/x.ics", "http://127.0.0.1/x", "ftp://exemple.com/a.ics")) {
            assertThatThrownBy(() -> AdaptateurIcal.verifier(url)).as(url).hasMessageContaining("invalide");
        }
        assertThat(AdaptateurIcal.verifier("https://www.airbnb.com/calendar/ical/1.ics").getHost()).isEqualTo("www.airbnb.com");
    }
}
