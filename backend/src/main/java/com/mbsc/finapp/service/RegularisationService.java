package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Régularisations de fin d'exercice (audit SYSCOHADA du 10/11/2026, C-11 ; AUDCIF art. 59, indépendance des
 * exercices) :
 * <ul>
 *   <li><b>CCA</b>, charge constatée d'avance : D 476 / C charge (6x) — la part de la charge qui concerne l'exercice
 *       suivant ;</li>
 *   <li><b>PCA</b>, produit constaté d'avance : D produit (7x) / C 477 ;</li>
 *   <li><b>FNP</b>, facture non parvenue : D charge (6x) / C 4081 — charge de l'exercice dont la facture arrivera ;</li>
 *   <li><b>FAE</b>, facture à établir : D 4181 / C produit (7x).</li>
 * </ul>
 * Chaque régularisation écrit sa pièce à la date d'arrêté et son extourne au lendemain (le 1er janvier pour une
 * régularisation au 31 décembre) : l'exercice suivant repart des montants réels, comme le veut la méthode de
 * l'extourne. Saisie par le comptable ou le DFIN ; annulable tant que la date d'arrêté est ouverte.
 */
@Service
@RequiredArgsConstructor
public class RegularisationService {

    /** Comptes de régularisation par défaut, par type. */
    static final Map<String, String> COMPTE_PAR_DEFAUT = Map.of("CCA", "476", "PCA", "477", "FNP", "4081", "FAE", "4181");

    private final JdbcTemplate jdbc;
    private final ComptabiliteService comptabilite;
    private final CompteOHADARepository compteRepository;
    private final PieceComptableRepository pieceRepository;
    private final PeriodeComptableService periodeService;
    private final CurrentUserProvider currentUser;

    public record Requete(String type, String libelle, String compteGestionNumero, String compteRegularisationNumero,
                          BigDecimal montant, LocalDate dateRegularisation) {
    }

    public record Regularisation(Long id, String type, String libelle, String compteGestion,
                                 String compteRegularisation, BigDecimal montant, LocalDate dateRegularisation,
                                 LocalDate dateExtourne, String piece, String pieceExtourne, String statut,
                                 String creePar, LocalDateTime creeLe) {
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<Regularisation> lister(Integer annee) {
        return jdbc.query("""
            select r.id, r.type_regularisation, r.libelle, g.numero, c.numero, r.montant, r.date_regularisation,
                   r.date_extourne, p.reference, e.reference, r.statut, u.email, r.created_at
            from regularisations r
            join comptes_ohada g on g.id = r.compte_gestion_id
            join comptes_ohada c on c.id = r.compte_regularisation_id
            left join pieces_comptables p on p.id = r.piece_id
            left join pieces_comptables e on e.id = r.piece_extourne_id
            left join users u on u.id = r.created_by_id
            where cast(? as integer) is null or extract(year from r.date_regularisation) = cast(? as integer)
            order by r.date_regularisation desc, r.id desc
            """, (rs, i) -> new Regularisation(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getBigDecimal(6), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class),
            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
            rs.getObject(13, LocalDateTime.class)), annee, annee);
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN')")
    @Transactional
    public Regularisation creer(Requete req) {
        User auteur = currentUser.requireUser();
        String type = req.type() == null ? "" : req.type().trim().toUpperCase();
        if (!COMPTE_PAR_DEFAUT.containsKey(type)) {
            throw new IllegalArgumentException("Type de régularisation inconnu : CCA, PCA, FNP ou FAE.");
        }
        if (req.montant() == null || req.montant().signum() <= 0) {
            throw new IllegalArgumentException("Le montant doit être positif.");
        }
        if (req.dateRegularisation() == null || !StringUtils.hasText(req.libelle())) {
            throw new IllegalArgumentException("La date d'arrêté et le libellé sont obligatoires.");
        }
        boolean charge = type.equals("CCA") || type.equals("FNP");
        CompteOHADA gestion = compte(req.compteGestionNumero());
        if (gestion.getType() != (charge ? TypeCompte.CHARGE : TypeCompte.PRODUIT)) {
            throw new IllegalArgumentException("Une régularisation " + type + " porte sur un compte de "
                + (charge ? "charges (classe 6)" : "produits (classe 7)") + ", pas sur " + gestion.getNumero() + ".");
        }
        CompteOHADA regul = compte(StringUtils.hasText(req.compteRegularisationNumero())
            ? req.compteRegularisationNumero() : COMPTE_PAR_DEFAUT.get(type));
        ComptabiliteService.exigerContrepartieHorsTresorerie(regul.getNumero(), "compte de régularisation");

        LocalDate date = req.dateRegularisation();
        LocalDate extourne = date.plusDays(1);
        String libelle = type + " " + req.libelle().trim();
        BigDecimal m = req.montant();
        // CCA : D 476 / C charge ; PCA : D produit / C 477 ; FNP : D charge / C 408 ; FAE : D 418 / C produit.
        boolean regulAuDebit = type.equals("CCA") || type.equals("FAE");
        List<EcritureGrandLivre> lignes = List.of(
            ligne(regulAuDebit ? regul : gestion, m, BigDecimal.ZERO, libelle, date),
            ligne(regulAuDebit ? gestion : regul, BigDecimal.ZERO, m, libelle, date));
        PieceComptable piece = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES, libelle, date,
            lignes, auteur);
        String libelleExtourne = "Extourne d'ouverture " + piece.getReference() + " - " + libelle;
        PieceComptable pieceExtourne = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES,
            libelleExtourne, extourne, List.of(
                ligne(regulAuDebit ? regul : gestion, BigDecimal.ZERO, m, libelleExtourne, extourne),
                ligne(regulAuDebit ? gestion : regul, m, BigDecimal.ZERO, libelleExtourne, extourne)), auteur);
        pieceExtourne.setExtourneOuverture(true);
        pieceExtourne.setPieceOrigine(piece);
        pieceRepository.save(pieceExtourne);

