package com.mbsc.finapp.service;

import com.mbsc.finapp.dto.comptabilite.ControleClotureResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;

/**
 * Contrôles préalables à la clôture d'un exercice (audit SYSCOHADA du 10/11/2026, C-10 et C-26).
 *
 * <p>Un exercice ne se clôture qu'une fois l'inventaire fait et les régularisations passées (AUDCIF art. 19 et 42) :
 * plus aucun brouillon dans l'exercice, balance équilibrée, dotations aux amortissements passées, paie constatée,
 * relevés bancaires rapprochés et validés, taux de change de clôture saisi, inventaire physique des stocks et des
 * immobilisations validé. Chaque contrôle bloquant non satisfait empêche la demande comme l'approbation de la
 * clôture ; les avertissements s'affichent sans bloquer.</p>
 */
@Service
@RequiredArgsConstructor
public class ControlesClotureService {

    /** Fenêtre d'un inventaire physique rattaché à la clôture : jusqu'à 45 jours avant, 31 jours après. */
    static final int INVENTAIRE_JOURS_AVANT = 45;
    static final int INVENTAIRE_JOURS_APRES = 31;

    private final JdbcTemplate jdbc;
    private final PeriodeComptableService periodeService;

    @Transactional(readOnly = true)
    public List<ControleClotureResponse> controler(LocalDate date) {
        List<ControleClotureResponse> c = new ArrayList<>();
        LocalDate debut = date.withDayOfYear(1);

        boolean finAnnee = MonthDay.from(date).equals(MonthDay.of(12, 31));
        c.add(controle("DATE", "Date de clôture au 31 décembre", true, finAnnee,
            finAnnee ? null : "L'exercice coïncide avec l'année civile (AUDCIF art. 7) : clôturez au 31/12."));

        boolean passee = !date.isAfter(LocalDate.now());
        c.add(controle("DATE_PASSEE", "Date de clôture atteinte", true, passee,
            passee ? null : "On ne clôture pas un exercice avant son dernier jour."));

        LocalDate precedente = periodeService.dateClotureExercice();
        boolean posterieure = precedente == null || date.isAfter(precedente);
        c.add(controle("ORDRE", "Postérieure à la clôture précédente", true, posterieure,
            posterieure ? null : "L'exercice est déjà clôturé au " + precedente + "."));

        long brouillons = compter("select count(*) from pieces_comptables where statut = 'BROUILLON' and date_piece <= ?", date);
        c.add(controle("BROUILLONS", "Aucune pièce en brouillon dans l'exercice", true, brouillons == 0,
            brouillons == 0 ? null : brouillons + " pièce(s) en brouillon datée(s) au plus tard du " + date
                + " : comptabilisez-les ou supprimez-les."));

        BigDecimal ecart = jdbc.queryForObject("""
            select coalesce(sum(g.debit), 0) - coalesce(sum(g.credit), 0)
            from grand_livre g left join pieces_comptables p on p.id = g.piece_id
            where g.date_ecriture <= ? and (p.id is null or p.statut <> 'BROUILLON')
            """, BigDecimal.class, date);
        boolean equilibre = ecart == null || ecart.signum() == 0;
        c.add(controle("BALANCE", "Balance équilibrée", true, equilibre,
            equilibre ? null : "Écart débit - crédit de " + ecart + " au " + date + "."));

        long dotations = compter("""
            select count(*) from lignes_amortissement l join immobilisations i on i.id = l.immobilisation_id
            where not l.comptabilise and l.periode <= ? and i.statut = 'EN_SERVICE'
            """, date);
        c.add(controle("AMORTISSEMENTS", "Dotations aux amortissements passées", true, dotations == 0,
            dotations == 0 ? null : dotations + " mensualité(s) d'amortissement échue(s) non comptabilisée(s) : "
                + "lancez la comptabilisation des dotations jusqu'au " + date + "."));

        long paie = compter("""
            select count(*) from drh_bulletins_paie
            where statut = 'VALIDE' and piece_comptable_id is null and make_date(annee, mois, 1) <= ?
            """, date);
        c.add(controle("PAIE", "Paie de l'exercice constatée", true, paie == 0,
            paie == 0 ? null : paie + " bulletin(s) validé(s) sans constatation comptable : faites clôturer la paie "
                + "de ces mois par le DFIN."));

        long releves = compter("""
            select count(*) from releves_bancaires
            where statut <> 'VALIDE' and make_date(annee, mois, 1) <= ?
            """, date);
        c.add(controle("RAPPROCHEMENTS", "Relevés bancaires validés", true, releves == 0,
            releves == 0 ? null : releves + " relevé(s) bancaire(s) de l'exercice en cours de rapprochement : "
                + "terminez-les et faites-les valider."));

        long sansReleve = compter("""
            select count(*) from etablissements_tresorerie e
            where e.actif and e.type <> 'CAISSE'
              and exists (select 1 from grand_livre g where g.compte_id = e.compte_id and g.date_ecriture between ? and ?)
              and not exists (select 1 from releves_bancaires r where r.etablissement_id = e.id
                              and r.annee = ? and r.mois = 12 and r.statut = 'VALIDE')
            """, debut, date, date.getYear());
        c.add(controle("RELEVES_DECEMBRE", "Rapprochement de décembre fait", false, sansReleve == 0,
            sansReleve == 0 ? null : sansReleve + " compte(s) de banque ou de mobile money mouvementé(s) dans "
                + "l'exercice sans relevé de décembre validé."));

        long devises = compter("""
            select count(*) from grand_livre g left join pieces_comptables p on p.id = g.piece_id
            where g.date_ecriture <= ? and g.montant_devise is not null and g.devise <> ?
              and (p.id is null or p.statut <> 'BROUILLON')
            """, date, ConversionDeviseService.DEVISE_BASE.name());
        long taux = compter("select count(*) from taux_change where date_effet between ? and ?",
            date.minusDays(31), date);
        boolean tauxOk = devises == 0 || taux > 0;
        c.add(controle("TAUX", "Taux de change de clôture saisi", true, tauxOk,
            tauxOk ? null : "Des écritures sont en devise étrangère : saisissez le taux de change du " + date
                + " (Paramètres > Taux de change) avant de réévaluer les soldes."));

        long stocks = compter("select count(*) from stock_niveaux where quantite <> 0");
        long inventaireStock = compter("""
            select count(*) from inventaires_stock where statut = 'VALIDE' and date_inventaire between ? and ?
            """, date.minusDays(INVENTAIRE_JOURS_AVANT), date.plusDays(INVENTAIRE_JOURS_APRES));
        boolean stockOk = stocks == 0 || inventaireStock > 0;
        c.add(controle("INVENTAIRE_STOCKS", "Inventaire physique des stocks validé", true, stockOk,
            stockOk ? null : "Aucun inventaire physique des stocks validé autour du " + date
                + " (Logistique > Inventaires)."));

        long immobilisations = compter("select count(*) from immobilisations where statut = 'EN_SERVICE'");
        long inventaireImmo = compter("""
            select count(*) from inventaires_immobilisations where statut = 'VALIDE' and date_inventaire between ? and ?
            """, date.minusDays(INVENTAIRE_JOURS_AVANT), date.plusDays(INVENTAIRE_JOURS_APRES));
        boolean immoOk = immobilisations == 0 || inventaireImmo > 0;
        c.add(controle("INVENTAIRE_IMMOBILISATIONS", "Inventaire physique des immobilisations validé", true, immoOk,
            immoOk ? null : "Aucun inventaire physique des immobilisations validé autour du " + date
                + " (Patrimoine > Inventaires)."));

        long regularisations = compter("select count(*) from regularisations where statut = 'ACTIVE' and date_regularisation = ?", date);
        c.add(controle("REGULARISATIONS", "Régularisations de fin d'exercice examinées", false, regularisations > 0,
            regularisations > 0 ? null : "Aucune charge ou produit constaté d'avance, facture non parvenue ou produit "
                + "à recevoir au " + date + " : vérifiez qu'il n'y en a pas."));
        return c;
    }

    /** true si aucun contrôle bloquant n'échoue. */
    public static boolean clotureAutorisee(List<ControleClotureResponse> controles) {
        return controles.stream().noneMatch(c -> c.bloquant() && !c.satisfait());
    }

    /** Message des contrôles bloquants non satisfaits. */
    public static String motifsDeRefus(List<ControleClotureResponse> controles) {
        return String.join(" ", controles.stream()
            .filter(c -> c.bloquant() && !c.satisfait())
            .map(ControleClotureResponse::detail)
            .toList());
    }

    private long compter(String sql, Object... parametres) {
        Long n = jdbc.queryForObject(sql, Long.class, parametres);
        return n == null ? 0 : n;
    }

    private static ControleClotureResponse controle(String code, String libelle, boolean bloquant, boolean ok,
                                                    String detail) {
        return new ControleClotureResponse(code, libelle, bloquant, ok, detail);
    }
}
