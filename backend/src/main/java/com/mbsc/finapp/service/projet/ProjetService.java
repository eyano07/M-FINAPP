package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.ConversionDeviseService;
import com.mbsc.finapp.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Projets (V167), côté logistique : le logisticien crée le projet et sa liste de besoins, l'envoie au comptable (qui en
 * tire le budget secondaire, voir {@link ProjetBudgetService}), puis tient la fiche de stock du projet : entrées par les
 * achats payés (automatiques, {@link ProjetStockService}), sorties, transferts entre projets, ajustements, affectation des
 * acquisitions. Cette fiche est SÉPARÉE du stock global : aucune variation de stock, aucune pièce comptable.
 */
@Service
@RequiredArgsConstructor
public class ProjetService {

    static final String LECTURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";
    static final String ECRITURE = "hasRole('LOGISTIQUE')";

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;
    private final NotificationService notifications;
    private final ConversionDeviseService conversion;

    // ---------------------------------------------------------------------
    // Vues
    // ---------------------------------------------------------------------

    public record Projet(Long id, String code, String nom, String description, String statut, String motifRetour,
                         Long budgetId, String budgetReference, String budgetStatut, String creePar, OffsetDateTime creeLe,
                         OffsetDateTime envoyeLe, OffsetDateTime budgeteLe, int nombreBesoins, BigDecimal prevuUsd,
                         BigDecimal payeUsd) { }

    public record Article(Long id, Long projetId, String designation, String unite, BigDecimal quantitePrevue,
                          BigDecimal prixEstimeUsd, String nature, String compteParent, Long compteId, String compteNumero,
                          String compteLibelle, BigDecimal entrees, BigDecimal sorties, BigDecimal solde, BigDecimal valeurUsd,
                          BigDecimal payeUsd) { }

    public record Mouvement(Long id, Long articleId, String designation, LocalDate date, String type, BigDecimal quantite,
                            BigDecimal montantUsd, Long noteId, String noteReference, String projetLie, String commentaire,
                            String auteur) { }

    public record Acquisition(Long id, String reference, String libelle, String compte, LocalDate dateAcquisition,
                              BigDecimal valeurUsd, String statut, Long projetId, String projetNom) { }

    public record NoteLiee(Long id, String reference, String objet, String statut, BigDecimal montantUsd) { }

    /** Dépenses du projet en USD : prévu (budget, sinon besoins), engagé (notes validées non payées), payé, reste. */
    public record Depenses(BigDecimal prevuBudget, BigDecimal prevuBesoins, BigDecimal engage, BigDecimal paye,
                           BigDecimal reste, List<NoteLiee> notes) { }

    public record Detail(Projet projet, List<Article> articles, List<Mouvement> mouvements, List<Acquisition> acquisitions,
                         Depenses depenses) { }

    /**
     * Compte « Projet — … » proposé dans une note de frais (V169) : lié à aucun projet, avec le prix unitaire et l'unité
     * du dernier besoin budgété sur ce compte, pour calculer le montant de la ligne d'après la quantité.
     */
    public record CompteProjet(String numero, String libelle, String unite, BigDecimal prixUnitaireUsd) { }

    // ---------------------------------------------------------------------
    // Requêtes
    // ---------------------------------------------------------------------

    public record ProjetRequest(@NotBlank @Size(max = 150) String nom, @Size(max = 1000) String description) { }

    public record BesoinRequest(@NotBlank @Size(max = 200) String designation, @Size(max = 30) String unite,
                                @NotNull @DecimalMin("0.001") BigDecimal quantite,
                                @NotNull @DecimalMin("0.00") BigDecimal prixEstimeUsd,
                                @NotNull @Pattern(regexp = "ACHAT|ACQUISITION") String nature) { }

    public record BesoinsRequest(@NotNull @Valid List<BesoinRequest> besoins) { }

    /** SORTIE (utilisation sur le projet), AJUSTEMENT (+/- après comptage). TRANSFERT est refusé : l'affectation est définitive. */
    public record MouvementRequest(@NotNull Long articleId, @NotNull @Pattern(regexp = "SORTIE|AJUSTEMENT|TRANSFERT") String type,
                                   @NotNull BigDecimal quantite, Long projetCibleId, LocalDate date,
                                   @Size(max = 500) String commentaire) { }

