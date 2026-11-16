package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Réévaluation générale, à la clôture, des soldes monétaires libellés en devise étrangère (audit SYSCOHADA du
 * 10/11/2026, C-12 ; AUDCIF art. 54 à 58).
 *
 * <p>Pour chaque compte de dettes financières (16), de tiers (classe 4) et de trésorerie (classe 5 hors 59) et pour
 * chaque devise, le solde en devise est converti au taux de clôture et comparé à sa valeur enregistrée :</p>
 * <ul>
 *   <li><b>tiers et emprunts</b> : l'écart est latent. Gain : 4791 (créances) ou 4792 (dettes) ; perte : 4781
 *       (créances) ou 4782 (dettes), et provision pour perte de change 194 par 6971. Ces écritures sont extournées
 *       au premier jour de l'exercice suivant (reprise de la provision en 7971) ;</li>
 *   <li><b>disponibilités</b> : l'écart est réalisé au sens du SYSCOHADA et va directement en résultat, 776 (gain)
 *       ou 676 (perte) ; il n'est pas extourné.</li>
 * </ul>
 *
 * <p>Les créances clients des ventes (4111) ont leur propre réévaluation, vente par vente (voir
 * {@link ClotureAnnuelleService}) : elles sont écartées ici. Une ligne d'ajustement porte la devise du solde et un
 * montant en devise nul : elle compte dans la valeur enregistrée sans changer le solde en devise, ce qui rend la
 * réévaluation suivante exacte.</p>
 */
@Service
@RequiredArgsConstructor
public class ReevaluationDevisesService {

    private static final Logger log = LoggerFactory.getLogger(ReevaluationDevisesService.class);

    static final String GAIN_CREANCES = "4791";
    static final String GAIN_DETTES = "4792";
    static final String PERTE_CREANCES = "4781";
    static final String PERTE_DETTES = "4782";
    static final String PROVISION_PERTE_CHANGE = "194";
    static final String DOTATION_PROVISION = "6971";
    static final String REPRISE_PROVISION = "7971";
    static final String GAIN_CHANGE = "776";
    static final String PERTE_CHANGE = "676";

    private final JdbcTemplate jdbc;
    private final ComptabiliteService comptabilite;
    private final CompteOHADARepository compteRepository;
    private final PieceComptableRepository pieceRepository;
    private final ConversionDeviseService conversionDevise;

    /** Résultat : références des pièces passées et nombre de soldes réévalués. */
    public record Resultat(List<String> pieces, int soldes) {
    }

    /** Un solde en devise : compte, devise, valeur enregistrée (devise de base), solde en devise. */
    record Solde(String compte, String devise, BigDecimal enregistre, BigDecimal enDevise) {
    }