        Long id = jdbc.queryForObject("""
            insert into regularisations (type_regularisation, libelle, compte_gestion_id, compte_regularisation_id,
                montant, date_regularisation, date_extourne, piece_id, piece_extourne_id, created_by_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, Long.class, type, req.libelle().trim(), gestion.getId(), regul.getId(), m, date, extourne,
            piece.getId(), pieceExtourne.getId(), auteur.getId());
        return lister(null).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    /** Annule une régularisation : ses deux pièces sont extournées (la date d'arrêté doit être ouverte). */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN')")
    @Transactional
    public void annuler(Long id) {
        User auteur = currentUser.requireUser();
        List<Map<String, Object>> l = jdbc.queryForList(
            "select piece_id, piece_extourne_id, statut, date_regularisation from regularisations where id = ? for update", id);
        if (l.isEmpty()) throw RessourceIntrouvableException.of("Regularisation", id);
        Map<String, Object> r = l.get(0);
        if (!"ACTIVE".equals(r.get("statut"))) {
            throw new TransitionInvalideException("Cette régularisation est déjà annulée.");
        }
        periodeService.verifierDateOuverte(((java.sql.Date) r.get("date_regularisation")).toLocalDate());
        for (String col : List.of("piece_extourne_id", "piece_id")) {
            Number pid = (Number) r.get(col);
            if (pid != null) comptabilite.annulerInterne(pid.longValue(), auteur);
        }
        jdbc.update("update regularisations set statut = 'ANNULEE' where id = ?", id);
    }

    private CompteOHADA compte(String numero) {
        if (!StringUtils.hasText(numero)) throw new IllegalArgumentException("Choisissez le compte.");
        return compteRepository.findByNumero(numero.trim())
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    private static EcritureGrandLivre ligne(CompteOHADA c, BigDecimal debit, BigDecimal credit, String libelle,
                                            LocalDate date) {
        return EcritureGrandLivre.builder().compte(c).debit(debit).credit(credit).libelle(libelle).dateEcriture(date)
            .build();
    }
}
