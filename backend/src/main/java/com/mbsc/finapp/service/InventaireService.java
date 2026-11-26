package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Inventaire physique annuel (audit SYSCOHADA du 10/11/2026, C-22 ; AUDCIF art. 19 et 42).
 *
 * <p><b>Stocks</b> : un inventaire par entrepôt. À sa création, chaque article en stock reçoit une ligne avec sa
 * quantité théorique et son coût moyen ; les compteurs saisissent la quantité comptée. À la validation — par une autre
 * personne que celle qui l'a ouvert, côté finances —, chaque écart devient un mouvement de stock au coût moyen :
 * manquant = sortie (D compte de variation de l'article / C stock), excédent = entrée (D stock / C variation).
 * Le procès-verbal s'imprime depuis l'écran.</p>
 *
 * <p><b>Immobilisations</b> : chaque bien en service est constaté présent ou absent, avec son état et sa
 * localisation. La validation fige le procès-verbal ; un bien absent ou hors d'usage se sort ensuite du patrimoine
 * (cession ou mise au rebut), décision qui reste au gestionnaire.</p>
 */
@Service
@RequiredArgsConstructor
public class InventaireService {

    private static final Logger log = LoggerFactory.getLogger(InventaireService.class);

    static final String SAISIE_STOCK = "hasAnyRole('LOGISTIQUE', 'COMPTABLE', 'DFIN')";
    static final String SAISIE_IMMO = "hasAnyRole('GEST_PATRIMOINE', 'LOGISTIQUE', 'COMPTABLE', 'DFIN')";
    static final String LECTURE = "hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_BAR', 'RESP_CUISINE', "
        + "'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";
    static final String VALIDATION = "hasAnyRole('COMPTABLE', 'DFIN')";

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final CurrentUserProvider currentUser;

    public record Inventaire(Long id, String type, String reference, Long entrepotId, String entrepot,
                             LocalDate dateInventaire, String statut, String observations, String creePar,
                             LocalDateTime creeLe, String validePar, LocalDateTime valideLe, List<Ligne> lignes) {
    }

    /** Ligne d'inventaire : article (stocks) ou bien (immobilisations). */
    public record Ligne(Long id, Long objetId, String code, String libelle, BigDecimal quantiteTheorique,
                        BigDecimal quantiteComptee, BigDecimal coutUnitaire, BigDecimal ecartValeur,
                        Boolean present, String etat, String localisationAttendue, String localisationConstatee,
                        String commentaire, String mouvement) {
    }

    public record Creation(Long entrepotId, LocalDate dateInventaire, String observations) {
    }

    public record Comptage(BigDecimal quantiteComptee, BigDecimal coutUnitaire, Boolean present, String etat,
                           String localisationConstatee, String commentaire) {
    }

    // =====================================================================
    // Stocks
    // =====================================================================

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Inventaire> listerStocks() {
        return jdbc.query("""
            select i.id, i.reference, i.entrepot_id, e.nom, i.date_inventaire, i.statut, i.observations, u.email,
                   i.created_at, v.email, i.valide_le
            from inventaires_stock i join entrepots e on e.id = i.entrepot_id
            left join users u on u.id = i.created_by_id left join users v on v.id = i.valide_par_id
            order by i.date_inventaire desc, i.id desc
            """, (rs, n) -> new Inventaire(rs.getLong(1), "STOCK", rs.getString(2), rs.getLong(3), rs.getString(4),
            rs.getObject(5, LocalDate.class), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getObject(9, LocalDateTime.class), rs.getString(10), rs.getObject(11, LocalDateTime.class), List.of()));
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public Inventaire consulterStock(Long id) {
        Inventaire i = listerStocks().stream().filter(x -> x.id().equals(id)).findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("InventaireStock", id));
        List<Ligne> lignes = jdbc.query("""
            select l.id, a.id, a.code, a.libelle, l.quantite_theorique, l.quantite_comptee, l.cout_unitaire,
                   l.commentaire, m.reference
            from inventaires_stock_lignes l join articles a on a.id = l.article_id
            left join mouvements_stock m on m.id = l.mouvement_id
            where l.inventaire_id = ? order by a.code
            """, (rs, n) -> {
            BigDecimal theo = rs.getBigDecimal(5), compte = rs.getBigDecimal(6), cout = rs.getBigDecimal(7);
            BigDecimal ecart = compte == null ? null
                : compte.subtract(theo).multiply(cout).setScale(2, RoundingMode.HALF_UP);
            return new Ligne(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), theo, compte, cout, ecart,
                null, null, null, null, rs.getString(8), rs.getString(9));
        }, id);
        return avecLignes(i, lignes);
    }

    @PreAuthorize(SAISIE_STOCK)
    @Transactional
    public Inventaire ouvrirStock(Creation req) {
        User auteur = currentUser.requireUser();
        if (req.entrepotId() == null || req.dateInventaire() == null) {
            throw new IllegalArgumentException("Choisissez l'entrepôt et la date de l'inventaire.");
        }
        Long enCours = jdbc.queryForObject("select count(*) from inventaires_stock where entrepot_id = ? and statut = 'EN_COURS'",
            Long.class, req.entrepotId());
        if (enCours != null && enCours > 0) {
            throw new TransitionInvalideException("Un inventaire de cet entrepôt est déjà en cours.");
        }
        Long id = jdbc.queryForObject("""
            insert into inventaires_stock (reference, entrepot_id, date_inventaire, observations, created_by_id)
            values (?, ?, ?, ?, ?) returning id
            """, Long.class, reference("INVS", req.dateInventaire()), req.entrepotId(), req.dateInventaire(),
            req.observations(), auteur.getId());
        jdbc.update("""
            insert into inventaires_stock_lignes (inventaire_id, article_id, quantite_theorique, cout_unitaire)
            select ?, n.article_id, n.quantite,
                   case when n.quantite <> 0 then round(n.valeur_totale / n.quantite, 4) else 0 end
            from stock_niveaux n where n.entrepot_id = ? and n.quantite <> 0
            """, id, req.entrepotId());
        return consulterStock(id);
    }

    @PreAuthorize(SAISIE_STOCK)
    @Transactional
    public Inventaire compterStock(Long id, Long ligneId, Comptage c) {
        exigerEnCours("inventaires_stock", id);
        if (c.quantiteComptee() != null && c.quantiteComptee().signum() < 0) {
            throw new IllegalArgumentException("La quantité comptée ne peut pas être négative.");
        }
        int n = jdbc.update("""
            update inventaires_stock_lignes set quantite_comptee = ?, commentaire = ?,
                cout_unitaire = coalesce(?, cout_unitaire)
            where id = ? and inventaire_id = ?
            """, c.quantiteComptee(), c.commentaire(), c.coutUnitaire(), ligneId, id);
        if (n == 0) throw RessourceIntrouvableException.of("LigneInventaire", ligneId);
        return consulterStock(id);
    }

    /** Ajoute un article trouvé en stock sans ligne (quantité théorique zéro). */
    @PreAuthorize(SAISIE_STOCK)
    @Transactional
    public Inventaire ajouterArticle(Long id, Long articleId) {
        exigerEnCours("inventaires_stock", id);
        jdbc.update("""
            insert into inventaires_stock_lignes (inventaire_id, article_id, quantite_theorique, cout_unitaire)
            values (?, ?, 0, 0) on conflict (inventaire_id, article_id) do nothing
            """, id, articleId);
        return consulterStock(id);
    }

    /** Valide l'inventaire et passe un mouvement de stock par écart, au coût moyen. */
    @PreAuthorize(VALIDATION)
    @Transactional
    public Inventaire validerStock(Long id) {
        User valideur = currentUser.requireUser();
        Map<String, Object> inv = exigerEnCours("inventaires_stock", id);
        exigerAutrePersonne(inv, valideur);
        Long nonComptees = jdbc.queryForObject(
            "select count(*) from inventaires_stock_lignes where inventaire_id = ? and quantite_comptee is null",
            Long.class, id);
        if (nonComptees != null && nonComptees > 0) {
            throw new TransitionInvalideException(nonComptees + " article(s) n'ont pas été comptés.");
        }
        Entrepot entrepot = stock.entrepotParIdInterne(((Number) inv.get("entrepot_id")).longValue());
        LocalDate date = ((java.sql.Date) inv.get("date_inventaire")).toLocalDate();
        String reference = (String) inv.get("reference");
        List<Map<String, Object>> lignes = jdbc.queryForList("""
            select l.id, l.article_id, l.quantite_comptee, l.cout_unitaire, coalesce(n.quantite, 0) as stock,
                   case when coalesce(n.quantite, 0) <> 0 then round(n.valeur_totale / n.quantite, 4) end as cmp
            from inventaires_stock_lignes l
            left join stock_niveaux n on n.article_id = l.article_id and n.entrepot_id = ?
            where l.inventaire_id = ?
            """, entrepot.getId(), id);
        int ajustements = 0;
        for (Map<String, Object> l : lignes) {
            // Le stock théorique est relu à la validation : un mouvement passé depuis l'ouverture compte.
            BigDecimal theorique = (BigDecimal) l.get("stock");
            BigDecimal compte = (BigDecimal) l.get("quantite_comptee");
            BigDecimal ecart = compte.subtract(theorique);
            jdbc.update("update inventaires_stock_lignes set quantite_theorique = ? where id = ?", theorique, l.get("id"));
            if (ecart.signum() == 0) continue;
            Article article = stock.articleParIdInterne(((Number) l.get("article_id")).longValue());
            String libelle = "Inventaire " + reference + " : " + (ecart.signum() < 0 ? "manquant " : "excédent ")
                + article.getCode();
            MouvementStock m;
            if (ecart.signum() < 0) {
                m = stock.enregistrerSortieVenteInterne(date, libelle, entrepot,
                    List.of(new StockService.SortieVente(article, ecart.negate())), valideur);
            } else {
                BigDecimal cout = l.get("cmp") != null ? (BigDecimal) l.get("cmp") : (BigDecimal) l.get("cout_unitaire");
                if (cout == null || cout.signum() <= 0) {
                    throw new TransitionInvalideException("Excédent de " + article.getCode() + " sans coût connu : "
                        + "saisissez son coût unitaire sur la ligne.");
                }
                // Consommable non stockable (605x) : l'excédent entre au suivi des quantités, sans écriture.
                if (article.getCompteCharge() == null && !StockService.suiviHorsComptabilite(article)) {
                    throw new TransitionInvalideException("L'article " + article.getCode()
                        + " n'a pas de compte de variation de stock : complétez sa fiche.");
                }
                m = stock.enregistrerEntreeRestaurantInterne(date, libelle, entrepot, article.getCompteCharge(),
                    article, ecart, cout, valideur);
            }
            jdbc.update("update inventaires_stock_lignes set mouvement_id = ? where id = ?", m.getId(), l.get("id"));
            ajustements++;
        }
        jdbc.update("update inventaires_stock set statut = 'VALIDE', valide_par_id = ?, valide_le = now() where id = ?",
            valideur.getId(), id);
        log.info("Inventaire {} valide : {} ajustement(s) de stock", reference, ajustements);
        return consulterStock(id);
    }

    @PreAuthorize(SAISIE_STOCK)
    @Transactional
    public void annulerStock(Long id) {
        exigerEnCours("inventaires_stock", id);
        jdbc.update("update inventaires_stock set statut = 'ANNULE' where id = ?", id);
    }

    // =====================================================================
    // Immobilisations
    // =====================================================================

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Inventaire> listerImmobilisations() {
        return jdbc.query("""
            select i.id, i.reference, i.date_inventaire, i.statut, i.observations, u.email, i.created_at, v.email,
                   i.valide_le
            from inventaires_immobilisations i
            left join users u on u.id = i.created_by_id left join users v on v.id = i.valide_par_id
            order by i.date_inventaire desc, i.id desc
            """, (rs, n) -> new Inventaire(rs.getLong(1), "IMMOBILISATIONS", rs.getString(2), null, null,
            rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getObject(7, LocalDateTime.class), rs.getString(8), rs.getObject(9, LocalDateTime.class), List.of()));
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public Inventaire consulterImmobilisations(Long id) {
        Inventaire i = listerImmobilisations().stream().filter(x -> x.id().equals(id)).findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("InventaireImmobilisations", id));
        // Tous les biens de l'entreprise : bien d'inventaire (code collé) et/ou fiche d'immobilisation.
        List<Ligne> lignes = jdbc.query("""
            select l.id, coalesce(ib.id, b.id),
                   coalesce(ib.code, b.reference),
                   coalesce(ib.libelle, b.libelle)
                     || case when ib.statut = 'PETIT_MATERIEL' then ' · non amortissable'
                             when ib.statut = 'A_VALORISER' then ' · à valoriser'
                             when b.id is not null and ib.id is null then ' · ' || b.reference
                             else '' end,
                   l.present, l.etat,
                   coalesce(e.nom, ib.localisation, b.localisation), l.localisation_constatee, l.commentaire
            from inventaires_immobilisations_lignes l
            left join inventaire_biens ib on ib.id = l.bien_id
            left join emplacements e on e.id = ib.emplacement_id
            left join immobilisations b on b.id = l.immobilisation_id
            where l.inventaire_id = ? order by coalesce(e.nom, ib.localisation, b.localisation), coalesce(ib.code, b.reference)
            """, (rs, n) -> new Ligne(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), null, null,
            null, null, (Boolean) rs.getObject(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            null), id);
        return avecLignes(i, lignes);
    }

    @PreAuthorize(SAISIE_IMMO)
    @Transactional
    public Inventaire ouvrirImmobilisations(Creation req) {
        User auteur = currentUser.requireUser();
        if (req.dateInventaire() == null) throw new IllegalArgumentException("Indiquez la date de l'inventaire.");
        Long enCours = jdbc.queryForObject(
            "select count(*) from inventaires_immobilisations where statut = 'EN_COURS'", Long.class);
        if (enCours != null && enCours > 0) {
            throw new TransitionInvalideException("Un inventaire du patrimoine est déjà en cours.");
        }
        Long id = jdbc.queryForObject("""
            insert into inventaires_immobilisations (reference, date_inventaire, observations, created_by_id)
            values (?, ?, ?, ?) returning id
            """, Long.class, reference("INVI", req.dateInventaire()), req.dateInventaire(), req.observations(),
            auteur.getId());
        // Biens relevés à l'inventaire (immobilisés, petit matériel, à valoriser), puis immobilisations en service
        // saisies sans code d'inventaire (achats directs).
        jdbc.update("""
            insert into inventaires_immobilisations_lignes (inventaire_id, bien_id, immobilisation_id)
            select ?, ib.id, ib.immobilisation_id from inventaire_biens ib
            where ib.statut in ('IMMOBILISE', 'PETIT_MATERIEL', 'A_VALORISER')
            """, id);
        jdbc.update("""
            insert into inventaires_immobilisations_lignes (inventaire_id, immobilisation_id)
            select ?, b.id from immobilisations b
            where b.statut = 'EN_SERVICE' and not exists (select 1 from inventaire_biens ib where ib.immobilisation_id = b.id)
            """, id);
        return consulterImmobilisations(id);
    }

    @PreAuthorize(SAISIE_IMMO)
    @Transactional
    public Inventaire constaterImmobilisation(Long id, Long ligneId, Comptage c) {
        exigerEnCours("inventaires_immobilisations", id);
        int n = jdbc.update("""
            update inventaires_immobilisations_lignes set present = ?, etat = ?, localisation_constatee = ?,
                commentaire = ?
            where id = ? and inventaire_id = ?
            """, c.present(), c.etat(), c.localisationConstatee(), c.commentaire(), ligneId, id);
        if (n == 0) throw RessourceIntrouvableException.of("LigneInventaire", ligneId);
        return consulterImmobilisations(id);
    }

    @PreAuthorize(VALIDATION)
    @Transactional
    public Inventaire validerImmobilisations(Long id) {
        User valideur = currentUser.requireUser();
        Map<String, Object> inv = exigerEnCours("inventaires_immobilisations", id);
        exigerAutrePersonne(inv, valideur);
        Long nonVus = jdbc.queryForObject(
            "select count(*) from inventaires_immobilisations_lignes where inventaire_id = ? and present is null",
            Long.class, id);
        if (nonVus != null && nonVus > 0) {
            throw new TransitionInvalideException(nonVus + " bien(s) n'ont pas été constatés (présent ou absent).");
        }
        jdbc.update("update inventaires_immobilisations set statut = 'VALIDE', valide_par_id = ?, valide_le = now() "
            + "where id = ?", valideur.getId(), id);
        return consulterImmobilisations(id);
    }

    @PreAuthorize(SAISIE_IMMO)
    @Transactional
    public void annulerImmobilisations(Long id) {
        exigerEnCours("inventaires_immobilisations", id);
        jdbc.update("update inventaires_immobilisations set statut = 'ANNULE' where id = ?", id);
    }

    // =====================================================================

    private Map<String, Object> exigerEnCours(String table, Long id) {
        List<Map<String, Object>> l = jdbc.queryForList("select * from " + table + " where id = ? for update", id);
        if (l.isEmpty()) throw RessourceIntrouvableException.of("Inventaire", id);
        if (!"EN_COURS".equals(l.get(0).get("statut"))) {
            throw new TransitionInvalideException("Cet inventaire est " + l.get(0).get("statut") + " : il ne change plus.");
        }
        return l.get(0);
    }

    /** Celui qui valide n'est pas celui qui a ouvert l'inventaire (séparation des tâches). */
    private static void exigerAutrePersonne(Map<String, Object> inv, User valideur) {
        Object createur = inv.get("created_by_id");
        if (createur != null && valideur.getId().equals(((Number) createur).longValue())) {
            throw new TransitionInvalideException(
                "Vous avez ouvert cet inventaire : il doit être validé par une autre personne.");
        }
    }

    private String reference(String prefixe, LocalDate date) {
        Long n = jdbc.queryForObject("select nextval('seq_inventaire')", Long.class);
        return "%s-%d-%06d".formatted(prefixe, date.getYear(), n);
    }

    private static Inventaire avecLignes(Inventaire i, List<Ligne> lignes) {
        return new Inventaire(i.id(), i.type(), i.reference(), i.entrepotId(), i.entrepot(), i.dateInventaire(),
            i.statut(), i.observations(), i.creePar(), i.creeLe(), i.validePar(), i.valideLe(), lignes);
    }
}
