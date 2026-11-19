package com.mbsc.finapp.service.hotellerie;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.OrigineVente;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.hotellerie.PmsDtos;
import com.mbsc.finapp.dto.vente.ClientRequest;
import com.mbsc.finapp.dto.vente.LigneVenteRequest;
import com.mbsc.finapp.dto.vente.ReglerCreanceRequest;
import com.mbsc.finapp.dto.vente.VenteRequest;
import com.mbsc.finapp.dto.vente.VenteResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.ClientRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.VenteRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.ClientService;
import com.mbsc.finapp.service.ComptabiliteService;
import com.mbsc.finapp.service.ConversionDeviseService;
import com.mbsc.finapp.service.EtablissementTresorerieService;
import com.mbsc.finapp.service.PermissionService;
import com.mbsc.finapp.service.TauxTvaService;
import com.mbsc.finapp.service.VenteService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Opérations de l'application Hôtellerie sur M-FINAPP (API {@code /pms/**}, V152). Le PMS ne touche jamais la base de
 * M-FINAPP : il demande ici, au nom de l'utilisateur connecté, ce que les services de M-FINAPP savent déjà faire.
 *
 * <ul>
 *   <li>Folio de séjour = vente M-FINAPP d'origine HOTELLERIE : créée à crédit, validée (pièce VENTES, sortie de stock
 *       des extras stockés, TVA, analytique 986 pour l'hébergement), puis réglée par la réception (caisse, banque ou
 *       mobile money) en imputant les acomptes (4191) et la caution retenue (1651). Mode CREDIT : la créance reste
 *       ouverte (séjour facturé à une entreprise).</li>
 *   <li>Acompte : D trésorerie / C 4191 ; caution : D trésorerie / C 1651 ; restitution : D 1651 / C trésorerie.</li>
 *   <li>Seuls la réception et la direction de l'hébergement encaissent (jamais l'administrateur : séparation des
 *       tâches). Chaque opération porte une clé d'idempotence : rejouée, elle renvoie le premier résultat.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PmsIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(PmsIntegrationService.class);
    static final String ENCAISSEURS = "hasAnyRole('RECEPTIONNISTE', 'DIR_HEBERGEMENT')";
    static final String LECTEURS = "hasAnyRole('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'GOUVERNANTE', 'TECHNICIEN', 'DG', 'DFIN', 'DA', 'COMPTABLE', 'ADMIN')";
    public static final String COMPTE_ACOMPTES = "4191";
    public static final String COMPTE_CAUTIONS = "1651";
    private static final String COMPTE_CAISSE = "571";
    /** Articles de séjour créés à la demande : prestation de service, compte 7061, analytique 986. */
    private static final Map<String, String[]> SEJOURS = Map.of(
        "NUIT", new String[] {"HEB-NUIT", "Hébergement — nuitée", "nuit"},
        "HEURE", new String[] {"HEB-HEURE", "Hébergement — heure", "heure"},
        "SEMAINE", new String[] {"HEB-SEMAINE", "Hébergement — semaine", "semaine"},
        "MOIS", new String[] {"HEB-MOIS", "Hébergement — mois", "mois"});

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserProvider currentUser;
    private final PermissionService permissions;
    private final VenteService ventes;
    private final VenteRepository venteRepository;
    private final ClientService clientService;
    private final ClientRepository clientRepository;
    private final ArticleRepository articleRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final EtablissementTresorerieService etablissements;
    private final TauxTvaService tauxTva;
    private final ConversionDeviseService conversion;
    private final com.mbsc.finapp.service.MailNotificationService mails;

    // ── Lecture ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PmsDtos.Etat etat() {
        boolean actif = Boolean.TRUE.equals(jdbc.query("select actif from modules_config where module = 'HOTELLERIE'",
            rs -> rs.next() ? rs.getBoolean(1) : Boolean.TRUE));
        String nom = jdbc.query("select nom from parametres_entreprise order by id limit 1", rs -> rs.next() ? rs.getString(1) : null);
        return new PmsDtos.Etat(actif, nom, ConversionDeviseService.DEVISE_BASE.name(), version());
    }

    @PreAuthorize(LECTEURS)
    @Transactional(readOnly = true)
    public PmsDtos.Moi moi() {
        User u = currentUser.requireUser();
        var roles = u.getRoles().stream().map(r -> r.getNom().name()).collect(Collectors.toSet());
        NiveauPermission niveau = permissions.mesPermissions().getOrDefault(ModuleMetier.HOTELLERIE, NiveauPermission.AUCUN);
        return new PmsDtos.Moi(u.getId(), u.getEmail(), u.getNom(), u.getPrenom(), roles, niveau.name());
    }

    @PreAuthorize(LECTEURS)
    @Transactional(readOnly = true)
    public PmsDtos.Referentiel referentiel() {
        LocalDate aujourdhui = LocalDate.now();
        var etabs = jdbc.query("""
            select e.id, e.nom, e.type, e.devise, c.numero from etablissements_tresorerie e join comptes_ohada c on c.id = e.compte_id
            where e.actif order by e.type, e.nom
            """, (rs, i) -> new PmsDtos.Etablissement(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        var entrepots = jdbc.query("select id, code, nom from entrepots where actif order by code",
            (rs, i) -> new PmsDtos.Entrepot(rs.getLong(1), rs.getString(2), rs.getString(3)));
        var articles = jdbc.query("""
            select id, code, libelle, type, prix_vente, soumis_tva, unite_mesure from articles
            where actif and compte_produit_id is not null and not hebergement and not minerais
            order by libelle
            """, (rs, i) -> new PmsDtos.Article(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getBigDecimal(5), rs.getBoolean(6), rs.getString(7)));
        String nom = jdbc.query("select nom from parametres_entreprise order by id limit 1", rs -> rs.next() ? rs.getString(1) : null);
        return new PmsDtos.Referentiel(nom, ConversionDeviseService.DEVISE_BASE.name(), tauxTva.tauxALaDate(aujourdhui),
            conversion.tauxALaDate(aujourdhui), true,
            etabs.stream().filter(e -> "BANQUE".equals(e.type())).toList(),
            etabs.stream().filter(e -> "MOBILE_MONEY".equals(e.type())).toList(), entrepots, articles);
    }

    @PreAuthorize(LECTEURS)
    @Transactional(readOnly = true)
    public List<PmsDtos.Client> clients(String recherche) {
        String q = recherche == null ? "" : recherche.strip().toLowerCase();
        return clientRepository.findAll().stream()
            .filter(c -> c.isActif())
            .filter(c -> q.isEmpty() || (c.getNom() + " " + c.getCode() + " " + (c.getTelephone() == null ? "" : c.getTelephone())
                + " " + (c.getEmail() == null ? "" : c.getEmail())).toLowerCase().contains(q))
            .limit(50)
            .map(c -> new PmsDtos.Client(c.getId(), c.getCode(), c.getNom(), c.getTelephone(), c.getEmail(), c.getTypeClient()))
            .toList();
    }

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Client creerClient(PmsDtos.NouveauClient req) {
        var c = clientService.creerInterne(new ClientRequest(null, req.nom(), req.telephone(), req.email(), req.adresse(),
            req.nif(), req.typeClient(), true));
        AuditService.detailler("Client " + c.code() + " créé depuis l'hôtellerie : " + c.nom() + ".");
        return new PmsDtos.Client(c.id(), c.code(), c.nom(), c.telephone(), c.email(), c.typeClient());
    }

    @PreAuthorize(LECTEURS)
    @Transactional(readOnly = true)
    public VenteResponse vente(Long id) {
        Vente v = venteRepository.findWithLignesById(id).orElseThrow(() -> RessourceIntrouvableException.of("Vente", id));
        if (v.getOrigine() != OrigineVente.HOTELLERIE) {
            throw RessourceIntrouvableException.of("Vente", id);
        }
        return VenteResponse.from(v);
    }

    // ── Acomptes et cautions ────────────────────────────────────────────────

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Piece acompte(PmsDtos.Fonds req) {
        return fonds(req, "ACOMPTE", COMPTE_ACOMPTES, true, "Acompte");
    }

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Piece caution(PmsDtos.Fonds req) {
        return fonds(req, "CAUTION", COMPTE_CAUTIONS, true, "Caution");
    }

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Piece restituerCaution(PmsDtos.Fonds req) {
        return fonds(req, "RESTITUTION", COMPTE_CAUTIONS, false, "Restitution de caution");
    }

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Piece rembourserAcompte(PmsDtos.Fonds req) {
        return fonds(req, "REMB_ACOMPTE", COMPTE_ACOMPTES, false, "Remboursement d'acompte");
    }

    private PmsDtos.Piece fonds(PmsDtos.Fonds req, String type, String compteNumero, boolean entree, String nature) {
        var deja = deja(req.cle(), PmsDtos.Piece.class);
        if (deja.isPresent()) {
            var p = deja.get();
            return new PmsDtos.Piece(p.cle(), p.pieceReference(), p.montant(), p.compte(), true);
        }
        if (req.mode() == ModeReglement.CREDIT) {
            throw new IllegalArgumentException(nature + " : choisissez la caisse, la banque ou le mobile money.");
        }
        User auteur = currentUser.requireUser();
        LocalDate date = req.date() == null ? LocalDate.now() : req.date();
        BigDecimal montant = req.montant().setScale(2, RoundingMode.HALF_UP);
        CompteOHADA tresorerie = compteTresorerie(req.mode(), req.etablissementId());
        CompteOHADA contrepartie = compte(compteNumero);
        String client = designationClient(req.clientId(), req.clientNom());
        String libelle = nature + " " + req.reference() + (client == null ? "" : " - " + client);
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        lignes.add(ligne(entree ? tresorerie : contrepartie, montant, BigDecimal.ZERO, libelle, date));
        lignes.add(ligne(entree ? contrepartie : tresorerie, BigDecimal.ZERO, montant, libelle, date));
        PieceComptable piece = comptabilite.creerPieceInterne(journal(req.mode()), libelle, date, lignes, auteur);
        AuditService.detailler(libelle + " : " + montant.toPlainString() + " " + ConversionDeviseService.DEVISE_BASE + " ("
            + req.mode() + "), pièce " + piece.getReference() + ".");
        var resultat = new PmsDtos.Piece(req.cle(), piece.getReference(), montant, compteNumero, false);
        memoriser(req.cle(), type, resultat, auteur);
        log.info("Hôtellerie : {} {} [{} {}]", nature, req.reference(), montant, piece.getReference());
        return resultat;
    }

    // ── Folio ───────────────────────────────────────────────────────────────

    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Folio encaisserFolio(PmsDtos.EncaissementFolio req) {
        var deja = deja(req.cle(), PmsDtos.Folio.class);
        if (deja.isPresent()) {
            return avecDeja(deja.get());
        }
        User auteur = currentUser.requireUser();
        LocalDate date = req.date() == null ? LocalDate.now() : req.date();
        BigDecimal acompte = zero(req.acompteImpute());
        BigDecimal caution = zero(req.cautionImputee());
        if (req.mode() == ModeReglement.CREDIT && (acompte.signum() > 0 || caution.signum() > 0)) {
            throw new IllegalArgumentException("Un folio laissé à crédit n'impute ni acompte ni caution : ils s'imputent au "
                + "règlement de la créance.");
        }
        if (req.clientId() == null && (req.clientNom() == null || req.clientNom().isBlank())) {
            throw new IllegalArgumentException("Indiquez le client du séjour.");
        }

        List<LigneVenteRequest> lignes = new ArrayList<>();
        List<String> designations = new ArrayList<>();
        for (PmsDtos.LigneFolio l : req.lignes()) {
            Long articleId;
            if (l.hebergement() != null) {
                articleId = articleSejour(l.hebergement()).getId();
            } else if (l.articleId() != null) {
                articleId = l.articleId();
            } else {
                throw new IllegalArgumentException("Chaque ligne du folio est un séjour (nuit, heure, semaine, mois) ou un article.");
            }
            lignes.add(new LigneVenteRequest(articleId, l.quantite(), l.prixUnitaire(), null));
            designations.add(l.designation());
        }

        // 1. Vente à crédit, d'origine HOTELLERIE : la créance porte le folio, le règlement la solde ensuite.
        VenteResponse creee = ventes.creerInterne(new VenteRequest(date, req.clientId(),
            req.clientId() == null ? req.clientNom().strip() : null, ModeReglement.CREDIT, ConversionDeviseService.DEVISE_BASE,
            null, req.entrepotId(), null, lignes), OrigineVente.HOTELLERIE);
        Vente vente = venteRepository.findWithLignesById(creee.id()).orElseThrow();
        for (var lv : vente.getLignes()) {
            int i = lv.getOrdre() - 1;
            if (i >= 0 && i < designations.size() && designations.get(i) != null && !designations.get(i).isBlank()) {
                lv.setDesignation(designations.get(i).strip());
            }
        }
        // 2. Validation : pièce VENTES (706x, TVA, 411x), sortie de stock des extras, analytique.
        VenteResponse validee = ventes.validerInterne(vente.getId());
        // 3. Règlement par la réception, acomptes et caution imputés.
        VenteResponse finale = validee;
        if (req.mode() != ModeReglement.CREDIT) {
            List<VenteService.Imputation> imputations = new ArrayList<>();
            if (acompte.signum() > 0) imputations.add(new VenteService.Imputation(COMPTE_ACOMPTES, acompte,
                "Acompte imputé - " + req.reference()));
            if (caution.signum() > 0) imputations.add(new VenteService.Imputation(COMPTE_CAUTIONS, caution,
                "Caution retenue - " + req.reference()));
            finale = ventes.reglerCreanceInterne(vente.getId(),
                new ReglerCreanceRequest(req.mode(), req.etablissementId(), date), imputations);
        }
        AuditService.detailler("Folio " + req.reference() + " encaissé : vente " + finale.reference() + ", "
            + finale.totalTtc().toPlainString() + " " + finale.devise() + " TTC, " + req.mode()
            + (acompte.signum() > 0 ? ", acompte imputé " + acompte.toPlainString() : "")
            + (caution.signum() > 0 ? ", caution retenue " + caution.toPlainString() : "") + ".");
        var resultat = folio(req.cle(), finale, false);
        memoriser(req.cle(), "FOLIO", resultat, auteur);
        return resultat;
    }

    /** Règlement plus tard d'un folio laissé à crédit (séjour facturé à une entreprise). */
    @PreAuthorize(ENCAISSEURS)
    @Transactional
    public PmsDtos.Folio reglerFolio(Long venteId, PmsDtos.ReglementFolio req) {
        var deja = deja(req.cle(), PmsDtos.Folio.class);
        if (deja.isPresent()) {
            return avecDeja(deja.get());
        }
        User auteur = currentUser.requireUser();
        Vente v = venteRepository.findById(venteId).orElseThrow(() -> RessourceIntrouvableException.of("Vente", venteId));
        if (v.getOrigine() != OrigineVente.HOTELLERIE) {
            throw new TransitionInvalideException("Seul un folio de l'hôtellerie se règle d'ici.");
        }
        BigDecimal acompte = zero(req.acompteImpute());
        List<VenteService.Imputation> imputations = acompte.signum() > 0
            ? List.of(new VenteService.Imputation(COMPTE_ACOMPTES, acompte, "Acompte imputé - " + v.getReference()))
            : List.of();
        VenteResponse r = ventes.reglerCreanceInterne(venteId,
            new ReglerCreanceRequest(req.mode(), req.etablissementId(), req.date() == null ? LocalDate.now() : req.date()), imputations);
        var resultat = folio(req.cle(), r, false);
        memoriser(req.cle(), "REGLEMENT", resultat, auteur);
        return resultat;
    }

    // ── Messages aux clients (phase 2) ─────────────────────────────────────

    /** Appel système (tâche planifiée de l'hôtellerie) : un e-mail par clé, jamais deux. */
    @PreAuthorize("hasRole('PMS')")
    @Transactional
    public PmsDtos.MessageEnvoye envoyerMessage(PmsDtos.Message m) {
        if (!jdbc.queryForList("select 1 from pms_operations where cle = ?", Integer.class, m.cle()).isEmpty()) {
            return new PmsDtos.MessageEnvoye(m.cle(), true, true);
        }
        if (!mails.pret()) {
            throw new IllegalStateException("La messagerie de M-FINAPP n'est pas configurée (Administration › Messagerie).");
        }
        try {
            mails.envoyerMessageClient(m.destinataire(), m.nom(), m.sujet(), m.corps());
        } catch (Exception e) {
            throw new IllegalStateException("Envoi refusé par le serveur de messagerie : " + e.getMessage());
        }
        jdbc.update("insert into pms_operations (cle, type_operation, resultat) values (?, 'MESSAGE', ?)", m.cle(),
            "{\"destinataire\":\"" + m.destinataire().replace("\"", "") + "\"}");
        log.info("Hôtellerie : message « {} » envoyé à {}", m.sujet(), m.destinataire());
        return new PmsDtos.MessageEnvoye(m.cle(), true, false);
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    /** Article de séjour (nuit, heure, semaine, mois), créé à la première utilisation. */
    Article articleSejour(String unite) {
        String[] d = SEJOURS.get(unite);
        if (d == null) throw new IllegalArgumentException("Unité de séjour inconnue : " + unite);
        return articleRepository.findByCode(d[0]).orElseGet(() -> {
            CompteOHADA produit = compteRepository.findByNumero("7061").or(() -> compteRepository.findByNumero("706"))
                .orElseThrow(() -> new IllegalStateException("Le compte 706 (services vendus) manque au plan comptable."));
            Article a = Article.builder()
                .code(d[0]).libelle(d[1]).type(TypeArticle.SERVICE).uniteMesure(d[2])
                .compteProduit(produit).soumisTva(true).hebergement(true).actif(true)
                .build();
            log.info("Hôtellerie : article de séjour {} créé", d[0]);
            return articleRepository.save(a);
        });
    }

    private CompteOHADA compteTresorerie(ModeReglement mode, Long etablissementId) {
        return switch (mode) {
            case CAISSE -> compte(COMPTE_CAISSE);
            case BANQUE -> etablissements.requireEtablissement(exiger(etablissementId, "la banque"), TypeEtablissement.BANQUE).getCompte();
            case MOBILE_MONEY -> etablissements.requireEtablissement(exiger(etablissementId, "l'opérateur mobile money"),
                TypeEtablissement.MOBILE_MONEY).getCompte();
            case CREDIT -> throw new IllegalArgumentException("Mode d'encaissement invalide.");
        };
    }

    private static Long exiger(Long id, String quoi) {
        if (id == null) throw new IllegalArgumentException("Précisez " + quoi + ".");
        return id;
    }

    private static JournalComptable journal(ModeReglement mode) {
        return switch (mode) {
            case CAISSE -> JournalComptable.CAISSE;
            case BANQUE -> JournalComptable.BANQUE;
            case MOBILE_MONEY -> JournalComptable.MOBILE_MONEY;
            case CREDIT -> JournalComptable.OPERATIONS_DIVERSES;
        };
    }

    private CompteOHADA compte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new IllegalStateException("Le compte " + numero + " manque au plan comptable."));
    }

    private String designationClient(Long clientId, String clientNom) {
        if (clientId != null) {
            return clientRepository.findById(clientId).map(c -> c.getNom())
                .orElseThrow(() -> RessourceIntrouvableException.of("Client", clientId));
        }
        return clientNom == null || clientNom.isBlank() ? null : clientNom.strip();
    }

    private static EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal debit, BigDecimal credit, String libelle, LocalDate date) {
        return EcritureGrandLivre.builder().compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static PmsDtos.Folio folio(String cle, VenteResponse v, boolean deja) {
        return new PmsDtos.Folio(cle, v.id(), v.reference(), v.statut().name(), v.totalHt(), v.totalTva(), v.totalTtc(),
            v.pieceReference(), v.pieceReglementReference(), v.reglee(), deja);
    }

    private static PmsDtos.Folio avecDeja(PmsDtos.Folio f) {
        return new PmsDtos.Folio(f.cle(), f.venteId(), f.venteReference(), f.statut(), f.totalHt(), f.totalTva(), f.totalTtc(),
            f.pieceVente(), f.pieceReglement(), f.reglee(), true);
    }

    private <T> Optional<T> deja(String cle, Class<T> type) {
        var lignes = jdbc.queryForList("select resultat from pms_operations where cle = ?", String.class, cle);
        if (lignes.isEmpty()) return Optional.empty();
        try {
            return Optional.of(json.readValue(lignes.get(0), type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Opération " + cle + " déjà reçue, résultat illisible.", e);
        }
    }

    private void memoriser(String cle, String type, Object resultat, User auteur) {
        try {
            jdbc.update("insert into pms_operations (cle, type_operation, resultat, utilisateur_id) values (?, ?, ?, ?)",
                cle, type, json.writeValueAsString(resultat), auteur.getId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String version() {
        return jdbc.query("select version from flyway_schema_history where success order by installed_rank desc limit 1",
            rs -> rs.next() ? rs.getString(1) : null);
    }
}
