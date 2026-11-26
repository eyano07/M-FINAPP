package com.mbsc.hotellerie.canal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture et écriture des calendriers iCalendar (RFC 5545) échangés avec Booking.com, Airbnb et Expedia/VRBO. Seules
 * les disponibilités circulent (dates occupées) : ni prix ni identité du client — c'est la limite du format.
 */
public final class Ical {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HORODATAGE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private Ical() {
    }

    /** Un événement du calendrier : identifiant, dates (départ exclu), résumé, annulé ou non. */
    public record Evenement(String uid, LocalDate debut, LocalDate fin, String resume, boolean annule) { }

    /** Lit les VEVENT d'un calendrier (lignes repliées, dates seules ou horodatées, UTC ou locales). */
    public static List<Evenement> lire(String texte, ZoneId fuseau) {
        List<Evenement> evenements = new ArrayList<>();
        if (texte == null) return evenements;
        List<String> lignes = deplier(texte);
        String uid = null, resume = null, statut = null;
        LocalDate debut = null, fin = null;
        boolean dans = false;
        for (String l : lignes) {
            if (l.equalsIgnoreCase("BEGIN:VEVENT")) {
                dans = true;
                uid = resume = statut = null;
                debut = fin = null;
            } else if (l.equalsIgnoreCase("END:VEVENT")) {
                if (dans && debut != null) {
                    LocalDate f = fin == null || !fin.isAfter(debut) ? debut.plusDays(1) : fin;
                    evenements.add(new Evenement(uid == null ? debut + "/" + f : uid, debut, f, resume,
                        "CANCELLED".equalsIgnoreCase(statut)));
                }
                dans = false;
            } else if (dans) {
                int deuxPoints = l.indexOf(':');
                if (deuxPoints < 0) continue;
                String nom = l.substring(0, deuxPoints);
                String valeur = l.substring(deuxPoints + 1).trim();
                String propriete = nom.contains(";") ? nom.substring(0, nom.indexOf(';')) : nom;
                switch (propriete.toUpperCase()) {
                    case "UID" -> uid = valeur;
                    case "SUMMARY" -> resume = deseschapper(valeur);
                    case "STATUS" -> statut = valeur;
                    case "DTSTART" -> debut = date(nom, valeur, fuseau);
                    case "DTEND" -> fin = date(nom, valeur, fuseau);
                    default -> { }
                }
            }
        }
        return evenements;
    }

    /** Calendrier d'une unité : une période occupée par réservation (dates seules, départ exclu). */
    public static String ecrire(String nomCalendrier, List<Evenement> occupations) {
        StringBuilder b = new StringBuilder();
        ligne(b, "BEGIN:VCALENDAR");
        ligne(b, "VERSION:2.0");
        ligne(b, "PRODID:-//M-FINAPP//Hotellerie//FR");
        ligne(b, "CALSCALE:GREGORIAN");
        ligne(b, "METHOD:PUBLISH");
        ligne(b, "X-WR-CALNAME:" + echapper(nomCalendrier));
        String maintenant = OffsetDateTime.now(ZoneOffset.UTC).format(HORODATAGE);
        for (Evenement e : occupations) {
            ligne(b, "BEGIN:VEVENT");
            ligne(b, "UID:" + e.uid());
            ligne(b, "DTSTAMP:" + maintenant);
            ligne(b, "DTSTART;VALUE=DATE:" + e.debut().format(JOUR));
            ligne(b, "DTEND;VALUE=DATE:" + e.fin().format(JOUR));
            ligne(b, "SUMMARY:" + echapper(e.resume() == null ? "Réservé" : e.resume()));
            ligne(b, "TRANSP:OPAQUE");
            ligne(b, "END:VEVENT");
        }
        ligne(b, "END:VCALENDAR");
        return b.toString();
    }

    static List<String> deplier(String texte) {
        List<String> lignes = new ArrayList<>();
        for (String brute : texte.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if ((brute.startsWith(" ") || brute.startsWith("\t")) && !lignes.isEmpty()) {
                lignes.set(lignes.size() - 1, lignes.get(lignes.size() - 1) + brute.substring(1));
            } else if (!brute.isBlank()) {
                lignes.add(brute.trim());
            }
        }
        return lignes;
    }

    static LocalDate date(String nom, String valeur, ZoneId fuseau) {
        String v = valeur.trim();
        try {
            if (v.length() == 8) return LocalDate.parse(v, JOUR);
            if (v.endsWith("Z")) {
                return LocalDateTime.parse(v.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .atOffset(ZoneOffset.UTC).atZoneSameInstant(fuseau).toLocalDate();
            }
            return LocalDateTime.parse(v.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void ligne(StringBuilder b, String contenu) {
        // Lignes de 75 octets au plus, repliées (RFC 5545 §3.1).
        String reste = contenu;
        boolean premiere = true;
        while (reste.length() > 73) {
            b.append(premiere ? "" : " ").append(reste, 0, 73).append("\r\n");
            reste = reste.substring(73);
            premiere = false;
        }
        b.append(premiere ? "" : " ").append(reste).append("\r\n");
    }

    private static String echapper(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String deseschapper(String s) {
        return s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }
}
