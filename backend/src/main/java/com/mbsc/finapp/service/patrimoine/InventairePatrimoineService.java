package com.mbsc.finapp.service.patrimoine;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.ModeAmortissement;
import com.mbsc.finapp.dto.patrimoine.ImmobilisationRequest;
import com.mbsc.finapp.dto.patrimoine.ImmobilisationResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.PatrimoineService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Inventaire du patrimoine (V153) : biens relevés sur une fiche d'inventaire, un par unité, chacun avec le code collé
 * dessus. Le relevé n'a ni valeur ni date ; le responsable logistique les saisit (« valoriser ») :
 * <ul>
 *   <li>valeur ≥ {@value #SEUIL_USD} USD : le bien entre en immobilisation — fiche d'amortissement SYSCOHADA (compte,
 *       durée d'usage de sa catégorie), pièce d'acquisition D 2xxx / C 1013 (apport et régularisation de biens
 *       jamais inscrits) ;</li>
 *   <li>valeur inférieure : petit matériel de faible valeur, suivi à l'inventaire, sans immobilisation ni écriture
 *       (déjà passé en charges à l'achat, ou à passer en 6056 par une note de frais).</li>
 * </ul>
 * Les dotations se comptabilisent ensuite, pour tous les biens, par « Comptabiliser les dotations » (Amortissements).
 */
@Service
@RequiredArgsConstructor
public class InventairePatrimoineService {

    /** Seuil d'immobilisation (USD) décidé le 19/11/2026 : en dessous, petit matériel de faible valeur. */
    public static final int SEUIL_USD = 100;
    /** Contrepartie de l'entrée au bilan des biens inventoriés : apport / régularisation (capital, 1013). */
    public static final String CONTREPARTIE = "1013";
    static final String ECRITURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'ADMIN')";
    static final String LECTURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";

    private final JdbcTemplate jdbc;
    private final PatrimoineService patrimoine;
    private final CurrentUserProvider currentUser;

    public record Bien(Long id, String code, String libelle, CategorieImmobilisation categorie, int dureeMois,
                       String localisation, String etat, String statut, BigDecimal valeurUsd, LocalDate dateAcquisition,
                       LocalDate dateMiseService, Long immobilisationId, String immobilisationReference, String source,
                       Integer ligneSource) { }

    private static final RowMapper<Bien> LIGNE = (rs, i) -> new Bien(rs.getLong("id"), rs.getString("code"),
        rs.getString("libelle"), CategorieImmobilisation.valueOf(rs.getString("categorie")), rs.getInt("duree_mois"),
        rs.getString("localisation"), rs.getString("etat"), rs.getString("statut"), rs.getBigDecimal("valeur_usd"),
        rs.getDate("date_acquisition") == null ? null : rs.getDate("date_acquisition").toLocalDate(),
        rs.getDate("date_mise_service") == null ? null : rs.getDate("date_mise_service").toLocalDate(),
        (Long) rs.getObject("immobilisation_id"), rs.getString("reference"), rs.getString("source"),
        (Integer) rs.getObject("ligne_source"));

    private static final String SELECT = """
        select b.*, i.reference from inventaire_biens b left join immobilisations i on i.id = b.immobilisation_id
        """;

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Bien> lister() {
        return jdbc.query(SELECT + " order by b.localisation, b.code", LIGNE);
    }

    private Bien bien(Long id) {
        return jdbc.query(SELECT + " where b.id = ?", LIGNE, id).stream().findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Bien inventorié", id));
    }

    public record CorrectionRequest(@NotNull @Size(max = 200) String libelle, @NotNull CategorieImmobilisation categorie,
                                    @Positive Integer dureeMois, @Size(max = 255) String localisation,
                                    @Size(max = 60) String etat) { }

    /** Correction d'un bien encore à valoriser (désignation, catégorie, durée proposée, emplacement, état). */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Bien corriger(Long id, CorrectionRequest r) {
        Bien b = bien(id);
        if (!"A_VALORISER".equals(b.statut())) {
            throw new TransitionInvalideException("Le bien " + b.code() + " est déjà valorisé : sa fiche se modifie depuis les immobilisations.");
        }
        jdbc.update("""
            update inventaire_biens set libelle = ?, categorie = ?, duree_mois = ?, localisation = ?, etat = ?, maj_le = now(), maj_par = ?
            where id = ?
            """, r.libelle().strip(), r.categorie().name(), r.dureeMois() == null ? r.categorie().dureeMoisParDefaut() : r.dureeMois(),
            r.localisation(), r.etat(), currentUser.requireUser().getEmail(), id);
        return bien(id);
    }

    public record ValorisationRequest(@NotNull @Positive BigDecimal valeurUsd, @NotNull LocalDate dateAcquisition,
                                      LocalDate dateMiseService, CategorieImmobilisation categorie, @Positive Integer dureeMois,
                                      ModeAmortissement modeAmortissement, @Size(max = 200) String fournisseur,
                                      @Size(max = 100) String numeroSerie) { }

    /**
     * Valeur et date d'un bien : immobilisation (fiche d'amortissement + pièce d'acquisition D 2xxx / C 1013) au-dessus
     * du seuil, petit matériel en dessous.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Bien valoriser(Long id, ValorisationRequest r) {
        Bien b = bien(id);
        if (!"A_VALORISER".equals(b.statut())) {
            throw new TransitionInvalideException("Le bien " + b.code() + " est déjà valorisé.");
        }
        LocalDate miseService = r.dateMiseService() == null ? r.dateAcquisition() : r.dateMiseService();
        if (miseService.isBefore(r.dateAcquisition())) {
            throw new IllegalArgumentException("La mise en service ne peut pas précéder l'acquisition.");
        }
        CategorieImmobilisation categorie = r.categorie() == null ? b.categorie() : r.categorie();
        int duree = r.dureeMois() != null ? r.dureeMois() : (r.categorie() != null && r.categorie() != b.categorie()
            ? categorie.dureeMoisParDefaut() : b.dureeMois());
        String auteur = currentUser.requireUser().getEmail();
        if (r.valeurUsd().compareTo(BigDecimal.valueOf(SEUIL_USD)) < 0) {
            jdbc.update("""
                update inventaire_biens set statut = 'PETIT_MATERIEL', valeur_usd = ?, date_acquisition = ?, date_mise_service = ?,
                categorie = ?, duree_mois = ?, maj_le = now(), maj_par = ? where id = ?
                """, r.valeurUsd(), r.dateAcquisition(), miseService, categorie.name(), duree, auteur, id);
            AuditService.detailler("Inventaire " + b.code() + " (" + b.libelle() + ") : " + r.valeurUsd()
                + " USD, sous le seuil de " + SEUIL_USD + " USD — petit matériel, non immobilisé.");
            return bien(id);
        }
        String description = "Inventaire " + (b.source() == null ? "" : b.source() + " ")
            + (b.ligneSource() == null ? "" : "ligne " + b.ligneSource()) + (b.etat() == null ? "" : " — état : " + b.etat())
            + " — code " + b.code();
        ImmobilisationResponse immo = patrimoine.creer(new ImmobilisationRequest(b.libelle(), categorie, description, null, null,
            null, r.dateAcquisition(), miseService, r.valeurUsd(), BigDecimal.ZERO, duree,
            r.modeAmortissement() == null ? ModeAmortissement.LINEAIRE : r.modeAmortissement(), b.localisation(), null,
            r.fournisseur(), r.numeroSerie(), true, CONTREPARTIE, null));
        patrimoine.lierCodeInventaireInterne(immo.id(), b.code());
        jdbc.update("""
            update inventaire_biens set statut = 'IMMOBILISE', valeur_usd = ?, date_acquisition = ?, date_mise_service = ?,
            categorie = ?, duree_mois = ?, immobilisation_id = ?, maj_le = now(), maj_par = ? where id = ?
            """, r.valeurUsd(), r.dateAcquisition(), miseService, categorie.name(), duree, immo.id(), auteur, id);
        AuditService.detailler("Inventaire " + b.code() + " immobilisé : " + immo.reference() + ", " + r.valeurUsd()
            + " USD, " + duree + " mois, contrepartie " + CONTREPARTIE + ".");
        return bien(id);
    }
}
