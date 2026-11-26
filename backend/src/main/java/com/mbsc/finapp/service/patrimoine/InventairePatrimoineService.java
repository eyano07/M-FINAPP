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
 *   <li>bien durable, utilisé au-delà d'un exercice (principe SYSCOHADA, quelle que soit sa valeur) : il entre en
 *       immobilisation — fiche d'amortissement (compte, durée d'usage de sa catégorie), pièce d'acquisition
 *       D 2xxx / C 1013 (apport et régularisation de biens jamais inscrits) ;</li>
 *   <li>bien consommé dans l'exercice : non amortissable, suivi à l'inventaire sans immobilisation ni écriture (déjà
 *       passé en charges à l'achat). Un tel bien peut être immobilisé ensuite s'il s'avère durable.</li>
 * </ul>
 * Les dotations se comptabilisent ensuite, pour tous les biens, par « Comptabiliser les dotations » (Amortissements).
 */
@Service
@RequiredArgsConstructor
public class InventairePatrimoineService {

    /** Contrepartie de l'entrée au bilan des biens inventoriés : apport / régularisation (capital, 1013). */
    public static final String CONTREPARTIE = "1013";
    static final String ECRITURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'ADMIN')";
    static final String LECTURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";
    /** Choix immobilisé / non amortissable d'un bien déjà valorisé : décision comptable (comptable, DFIN). */
    static final String CLASSEMENT = "hasAnyRole('COMPTABLE', 'DFIN')";
    private static final java.util.Set<String> AUTORITES_CLASSEMENT = java.util.Set.of("ROLE_COMPTABLE", "ROLE_DFIN");

    private final JdbcTemplate jdbc;
    private final PatrimoineService patrimoine;
    private final CurrentUserProvider currentUser;
    private final com.mbsc.finapp.service.StorageService stockage;
    private final com.mbsc.finapp.service.ComptabiliteService comptabilite;
    private final com.mbsc.finapp.repository.CompteOHADARepository comptes;

    /**
     * {@code localisation} : lieu relevé sur la fiche d'inventaire (texte libre) ; {@code emplacementId} : affectation
     * tenue par la logistique (bureau, salle, chambre ou appartement de l'hôtellerie…), facultative.
     */
    public record Bien(Long id, String code, String libelle, CategorieImmobilisation categorie, int dureeMois,
                       String localisation, String etat, String statut, BigDecimal valeurUsd, LocalDate dateAcquisition,
                       LocalDate dateMiseService, Long immobilisationId, String immobilisationReference, String source,
                       Integer ligneSource, Long emplacementId, String emplacementNom, String emplacementType, int nbPhotos,
                       String factureNom, String factureType) { }

    private static final RowMapper<Bien> LIGNE = (rs, i) -> new Bien(rs.getLong("id"), rs.getString("code"),
        rs.getString("libelle"), CategorieImmobilisation.valueOf(rs.getString("categorie")), rs.getInt("duree_mois"),
        rs.getString("localisation"), rs.getString("etat"), rs.getString("statut"), rs.getBigDecimal("valeur_usd"),
        rs.getDate("date_acquisition") == null ? null : rs.getDate("date_acquisition").toLocalDate(),
        rs.getDate("date_mise_service") == null ? null : rs.getDate("date_mise_service").toLocalDate(),
        (Long) rs.getObject("immobilisation_id"), rs.getString("reference"), rs.getString("source"),
        (Integer) rs.getObject("ligne_source"), (Long) rs.getObject("emplacement_id"), rs.getString("emplacement_nom"),
        rs.getString("emplacement_type"), rs.getInt("nb_photos"), rs.getString("facture_nom"), rs.getString("facture_type"));

    private static final String SELECT = """
        select b.*, i.reference, e.nom as emplacement_nom, e.type as emplacement_type,
               (select count(*) from inventaire_photos p where p.bien_id = b.id) as nb_photos
        from inventaire_biens b left join immobilisations i on i.id = b.immobilisation_id
        left join emplacements e on e.id = b.emplacement_id
        """;

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Bien> lister() {
        return jdbc.query(SELECT + " order by coalesce(e.nom, b.localisation), b.code", LIGNE);
    }

    private Bien bien(Long id) {
        return jdbc.query(SELECT + " where b.id = ?", LIGNE, id).stream().findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Bien inventorié", id));
    }

    public record AffectationRequest(@jakarta.validation.constraints.NotEmpty List<Long> biens, Long emplacementId) { }

    /**
     * Affecte des biens à un emplacement (ou les en retire : {@code emplacementId} vide), à toute étape : un bien
     * valorisé change aussi de bureau ou de chambre.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public List<Bien> affecter(AffectationRequest r) {
        String nom = null;
        if (r.emplacementId() != null) {
            nom = jdbc.query("select nom from emplacements where id = ? and actif", rs -> rs.next() ? rs.getString(1) : null, r.emplacementId());
            if (nom == null) throw new IllegalArgumentException("Emplacement introuvable ou désactivé.");
        }
        String auteur = currentUser.requireUser().getEmail();
        for (Long id : r.biens()) {
            jdbc.update("update inventaire_biens set emplacement_id = ?, maj_le = now(), maj_par = ? where id = ?", r.emplacementId(), auteur, id);
        }
        AuditService.detailler(r.biens().size() + " bien(s) " + (nom == null ? "retiré(s) de leur emplacement" : "affecté(s) à « " + nom + " »") + ".");
        // Identifiants numériques (List<Long>) : la liste se compose sans risque d'injection.
        String ids = r.biens().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return jdbc.query(SELECT + " where b.id in (" + ids + ")", LIGNE);
    }

    // ── Photos des biens ────────────────────────────────────────────────────

    private static final java.util.Set<String> TYPES_PHOTO = java.util.Set.of("image/jpeg", "image/png", "image/webp");

    public record Photo(Long id, Long bienId, String nomOrigine, String creePar, java.time.OffsetDateTime creeLe) { }

    public record FichierPhoto(org.springframework.core.io.Resource ressource, String typeContenu) { }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Photo> photos(Long bienId) {
        return jdbc.query("select * from inventaire_photos where bien_id = ? order by cree_le", (rs, i) -> new Photo(rs.getLong("id"),
            rs.getLong("bien_id"), rs.getString("nom_origine"), rs.getString("cree_par"), rs.getObject("cree_le", java.time.OffsetDateTime.class)), bienId);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Photo ajouterPhoto(Long bienId, org.springframework.web.multipart.MultipartFile fichier) {
        Bien b = bien(bienId);
        if (fichier == null || fichier.isEmpty()) throw new IllegalArgumentException("Photo vide.");
        String type = fichier.getContentType() == null ? "" : fichier.getContentType();
        if (!TYPES_PHOTO.contains(type)) throw new IllegalArgumentException("Photo : JPEG, PNG ou WebP seulement.");
        if (fichier.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("Photo trop lourde (10 Mo au plus).");
        String chemin = stockage.enregistrer("patrimoine/photos", fichier);
        Long id = jdbc.queryForObject("""
            insert into inventaire_photos (bien_id, chemin, nom_origine, type_contenu, cree_par) values (?, ?, ?, ?, ?) returning id
            """, Long.class, bienId, chemin, fichier.getOriginalFilename(), type, currentUser.requireUser().getEmail());
        AuditService.detailler("Photo ajoutée au bien " + b.code() + ".");
        return photos(bienId).stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public FichierPhoto fichierPhoto(Long bienId, Long photoId) {
        return jdbc.query("select chemin, type_contenu from inventaire_photos where id = ? and bien_id = ?",
            rs -> rs.next() ? new FichierPhoto(stockage.charger(rs.getString(1)), rs.getString(2)) : null, photoId, bienId);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public void supprimerPhoto(Long bienId, Long photoId) {
        String chemin = jdbc.query("select chemin from inventaire_photos where id = ? and bien_id = ?", rs -> rs.next() ? rs.getString(1) : null,
            photoId, bienId);
        if (chemin == null) throw RessourceIntrouvableException.of("Photo", photoId);
        jdbc.update("delete from inventaire_photos where id = ?", photoId);
        try {
            stockage.supprimer(chemin);
        } catch (RuntimeException e) {
            // Fichier resté sur le disque : sans conséquence, la référence est retirée.
        }
        AuditService.detailler("Photo retirée du bien " + bien(bienId).code() + ".");
    }

    // ── Facture d'acquisition (V155) : une image ou un PDF par bien ──────────

    private static final java.util.Set<String> TYPES_FACTURE = java.util.Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    /** Joint (ou remplace) la facture d'un bien : image JPEG, PNG, WebP ou PDF, 15 Mo au plus. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Bien joindreFacture(Long bienId, org.springframework.web.multipart.MultipartFile fichier) {
        Bien b = bien(bienId);
        if (fichier == null || fichier.isEmpty()) throw new IllegalArgumentException("Facture vide.");
        String type = fichier.getContentType() == null ? "" : fichier.getContentType();
        if (!TYPES_FACTURE.contains(type)) throw new IllegalArgumentException("Facture : image (JPEG, PNG, WebP) ou PDF seulement.");
        if (fichier.getSize() > 15L * 1024 * 1024) throw new IllegalArgumentException("Facture trop lourde (15 Mo au plus).");
        String ancien = jdbc.query("select facture_chemin from inventaire_biens where id = ?", rs -> rs.next() ? rs.getString(1) : null, bienId);
        String chemin = stockage.enregistrer("patrimoine/factures", fichier);
        jdbc.update("update inventaire_biens set facture_chemin = ?, facture_nom = ?, facture_type = ?, maj_le = now(), maj_par = ? where id = ?",
            chemin, fichier.getOriginalFilename(), type, currentUser.requireUser().getEmail(), bienId);
        effacerFichier(ancien);
        AuditService.detailler("Facture jointe au bien " + b.code() + ".");
        return bien(bienId);
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public FichierPhoto fichierFacture(Long bienId) {
        return jdbc.query("select facture_chemin, facture_type from inventaire_biens where id = ? and facture_chemin is not null",
            rs -> rs.next() ? new FichierPhoto(stockage.charger(rs.getString(1)), rs.getString(2)) : null, bienId);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Bien retirerFacture(Long bienId) {
        Bien b = bien(bienId);
        String chemin = jdbc.query("select facture_chemin from inventaire_biens where id = ?", rs -> rs.next() ? rs.getString(1) : null, bienId);
        if (chemin == null) throw RessourceIntrouvableException.of("Facture du bien", bienId);
        jdbc.update("update inventaire_biens set facture_chemin = null, facture_nom = null, facture_type = null, maj_le = now(), maj_par = ? where id = ?",
            currentUser.requireUser().getEmail(), bienId);
        effacerFichier(chemin);
        AuditService.detailler("Facture retirée du bien " + b.code() + ".");
        return bien(bienId);
    }

    private void effacerFichier(String chemin) {
        if (chemin == null) return;
        try {
            stockage.supprimer(chemin);
        } catch (RuntimeException e) {
            // Fichier resté sur le disque : sans conséquence, la référence est retirée.
        }
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
                                      @Size(max = 100) String numeroSerie,
                                      /** Bien durable, utilisé au-delà d'un exercice : amortissable (oui si absent). */
                                      Boolean amortissable) { }

    /**
     * Valeur et date d'un bien : immobilisation (fiche d'amortissement + pièce d'acquisition D 2xxx / C 1013) au-dessus
     * du seuil, petit matériel en dessous.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Bien valoriser(Long id, ValorisationRequest r) {
        Bien b = bien(id);
        boolean amortissable = !Boolean.FALSE.equals(r.amortissable());
        // Un bien classé non amortissable peut être immobilisé ensuite (il s'avère durable) ; un bien immobilisé ne
        // repasse pas en charge (sa fiche se sort par cession ou mise au rebut).
        boolean reclassement = "PETIT_MATERIEL".equals(b.statut()) && amortissable;
        if (!"A_VALORISER".equals(b.statut()) && !reclassement) {
            throw new TransitionInvalideException("Le bien " + b.code() + " est déjà valorisé.");
        }
        if (reclassement && currentUser.requirePrincipal().getAuthorities().stream()
                .noneMatch(a -> AUTORITES_CLASSEMENT.contains(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException(
                "Immobiliser un bien déjà classé non amortissable est une décision du comptable ou du DFIN.");
        }
        LocalDate miseService = r.dateMiseService() == null ? r.dateAcquisition() : r.dateMiseService();
        if (miseService.isBefore(r.dateAcquisition())) {
            throw new IllegalArgumentException("La mise en service ne peut pas précéder l'acquisition.");
        }
        CategorieImmobilisation categorie = r.categorie() == null ? b.categorie() : r.categorie();
        int duree = r.dureeMois() != null ? r.dureeMois() : (r.categorie() != null && r.categorie() != b.categorie()
            ? categorie.dureeMoisParDefaut() : b.dureeMois());
        String auteur = currentUser.requireUser().getEmail();
        if (!amortissable) {
            jdbc.update("""
                update inventaire_biens set statut = 'PETIT_MATERIEL', valeur_usd = ?, date_acquisition = ?, date_mise_service = ?,
                categorie = ?, duree_mois = ?, maj_le = now(), maj_par = ? where id = ?
                """, r.valeurUsd(), r.dateAcquisition(), miseService, categorie.name(), duree, auteur, id);
            AuditService.detailler("Inventaire " + b.code() + " (" + b.libelle() + ") : " + r.valeurUsd()
                + " USD, consommé dans l'exercice — non amortissable, non immobilisé.");
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

    /**
     * Le logisticien reclasse un bien immobilisé en « non amortissable » (consommé dans l'exercice) : une pièce
     * d'opérations diverses extourne l'acquisition (D contrepartie / C compte d'immobilisation) et les dotations déjà
     * passées (D amortissements / C dotations) ; la fiche et son plan sont supprimés, le bien reste à l'inventaire.
     */
    @PreAuthorize(CLASSEMENT)
    @Transactional
    public Bien declasser(Long id) {
        Bien b = bien(id);
        if (!"IMMOBILISE".equals(b.statut()) || b.immobilisationId() == null) {
            throw new TransitionInvalideException("Le bien " + b.code() + " n'est pas immobilisé.");
        }
        Long immoId = b.immobilisationId();
        var fiche = jdbc.queryForMap("""
            select reference, statut, piece_acquisition_id, compte_amortissement_id, compte_dotation_id
            from immobilisations where id = ? for update""", immoId);
        if (!"EN_SERVICE".equals(fiche.get("statut"))) {
            throw new TransitionInvalideException("La fiche " + fiche.get("reference") + " est déjà sortie du patrimoine.");
        }
        var auteur = currentUser.requireUser();
        LocalDate aujourdhui = LocalDate.now(com.mbsc.finapp.config.FuseauEntreprise.ZONE);
        String libelle = "Déclassement " + b.code() + " (" + b.libelle() + ") : bien non amortissable, extourne de "
            + fiche.get("reference");
        if (libelle.length() > 250) libelle = libelle.substring(0, 250);
        List<com.mbsc.finapp.domain.EcritureGrandLivre> lignes = new java.util.ArrayList<>();
        // 1. Acquisition : chaque ligne de la pièce d'origine, sens inversé.
        if (fiche.get("piece_acquisition_id") != null) {
            for (var e : jdbc.queryForList("select compte_id, debit, credit from grand_livre where piece_id = ?",
                    ((Number) fiche.get("piece_acquisition_id")).longValue())) {
                lignes.add(ecriture(((Number) e.get("compte_id")).longValue(), (BigDecimal) e.get("credit"),
                    (BigDecimal) e.get("debit"), libelle, aujourdhui));
            }
        }
        // 2. Dotations déjà passées : reprise du cumul.
        BigDecimal cumul = jdbc.queryForObject(
            "select coalesce(sum(dotation), 0) from lignes_amortissement where immobilisation_id = ? and comptabilise",
            BigDecimal.class, immoId);
        if (cumul.signum() > 0 && fiche.get("compte_amortissement_id") != null && fiche.get("compte_dotation_id") != null) {
            lignes.add(ecriture(((Number) fiche.get("compte_amortissement_id")).longValue(), cumul, BigDecimal.ZERO, libelle, aujourdhui));
            lignes.add(ecriture(((Number) fiche.get("compte_dotation_id")).longValue(), BigDecimal.ZERO, cumul, libelle, aujourdhui));
        }
        String piece = null;
        if (lignes.size() >= 2) {
            piece = comptabilite.creerPieceInterne(com.mbsc.finapp.domain.enums.JournalComptable.OPERATIONS_DIVERSES,
                libelle, aujourdhui, lignes, auteur).getReference();
        }
        // 3. La fiche disparaît du registre ; le bien redevient non amortissable.
        jdbc.update("update inventaires_immobilisations_lignes set immobilisation_id = null where immobilisation_id = ? and bien_id is not null", immoId);
        Long autres = jdbc.queryForObject("select count(*) from inventaires_immobilisations_lignes where immobilisation_id = ?", Long.class, immoId);
        if (autres != null && autres > 0) {
            throw new TransitionInvalideException("La fiche " + fiche.get("reference") + " figure dans un inventaire du patrimoine "
                + "sans code de bien : sortez-la plutôt par mise au rebut.");
        }
        jdbc.update("update inventaire_biens set statut = 'PETIT_MATERIEL', immobilisation_id = null, maj_le = now(), maj_par = ? where id = ?",
            auteur.getEmail(), id);
        jdbc.update("delete from immobilisations where id = ?", immoId);
        AuditService.detailler("Bien " + b.code() + " déclassé (non amortissable) : fiche " + fiche.get("reference")
            + " supprimée" + (piece == null ? "." : ", extourne " + piece + "."));
        return bien(id);
    }

    private com.mbsc.finapp.domain.EcritureGrandLivre ecriture(Long compteId, BigDecimal debit, BigDecimal credit,
                                                                String libelle, LocalDate date) {
        return com.mbsc.finapp.domain.EcritureGrandLivre.builder()
            .compte(comptes.findById(compteId).orElseThrow())
            .debit(debit == null ? BigDecimal.ZERO : debit)
            .credit(credit == null ? BigDecimal.ZERO : credit)
            .libelle(libelle)
            .dateEcriture(date)
            .build();
    }
}
