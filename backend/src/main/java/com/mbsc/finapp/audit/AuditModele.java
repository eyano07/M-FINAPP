package com.mbsc.finapp.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Types de l'écran d'audit : filtre, événement lu dans audit.log, synthèse. */
public final class AuditModele {

    private AuditModele() {}

    public record Filtre(LocalDate du, LocalDate au, String utilisateur, String module, String operation,
                         String resultat, String type, String q, String terminal, String systeme) {

        /** Filtre sans critère de terminal ni de système. */
        public Filtre(LocalDate du, LocalDate au, String utilisateur, String module, String operation,
                      String resultat, String type, String q) {
            this(du, au, utilisateur, module, operation, resultat, type, q, null, null);
        }
    }

    /**
     * Événement lu dans audit.log. {@code agent} est l'en-tête User-Agent brut ; {@code terminal} (Ordinateur, Mobile,
     * Tablette, Application) et {@code systeme} (Windows, Android, iOS...) en sont déduits à la lecture, jamais nuls :
     * « Inconnu » quand l'en-tête est absent (connexions antérieures à son enregistrement) ou non reconnu.
     */
    public record Evenement(Instant horodatage, String type, Long utilisateurId, String email, List<String> roles,
                            String ip, String module, String operation, String ressourceId, String methode,
                            String chemin, String requete, Integer statut, boolean reussi, Long dureeMs, String detail,
                            String agent, String terminal, String systeme) {}

    public record Compte(String cle, long total, long echecs) {}

    public record Jour(LocalDate jour, long total, long echecs) {}

    public record Synthese(LocalDate du, LocalDate au, long total, long echecs, long connexionsRefusees, long utilisateursActifs,
                           Instant premier, Instant dernier, boolean tronque,
                           List<Compte> parUtilisateur, List<Compte> parModule, List<Compte> parOperation,
                           List<Compte> parTerminal, List<Compte> parSysteme, List<Jour> parJour) {}

    public record Page(long total, int page, int taille, boolean tronque, List<Evenement> evenements) {}
}