    /** Projet qui reçoit ; quantité affectée (achat du stock commun : tout le solde si vide). */
    public record AffectationRequest(Long projetId, BigDecimal quantite) {
        public AffectationRequest(Long projetId) {
            this(projetId, null);
        }
    }

    private static final String SELECT_PROJET = """
        select p.*, b.reference as budget_reference, b.statut as budget_statut,
               (select count(*) from projet_articles a where a.projet_id = p.id) as nb,
               (select coalesce(sum(a.quantite_prevue * a.prix_estime_usd), 0) from projet_articles a where a.projet_id = p.id) as prevu,
               (select coalesce(sum(m.montant_usd), 0) from projet_mouvements m join projet_articles a on a.id = m.article_id
                 where a.projet_id = p.id and m.type in ('ACHAT', 'RETOUR_FONDS', 'AFFECTATION', 'TRANSFERT_ENTREE', 'TRANSFERT_SORTIE')) as paye
        from projets p left join budgets b on b.id = p.budget_id
        """;

    private static final RowMapper<Projet> PROJET = (rs, i) -> new Projet(rs.getLong("id"), rs.getString("code"),
        rs.getString("nom"), rs.getString("description"), rs.getString("statut"), rs.getString("motif_retour"),
        (Long) rs.getObject("budget_id"), rs.getString("budget_reference"), rs.getString("budget_statut"),
        rs.getString("cree_par"), rs.getObject("cree_le", OffsetDateTime.class), rs.getObject("envoye_le", OffsetDateTime.class),
        rs.getObject("budgete_le", OffsetDateTime.class), rs.getInt("nb"), rs.getBigDecimal("prevu"), rs.getBigDecimal("paye"));

    private static final String SELECT_ARTICLE = """
        select a.*, c.numero as compte_numero, c.libelle as compte_libelle,
               coalesce(sum(m.quantite) filter (where m.quantite > 0), 0) as entrees,
               coalesce(-sum(m.quantite) filter (where m.quantite < 0), 0) as sorties,
               coalesce(sum(m.quantite), 0) as solde,
               coalesce(sum(m.montant_usd), 0) as valeur,
               coalesce(sum(m.montant_usd) filter (where m.type in ('ACHAT', 'RETOUR_FONDS', 'AFFECTATION', 'TRANSFERT_ENTREE', 'TRANSFERT_SORTIE')), 0) as paye
        from projet_articles a left join comptes_ohada c on c.id = a.compte_id
        left join projet_mouvements m on m.article_id = a.id
        """;