    @Transactional
    public Resultat reevaluer(LocalDate dateCloture, User auteur) {
        List<Solde> soldes = soldes(dateCloture);
        if (soldes.isEmpty()) {
            return new Resultat(List.of(), 0);
        }
        BigDecimal taux = tauxDeCloture(dateCloture);
        List<String> pieces = new ArrayList<>();
        List<EcritureGrandLivre> latentes = new ArrayList<>();
        List<EcritureGrandLivre> realisees = new ArrayList<>();
        BigDecimal pertesLatentes = BigDecimal.ZERO;
        String libelle = "Réévaluation des soldes en devise au " + dateCloture;
        int nb = 0;

        for (Solde s : soldes) {
            BigDecimal valeur = valeurAuTaux(s.enDevise(), Devise.valueOf(s.devise()), taux);
            BigDecimal ecart = valeur.subtract(s.enregistre());
            if (ecart.signum() == 0) continue;
            nb++;
            CompteOHADA compte = compte(s.compte());
            String lib = libelle + " (" + s.compte() + " " + s.devise() + ")";
            boolean tresorerie = s.compte().startsWith("5");
            if (tresorerie) {
                realisees.add(ligne(compte, ecart, lib, dateCloture, s.devise()));
                realisees.add(ligne(compte(ecart.signum() > 0 ? GAIN_CHANGE : PERTE_CHANGE), ecart.negate(), lib,
                    dateCloture, null));
            } else {
                boolean creance = s.enregistre().signum() >= 0;
                latentes.add(ligne(compte, ecart, lib, dateCloture, s.devise()));
                String ecartConversion = ecart.signum() > 0
                    ? (creance ? GAIN_CREANCES : GAIN_DETTES)
                    : (creance ? PERTE_CREANCES : PERTE_DETTES);
                latentes.add(ligne(compte(ecartConversion), ecart.negate(), lib, dateCloture, null));
                if (ecart.signum() < 0) pertesLatentes = pertesLatentes.add(ecart.negate());
            }
        }

        if (!realisees.isEmpty()) {
            // Le relevé de décembre est déjà rapproché et validé (contrôle de clôture) : l'ajustement ne touche que la
            // contre-valeur en devise de base, pas le solde en devise du relevé.
            PieceComptable p = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES,
                libelle + " — disponibilités", dateCloture, realisees, auteur, false);
            pieces.add(p.getReference());
        }
        if (!latentes.isEmpty()) {
            if (pertesLatentes.signum() > 0) {
                String libProvision = "Provision pour perte de change au " + dateCloture;
                latentes.add(ligne(compte(DOTATION_PROVISION), pertesLatentes, libProvision, dateCloture, null));
                latentes.add(ligne(compte(PROVISION_PERTE_CHANGE), pertesLatentes.negate(), libProvision, dateCloture, null));
            }
            PieceComptable p = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES,
                libelle + " — tiers et emprunts", dateCloture, latentes, auteur);
            p.setReevaluationDevise(true);
            pieceRepository.save(p);
            pieces.add(p.getReference());
            pieces.add(extournerALOuverture(p, dateCloture.plusDays(1), auteur).getReference());
        }
        log.info("Réévaluation des soldes en devise au {} : {} solde(s), pièces {}", dateCloture, nb, pieces);
        return new Resultat(pieces, nb);
    }

    /**
     * Extourne d'ouverture : la pièce contre-passée au lendemain de la clôture. La provision pour perte de change est
     * reprise en 7971 (reprise de provision financière) plutôt que portée au crédit de la dotation de l'exercice
     * suivant.
     */
    PieceComptable extournerALOuverture(PieceComptable piece, LocalDate date, User auteur) {
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        String libelle = "Extourne d'ouverture " + piece.getReference() + " - " + piece.getLibelle();
        for (EcritureGrandLivre l : piece.getLignes()) {
            CompteOHADA c = l.getCompte();
            if (DOTATION_PROVISION.equals(c.getNumero())) c = compte(REPRISE_PROVISION);
            lignes.add(EcritureGrandLivre.builder().compte(c).debit(l.getCredit()).credit(l.getDebit())
                .libelle(libelle).dateEcriture(date).devise(l.getDevise()).montantDevise(l.getMontantDevise())
                .build());
        }
        PieceComptable extourne = comptabilite.creerPieceInterne(piece.getJournal(), libelle, date, lignes, auteur);
        extourne.setExtourneOuverture(true);
        extourne.setReevaluationDevise(piece.isReevaluationDevise());
        extourne.setPieceOrigine(piece);
        return pieceRepository.save(extourne);
    }

    /** Soldes en devise étrangère par compte et par devise, à la date de clôture (créances des ventes exclues). */
    List<Solde> soldes(LocalDate date) {
        return jdbc.query("""
            select c.numero, g.devise,
                   sum(g.debit - g.credit) as enregistre,
                   sum(case when g.debit > 0 then coalesce(g.montant_devise, 0) else -coalesce(g.montant_devise, 0) end) as en_devise
            from grand_livre g
            join comptes_ohada c on c.id = g.compte_id
            left join pieces_comptables p on p.id = g.piece_id
            where g.date_ecriture <= ?
              and g.montant_devise is not null
              and g.devise <> ?
              and (p.id is null or p.statut <> 'BROUILLON')
              and (c.numero like '16%' or c.numero like '4%' or c.numero like '5%')
              and c.numero not like '59%' and c.numero not like '478%' and c.numero not like '479%'
              and c.numero not like ?
            group by c.numero, g.devise
            having sum(g.debit - g.credit) <> 0
                or sum(case when g.debit > 0 then coalesce(g.montant_devise, 0) else -coalesce(g.montant_devise, 0) end) <> 0
            order by c.numero, g.devise
            """, (rs, i) -> new Solde(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)),
            date, ConversionDeviseService.DEVISE_BASE.name(), VenteService.COMPTE_CLIENTS + "%");
    }

    private BigDecimal tauxDeCloture(LocalDate date) {
        List<Map<String, Object>> taux = jdbc.queryForList(
            "select taux from taux_change where date_effet between ? and ? order by date_effet desc, created_at desc limit 1",
            date.minusDays(31), date);
        if (taux.isEmpty()) {
            throw new TransitionInvalideException("Aucun taux de change saisi dans le mois qui précède le " + date
                + " : la réévaluation des soldes en devise est impossible.");
        }
        return (BigDecimal) taux.get(0).get("taux");
    }

    private BigDecimal valeurAuTaux(BigDecimal montantDevise, Devise devise, BigDecimal taux) {
        BigDecimal base = conversionDevise.enDeviseBase(montantDevise.abs(), devise, taux).montantBase();
        return montantDevise.signum() < 0 ? base.negate() : base;
    }

    /** Ligne au débit si le montant est positif, au crédit sinon ; la devise marque une ligne d'ajustement. */
    private static EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal montant, String libelle, LocalDate date,
                                            String devise) {
        var b = EcritureGrandLivre.builder().compte(compte)
            .debit(montant.signum() > 0 ? montant : BigDecimal.ZERO)
            .credit(montant.signum() < 0 ? montant.negate() : BigDecimal.ZERO)
            .libelle(libelle).dateEcriture(date);
        if (devise != null) {
            b.devise(devise).montantDevise(BigDecimal.ZERO);
        } else {
            b.devise(ConversionDeviseService.DEVISE_BASE.name());
        }
        return b.build();
    }

    private CompteOHADA compte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new TransitionInvalideException("Compte " + numero
                + " absent du plan comptable : réévaluation des soldes en devise impossible."));
    }
}
