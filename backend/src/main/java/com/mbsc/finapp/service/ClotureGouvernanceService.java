package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.comptabilite.ClotureExerciceResponse;
import com.mbsc.finapp.dto.comptabilite.ControleClotureResponse;
import com.mbsc.finapp.dto.comptabilite.EtatClotureResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Qui fait quoi dans la clôture comptable (audit SYSCOHADA du 10/11/2026, C-03, C-06 et C-10).
 *
 * <ul>
 *   <li><b>Avancer le verrou de période</b> : le DFIN, immédiatement.</li>
 *   <li><b>Reculer ou lever le verrou</b> : le DFIN le demande avec un motif ; le DA ou le DG approuve ou refuse.
 *       Jamais en dessous de la dernière clôture d'exercice, qui est définitive.</li>
 *   <li><b>Clôturer l'exercice</b> : le DFIN le demande une fois les contrôles préalables satisfaits ; le DA ou le
 *       DG approuve, ce qui exécute la clôture (les contrôles sont refaits à ce moment).</li>
 *   <li><b>Dérogation à la validation par une autre personne</b> : le DA ou le DG, avec un motif.</li>
 * </ul>
 *
 * <p>Celui qui décide n'est jamais celui qui a demandé. L'ADMIN ne garde que le paramétrage technique : il n'a
 * aucun de ces droits. Chaque demande, décision et changement est inscrit à l'historique des clôtures et au
 * journal des événements comptables, tous deux en ajout seul.</p>
 */
@Service
@RequiredArgsConstructor
public class ClotureGouvernanceService {

    static final String RECUL_VERROU = "RECUL_VERROU";
    static final String EXERCICE = "EXERCICE";