    private static final RowMapper<Article> ARTICLE = (rs, i) -> new Article(rs.getLong("id"), (Long) rs.getObject("projet_id"),
        rs.getString("designation"), rs.getString("unite"), rs.getBigDecimal("quantite_prevue"), rs.getBigDecimal("prix_estime_usd"),
        rs.getString("nature"), rs.getString("compte_parent"), (Long) rs.getObject("compte_id"), rs.getString("compte_numero"),
        rs.getString("compte_libelle"), rs.getBigDecimal("entrees"), rs.getBigDecimal("sorties"), rs.getBigDecimal("solde"),
        rs.getBigDecimal("valeur"), rs.getBigDecimal("paye"));

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Projet> lister() {
        return jdbc.query(SELECT_PROJET + " order by p.cree_le desc", PROJET);
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public Detail consulter(Long id) {
        Projet p = projet(id);
        return new Detail(p, articles(id), mouvements(id), acquisitions(id), depenses(p));
    }

    /** Achats sur un compte « Projet … » qui n'appartient à aucun projet (créé à la main) : à rattacher. */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Article> aAffecter() {
        return jdbc.query(SELECT_ARTICLE + " where a.projet_id is null group by a.id, c.numero, c.libelle order by a.id", ARTICLE);
    }

    /** Acquisitions (fiches d'immobilisation actives) : celles du projet, et celles qu'aucun projet n'a encore. */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Acquisition> acquisitionsAffectables() {
        return jdbc.query(SELECT_ACQUISITION + " where i.projet_id is null and i.statut = 'EN_SERVICE' order by i.date_acquisition desc",
            ACQUISITION);
    }

    /** Comptes « Projet … » imputables, pour la saisie des notes de frais (quantité, budget du projet présélectionné). */
    @Transactional(readOnly = true)
    public List<CompteProjet> comptesProjet() {
        return jdbc.query("""
            select c.numero, c.libelle,
                   (select a.unite from projet_articles a where a.compte_id = c.id and a.projet_id is not null
                     order by a.id desc limit 1) as unite,
                   (select a.prix_estime_usd from projet_articles a where a.compte_id = c.id and a.projet_id is not null
                     order by a.id desc limit 1) as prix
            from comptes_ohada c
            where c.imputable and c.actif and lower(trim(c.libelle)) like 'projet%'
            order by c.numero
            """, (rs, i) -> new CompteProjet(rs.getString("numero"), rs.getString("libelle"), rs.getString("unite"),
            rs.getBigDecimal("prix")));
    }

    // ---------------------------------------------------------------------
    // Projet et besoins (logistique)
    // ---------------------------------------------------------------------

    @PreAuthorize(ECRITURE)
    @Transactional
    public Projet creer(ProjetRequest r) {
        Long n = jdbc.queryForObject("select nextval('seq_projet')", Long.class);
        Long id = jdbc.queryForObject("""
            insert into projets (code, nom, description, cree_par) values (?, ?, ?, ?) returning id
            """, Long.class, "PRJ-%04d".formatted(n), r.nom().trim(), vide(r.description()), nom(currentUser.requireUser()));
        return projet(id);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Projet modifier(Long id, ProjetRequest r) {
        Projet p = projet(id);
        exigerModifiable(p);
        jdbc.update("update projets set nom = ?, description = ? where id = ?", r.nom().trim(), vide(r.description()), id);
        return projet(id);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public void supprimer(Long id) {
        Projet p = projet(id);
        if (!"BROUILLON".equals(p.statut()) && !"RETOURNE".equals(p.statut())) {
            throw new TransitionInvalideException("Seul un projet pas encore budgété se supprime.");
        }
        jdbc.update("delete from projets where id = ?", id);
    }

    /** Remplace la liste des besoins (projet en brouillon ou retourné par le comptable). */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Detail enregistrerBesoins(Long id, BesoinsRequest r) {
        Projet p = projet(id);
        exigerModifiable(p);
        jdbc.update("delete from projet_articles where projet_id = ?", id);
        int ordre = 0;
        for (BesoinRequest b : r.besoins()) {
            jdbc.update("""
                insert into projet_articles (projet_id, designation, unite, quantite_prevue, prix_estime_usd, nature, compte_parent, ordre)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, b.designation().trim(), vide(b.unite()), b.quantite(), b.prixEstimeUsd().setScale(2, RoundingMode.HALF_UP),
                b.nature(), "ACHAT".equals(b.nature()) ? ComptesProjetService.PARENT_ACHAT : ComptesProjetService.PARENT_ACQUISITION,
                ordre++);
        }
        return consulter(id);
    }

    /** Envoi au comptable, qui crée le budget du projet ; le comptable et le DFIN sont prévenus. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Projet envoyer(Long id) {
        Projet p = projet(id);
        exigerModifiable(p);
        if (p.nombreBesoins() == 0) {
            throw new IllegalArgumentException("Ajoutez au moins un besoin avant d'envoyer le projet au comptable.");
        }
        jdbc.update("update projets set statut = 'ENVOYE', envoye_le = now(), motif_retour = null where id = ?", id);
        String message = p.code() + " — " + p.nom() + " : " + p.nombreBesoins() + " besoin(s), "
            + p.prevuUsd().setScale(2, RoundingMode.HALF_UP).toPlainString() + " USD estimés. Créez le budget du projet.";
        for (RoleType role : List.of(RoleType.COMPTABLE, RoleType.DFIN)) {
            notifications.notifierRole(role, TypeNotification.PROJET_A_BUDGETER, "Projet à budgéter", message, "/budgets/projets", null);
        }
        return projet(id);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Projet cloturer(Long id) {
        Projet p = projet(id);
        if (!"BUDGETE".equals(p.statut())) {
            throw new TransitionInvalideException("Seul un projet budgété se clôture.");
        }
        jdbc.update("update projets set statut = 'CLOTURE' where id = ?", id);
        return projet(id);
    }

    // ---------------------------------------------------------------------
    // Fiche de stock projet (aucune pièce comptable)
    // ---------------------------------------------------------------------

    @PreAuthorize(ECRITURE)
    @Transactional
    public Detail mouvement(Long projetId, MouvementRequest r) {
        Article a = article(r.articleId());
        if (a.projetId() == null || !a.projetId().equals(projetId)) {
            throw new IllegalArgumentException("Cet article n'appartient pas à ce projet.");
        }
        BigDecimal q = r.quantite().setScale(3, RoundingMode.HALF_UP);
        LocalDate date = r.date() == null ? LocalDate.now() : r.date();
        String auteur = nom(currentUser.requireUser());
        switch (r.type()) {
            case "AJUSTEMENT" -> {
                if (q.signum() == 0) throw new IllegalArgumentException("Un ajustement porte sur une quantité non nulle.");
                if (a.solde().add(q).signum() < 0) throw stockInsuffisant(a);
                inserer(a.id(), date, "AJUSTEMENT", q, valeurDe(a, q), null, r.commentaire(), auteur);
            }
            case "SORTIE" -> {
                exigerPositive(q);
                if (a.solde().compareTo(q) < 0) throw stockInsuffisant(a);
                inserer(a.id(), date, "SORTIE", q.negate(), valeurDe(a, q).negate(), null, r.commentaire(), auteur);
            }
            // TRANSFERT : un bien affecté à un projet ne peut plus être affecté à un autre (27/11/2026).
            default -> throw new IllegalArgumentException("Un bien affecté à un projet ne peut plus être affecté à un autre projet.");
        }
        return consulter(projetId);
    }

    /**
     * Affecte au projet une quantité d'un achat qui attend dans le stock commun des projets (V169) : sortie du stock commun,
     * entrée dans l'article du projet sur le même compte (créé au besoin), au coût moyen. C'est cette affectation qui
     * exécute la ligne du budget du projet.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Article affecterArticle(Long articleId, AffectationRequest r) {
        Article a = article(articleId);
        if (a.projetId() != null) {
            throw new IllegalArgumentException("Cet article est déjà dans un projet : transférez-le depuis la fiche du projet.");
        }
        if (r.projetId() == null) {
            throw new IllegalArgumentException("Choisissez le projet qui reçoit le bien.");
        }
        Projet cible = projet(r.projetId());
        BigDecimal q = (r.quantite() == null ? a.solde() : r.quantite()).setScale(3, RoundingMode.HALF_UP);
        exigerPositive(q);
        if (a.solde().compareTo(q) < 0) throw stockInsuffisant(a);
        Long articleCible = articleDuTransfert(cible.id(), a);
        BigDecimal valeur = valeurDe(a, q);
        LocalDate date = LocalDate.now(com.mbsc.finapp.config.FuseauEntreprise.ZONE);
        String auteur = nom(currentUser.requireUser());
        String commentaire = "Affectation au projet " + cible.code() + " — " + cible.nom();
        inserer(a.id(), date, "AFFECTATION_SORTIE", q.negate(), valeur.negate(), articleCible, commentaire, auteur);
        inserer(articleCible, date, "AFFECTATION", q, valeur, a.id(), "Reçu du stock commun des projets", auteur);
        return article(articleCible);
    }

    /** Affecte une acquisition (fiche d'immobilisation) à un projet, ou la retire (projetId null). */
    @PreAuthorize(ECRITURE)
    @Transactional
    public Acquisition affecterAcquisition(Long immobilisationId, AffectationRequest r) {
        if (r.projetId() != null) projet(r.projetId());
        // Affectation définitive : une acquisition déjà affectée à un projet n'en change plus.
        Long actuel = jdbc.query("select projet_id from immobilisations where id = ?", (rs, i) -> (Long) rs.getObject(1), immobilisationId)
            .stream().findFirst().orElse(null);
        if (actuel != null) {
            throw new IllegalArgumentException("Cette acquisition est déjà affectée à un projet : elle ne peut plus en changer.");
        }
        int n = jdbc.update("update immobilisations set projet_id = ? where id = ?", r.projetId(), immobilisationId);
        if (n == 0) throw RessourceIntrouvableException.of("Immobilisation", immobilisationId);
        return jdbc.query(SELECT_ACQUISITION + " where i.id = ?", ACQUISITION, immobilisationId).get(0);
    }

    // ---------------------------------------------------------------------
    // Interne
    // ---------------------------------------------------------------------

    public Projet projet(Long id) {
        return jdbc.query(SELECT_PROJET + " where p.id = ?", PROJET, id).stream().findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Projet", id));
    }

    public List<Article> articles(Long projetId) {
        return jdbc.query(SELECT_ARTICLE + " where a.projet_id = ? group by a.id, c.numero, c.libelle order by a.ordre, a.id",
            ARTICLE, projetId);
    }

    public Article article(Long id) {
        return jdbc.query(SELECT_ARTICLE + " where a.id = ? group by a.id, c.numero, c.libelle", ARTICLE, id).stream().findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Article de projet", id));
    }

    public List<Mouvement> mouvements(Long projetId) {
        return jdbc.query("""
            select m.*, a.designation, n.reference as note_reference, pl.nom as projet_lie
            from projet_mouvements m join projet_articles a on a.id = m.article_id
            left join notes_frais n on n.id = m.note_id
            left join projet_articles al on al.id = m.article_lie_id left join projets pl on pl.id = al.projet_id
            where a.projet_id = ? order by m.date_mouvement desc, m.id desc
            """, (rs, i) -> new Mouvement(rs.getLong("id"), rs.getLong("article_id"), rs.getString("designation"),
            rs.getObject("date_mouvement", LocalDate.class), rs.getString("type"), rs.getBigDecimal("quantite"),
            rs.getBigDecimal("montant_usd"), (Long) rs.getObject("note_id"), rs.getString("note_reference"),
            rs.getString("projet_lie"), rs.getString("commentaire"), rs.getString("auteur")), projetId);
    }

    private static final String SELECT_ACQUISITION = """
        select i.id, i.reference, i.libelle, c.numero as compte, i.date_acquisition, i.valeur_acquisition, i.statut,
               i.projet_id, p.nom as projet_nom
        from immobilisations i left join comptes_ohada c on c.id = i.compte_immobilisation_id
        left join projets p on p.id = i.projet_id
        """;

    private static final RowMapper<Acquisition> ACQUISITION = (rs, i) -> new Acquisition(rs.getLong("id"),
        rs.getString("reference"), rs.getString("libelle"), rs.getString("compte"), rs.getObject("date_acquisition", LocalDate.class),
        rs.getBigDecimal("valeur_acquisition"), rs.getString("statut"), (Long) rs.getObject("projet_id"), rs.getString("projet_nom"));

    public List<Acquisition> acquisitions(Long projetId) {
        return jdbc.query(SELECT_ACQUISITION + " where i.projet_id = ? order by i.date_acquisition desc", ACQUISITION, projetId);
    }

    /** Dépenses en USD ; l'engagé convertit les notes en francs au taux d'engagement, sinon au taux du jour. */
    public Depenses depenses(Projet p) {
        BigDecimal prevuBudget = p.budgetId() == null ? null : jdbc.queryForObject("""
            select coalesce(sum(lb.montant_prevu), 0) from lignes_budget lb join budgets b on b.id = lb.budget_id
            where b.id = (select coalesce(max(r.id), ?) from budgets r
                          where (r.id = ? or r.revision_de_id = ?) and r.statut in ('EN_EXECUTION', 'CLOTURE'))
            """, BigDecimal.class, p.budgetId(), p.budgetId(), p.budgetId());
        BigDecimal taux = tauxDuJour();
        List<NoteLiee> notes = jdbc.query("""
            select n.id, n.reference, n.objet, n.statut,
                   sum(case when n.devise = 'USD' then l.montant
                            else l.montant / nullif(coalesce(n.taux_engagement, ?), 0) end) as montant
            from notes_frais n join lignes_note_frais l on l.note_id = n.id
            join comptes_ohada c on c.id = l.compte_imputation_id
            where n.budget_secondaire_id = ? and lower(trim(c.libelle)) like 'projet%' and n.statut <> 'ANNULEE'
            group by n.id order by n.date_creation desc
            """, (rs, i) -> new NoteLiee(rs.getLong("id"), rs.getString("reference"), rs.getString("objet"), rs.getString("statut"),
            rs.getBigDecimal("montant") == null ? BigDecimal.ZERO : rs.getBigDecimal("montant").setScale(2, RoundingMode.HALF_UP)),
            taux, p.budgetId() == null ? -1L : p.budgetId());
        // Engagé : les biens payés disponibles dans le stock des projets sur les comptes de ce projet, pas encore affectés
        // (une note approuvée non payée ne compte pas).
        BigDecimal enAttente = jdbc.queryForObject("""
            select coalesce(sum(m.montant_usd), 0) from projet_mouvements m join projet_articles a on a.id = m.article_id
            where a.projet_id is null
              and a.compte_id in (select compte_id from projet_articles where projet_id = ? and compte_id is not null)
            """, BigDecimal.class, p.id());
        BigDecimal engage = enAttente;
        BigDecimal paye = p.payeUsd();
        BigDecimal reference = prevuBudget != null ? prevuBudget : p.prevuUsd();
        return new Depenses(prevuBudget, p.prevuUsd(), engage, paye, reference.subtract(engage).subtract(paye), notes);
    }

    private BigDecimal tauxDuJour() {
        try {
            BigDecimal t = conversion.tauxCourant();
            return t == null || t.signum() <= 0 ? BigDecimal.ONE : t;
        } catch (RuntimeException e) {
            return BigDecimal.ONE;
        }
    }

    /** Article du projet cible qui reçoit le transfert : même désignation, sinon créé (sans compte : pas un achat). */
    /** Article du projet qui reçoit : même compte « Projet — … » (sinon même désignation), créé au besoin. */
    private Long articleDuTransfert(Long projetCibleId, Article source) {
        return jdbc.query("""
                select id from projet_articles where projet_id = ?
                  and (compte_id = ? or (compte_id is null and lower(designation) = lower(?)))
                order by (compte_id is null), id limit 1
                """, (rs, i) -> rs.getLong(1), projetCibleId, source.compteId(), source.designation())
            .stream().findFirst()
            .orElseGet(() -> jdbc.queryForObject("""
                insert into projet_articles (projet_id, designation, unite, nature, compte_id, ordre)
                values (?, ?, ?, ?, ?, 1000) returning id
                """, Long.class, projetCibleId, source.designation(), source.unite(), source.nature(), source.compteId()));
    }

    /** Valeur d'une quantité au coût moyen de l'article (valeur / solde). */
    private static BigDecimal valeurDe(Article a, BigDecimal q) {
        if (a.solde().signum() <= 0 || a.valeurUsd().signum() <= 0) return BigDecimal.ZERO;
        return a.valeurUsd().multiply(q.abs()).divide(a.solde(), 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(q.signum()));
    }

    public void inserer(Long articleId, LocalDate date, String type, BigDecimal quantite, BigDecimal montant, Long articleLie,
                 String commentaire, String auteur) {
        jdbc.update("""
            insert into projet_mouvements (article_id, date_mouvement, type, quantite, montant_usd, article_lie_id, commentaire, auteur)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, articleId, date, type, quantite, montant, articleLie, vide(commentaire), auteur);
    }

    private static void exigerModifiable(Projet p) {
        if (!"BROUILLON".equals(p.statut()) && !"RETOURNE".equals(p.statut())) {
            throw new TransitionInvalideException("Le projet " + p.code() + " est « " + p.statut().toLowerCase()
                + " » : sa liste de besoins ne se modifie plus.");
        }
    }

    private static void exigerPositive(BigDecimal q) {
        if (q.signum() <= 0) throw new IllegalArgumentException("La quantité doit être positive.");
    }

    private static IllegalArgumentException stockInsuffisant(Article a) {
        return new IllegalArgumentException("Stock insuffisant pour « " + a.designation() + " » : "
            + a.solde().stripTrailingZeros().toPlainString() + (a.unite() == null ? "" : " " + a.unite()) + " disponible(s).");
    }

    static String nom(User u) {
        if (u == null) return null;
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static String vide(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