    private final JdbcTemplate jdbc;
    private final PeriodeComptableService periodeService;
    private final ControlesClotureService controles;
    private final ClotureAnnuelleService clotureAnnuelle;
    private final JournalEvenementsComptables evenements;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN', 'CAISSIER')")
    @Transactional(readOnly = true)
    public EtatClotureResponse etat() {
        List<EtatClotureResponse.Demande> demandes = jdbc.query("""
            select d.id, d.type_demande, d.date_demandee, d.motif, d.statut, u.email, d.demande_par_id, d.demande_le,
                   v.email, d.decide_le, d.commentaire
            from demandes_cloture d join users u on u.id = d.demande_par_id left join users v on v.id = d.decide_par_id
            order by d.id desc limit 50
            """, (rs, i) -> new EtatClotureResponse.Demande(rs.getLong(1), rs.getString(2),
            rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
            rs.getObject(8, LocalDateTime.class), rs.getString(9), rs.getObject(10, LocalDateTime.class),
            rs.getString(11)));
        List<EtatClotureResponse.Historique> historique = jdbc.query("""
            select type_cloture, ancienne_date, nouvelle_date, motif, auteur, cree_le
            from historique_clotures order by id desc limit 50
            """, (rs, i) -> new EtatClotureResponse.Historique(rs.getString(1), rs.getObject(2, LocalDate.class),
            rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5),
            rs.getObject(6, LocalDateTime.class)));
        return new EtatClotureResponse(periodeService.dateCloture(), periodeService.dateClotureExercice(),
            periodeService.autoValidationAutorisee(), demandes, historique);
    }

    /**
     * Le DFIN déplace le verrou : avancé, il s'applique tout de suite ; reculé ou levé ({@code null}), il devient
     * une demande à faire approuver par le DA ou le DG.
     */
    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public EtatClotureResponse definirVerrou(LocalDate nouvelleDate, String motif) {
        User auteur = currentUser.requireUser();
        LocalDate actuel = periodeService.dateCloture();
        if (nouvelleDate != null && nouvelleDate.isAfter(LocalDate.now())) {
            throw new TransitionInvalideException("Le verrou de période ne peut pas dépasser aujourd'hui.");
        }
        boolean avance = nouvelleDate != null && (actuel == null || nouvelleDate.isAfter(actuel));
        if (avance) {
            periodeService.appliquerVerrou(nouvelleDate, "VERROU", texte(motif, "Verrou de période"), auteur);
            evenements.enregistrer("VERROU_AVANCE", "CLOTURE", null, String.valueOf(nouvelleDate),
                Map.of("verrou", String.valueOf(actuel)), Map.of("verrou", String.valueOf(nouvelleDate)), motif, auteur);
            return etat();
        }
        if (Objects.equals(nouvelleDate, actuel)) {
            return etat();
        }
        exigerMotif(motif);
        LocalDate exercice = periodeService.dateClotureExercice();
        if (exercice != null && (nouvelleDate == null || nouvelleDate.isBefore(exercice))) {
            throw new TransitionInvalideException("L'exercice est clôturé définitivement au " + exercice
                + " : le verrou ne peut pas redescendre en dessous.");
        }
        exigerAucuneDemandeEnAttente(RECUL_VERROU);
        Long id = creerDemande(RECUL_VERROU, nouvelleDate, motif, auteur);
        evenements.enregistrer("DEMANDE_RECUL_VERROU", "DEMANDE_CLOTURE", id, String.valueOf(nouvelleDate),
            Map.of("verrou", String.valueOf(actuel)), Map.of("verrouDemande", String.valueOf(nouvelleDate)), motif, auteur);
        return etat();
    }

    /** Contrôles préalables à la clôture de l'exercice qui se termine à {@code date}. */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ControleClotureResponse> controles(LocalDate date) {
        return controles.controler(date);
    }

    /** Le DFIN demande la clôture définitive de l'exercice ; refusé tant qu'un contrôle bloquant échoue. */
    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public EtatClotureResponse demanderClotureExercice(LocalDate date, String motif) {
        User auteur = currentUser.requireUser();
        List<ControleClotureResponse> resultat = controles.controler(date);
        if (!ControlesClotureService.clotureAutorisee(resultat)) {
            throw new TransitionInvalideException("Clôture de l'exercice impossible : "
                + ControlesClotureService.motifsDeRefus(resultat));
        }
        exigerAucuneDemandeEnAttente(EXERCICE);
        String m = texte(motif, "Clôture de l'exercice " + date.getYear());
        Long id = creerDemande(EXERCICE, date, m, auteur);
        evenements.enregistrer("DEMANDE_CLOTURE_EXERCICE", "DEMANDE_CLOTURE", id, String.valueOf(date), null,
            Map.of("dateCloture", date.toString()), m, auteur);
        return etat();
    }

    /** Le DA ou le DG approuve une demande : le verrou est reculé, ou l'exercice clôturé. */
    @PreAuthorize("hasAnyRole('DA', 'DG')")
    @Transactional
    public ClotureExerciceResponse approuver(Long id, String commentaire) {
        User decideur = currentUser.requireUser();
        Map<String, Object> d = demandeEnAttente(id, decideur);
        String type = (String) d.get("type_demande");
        LocalDate date = d.get("date_demandee") == null ? null : ((java.sql.Date) d.get("date_demandee")).toLocalDate();
        String motif = (String) d.get("motif");
        decider(id, "APPROUVEE", commentaire, decideur);
        ClotureExerciceResponse reponse = null;
        if (RECUL_VERROU.equals(type)) {
            periodeService.appliquerVerrou(date, "VERROU",
                motif + (StringUtils.hasText(commentaire) ? " — approuvé : " + commentaire : ""), decideur);
        } else {
            reponse = clotureAnnuelle.executer(date, motif, decideur);
        }
        Map<String, Object> apres = new LinkedHashMap<>();
        apres.put("statut", "APPROUVEE");
        apres.put("date", String.valueOf(date));
        if (reponse != null) {
            apres.put("pieceCloture", String.valueOf(reponse.pieceClotureReference()));
            apres.put("resultatNet", reponse.resultatNet());
        }
        evenements.enregistrer("APPROBATION_" + type, "DEMANDE_CLOTURE", id, String.valueOf(date),
            Map.of("statut", "EN_ATTENTE"), apres, commentaire, decideur);
        return reponse;
    }

    @PreAuthorize("hasAnyRole('DA', 'DG')")
    @Transactional
    public EtatClotureResponse refuser(Long id, String commentaire) {
        User decideur = currentUser.requireUser();
        exigerMotif(commentaire);
        Map<String, Object> d = demandeEnAttente(id, decideur);
        decider(id, "REFUSEE", commentaire, decideur);
        evenements.enregistrer("REFUS_" + d.get("type_demande"), "DEMANDE_CLOTURE", id,
            String.valueOf(d.get("date_demandee")), Map.of("statut", "EN_ATTENTE"), Map.of("statut", "REFUSEE"),
            commentaire, decideur);
        return etat();
    }

    /** Le demandeur retire sa demande tant qu'elle n'est pas décidée. */
    @PreAuthorize("hasRole('DFIN')")
    @Transactional
    public EtatClotureResponse retirer(Long id) {
        User auteur = currentUser.requireUser();
        int n = jdbc.update("update demandes_cloture set statut = 'RETIREE', decide_par_id = ?, decide_le = now() "
            + "where id = ? and statut = 'EN_ATTENTE' and demande_par_id = ?", auteur.getId(), id, auteur.getId());
        if (n == 0) {
            throw new TransitionInvalideException("Seul le demandeur retire une demande encore en attente.");
        }
        evenements.enregistrer("RETRAIT_DEMANDE", "DEMANDE_CLOTURE", id, null, Map.of("statut", "EN_ATTENTE"),
            Map.of("statut", "RETIREE"), null, auteur);
        return etat();
    }

    /** Le DA ou le DG pose ou retire la dérogation à la validation par une autre personne (C-06). */
    @PreAuthorize("hasAnyRole('DA', 'DG')")
    @Transactional
    public EtatClotureResponse definirAutoValidation(boolean autorisee, String motif) {
        User auteur = currentUser.requireUser();
        exigerMotif(motif);
        boolean avant = periodeService.autoValidationAutorisee();
        periodeService.definirAutoValidation(autorisee, motif, auteur);
        evenements.enregistrer("PARAMETRE_AUTO_VALIDATION", "PARAMETRE", null, "auto_validation_autorisee",
            Map.of("autoValidationAutorisee", avant), Map.of("autoValidationAutorisee", autorisee), motif, auteur);
        return etat();
    }

    // ---------------------------------------------------------------------

    private Long creerDemande(String type, LocalDate date, String motif, User auteur) {
        return jdbc.queryForObject("""
            insert into demandes_cloture (type_demande, date_demandee, motif, demande_par_id)
            values (?, ?, ?, ?) returning id
            """, Long.class, type, date, motif, auteur.getId());
    }

    private Map<String, Object> demandeEnAttente(Long id, User decideur) {
        List<Map<String, Object>> lignes = jdbc.queryForList(
            "select * from demandes_cloture where id = ? for update", id);
        if (lignes.isEmpty()) {
            throw RessourceIntrouvableException.of("DemandeCloture", id);
        }
        Map<String, Object> d = lignes.get(0);
        if (!"EN_ATTENTE".equals(d.get("statut"))) {
            throw new TransitionInvalideException("Cette demande est déjà " + d.get("statut") + ".");
        }
        if (decideur.getId().equals(((Number) d.get("demande_par_id")).longValue())) {
            throw new TransitionInvalideException(
                "Vous avez fait cette demande : elle doit être décidée par une autre personne (DA ou DG).");
        }
        return d;
    }

    private void decider(Long id, String statut, String commentaire, User decideur) {
        jdbc.update("update demandes_cloture set statut = ?, decide_par_id = ?, decide_le = now(), commentaire = ? "
            + "where id = ?", statut, decideur.getId(), commentaire, id);
    }

    private void exigerAucuneDemandeEnAttente(String type) {
        Long n = jdbc.queryForObject("select count(*) from demandes_cloture where type_demande = ? and statut = 'EN_ATTENTE'",
            Long.class, type);
        if (n != null && n > 0) {
            throw new TransitionInvalideException("Une demande du même type attend déjà une décision.");
        }
    }

    private static void exigerMotif(String motif) {
        if (!StringUtils.hasText(motif) || motif.trim().length() < 5) {
            throw new IllegalArgumentException("Un motif est obligatoire (au moins 5 caractères).");
        }
    }

    private static String texte(String motif, String defaut) {
        return StringUtils.hasText(motif) ? motif.trim() : defaut;
    }
}
