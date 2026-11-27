package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.service.ConversionDeviseService;
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
import java.util.List;
import java.util.Map;

/**
 * Alimentation de la fiche de stock projet (V167) par les achats PAYÉS imputés à un compte dont le libellé commence par
 * « Projet » (achats divers 6588…, acquisitions 6058… ou 21-24…) : une entrée ACHAT par ligne de note (quantité de la
 * ligne, montant HT en USD au taux du paiement), une fois seulement, dans le STOCK COMMUN DES PROJETS (V169 : article
 * sans projet, un par compte) ; le logisticien affecte ensuite les biens aux projets. Aucune écriture : la fiche de stock
 * projet est séparée du stock global.
 */
@Service
@RequiredArgsConstructor
public class ProjetStockService {

    private static final Logger log = LoggerFactory.getLogger(ProjetStockService.class);

    private final JdbcTemplate jdbc;
    private final ConversionDeviseService conversion;
    private final NoteFraisRepository notes;

    /** Au paiement d'une note (caisse, banque, mobile money), dans la transaction du paiement. */
    @Transactional
    public void apresPaiementInterne(NoteFrais note, User auteur, BigDecimal taux) {
        for (LigneNoteFrais l : note.getLignes()) {
            entrerAchat(note, l, taux, auteur == null ? null : ProjetService.nom(auteur));
        }
    }

    /**
     * Fiche d'immobilisation créée au paiement d'une acquisition de projet (compte 21-24 « Projet … ») : depuis V169, un
     * compte « Projet — … » n'est lié à aucun projet ; la fiche reste sans projet, le logisticien l'affecte (onglet
     * Acquisitions du projet).
     */
    @Transactional
    public void lierImmobilisation(Long immobilisationId, CompteOHADA compte) {
        // Rien à rattacher automatiquement.
    }

    /**
     * Retour de fonds validé : la part rendue (HT, en USD) de chaque ligne de projet diminue l'achat, et sa quantité
     * diminue au prorata de l'achat d'origine.
     */
    @Transactional
    public void apresRetourFondsInterne(NoteFrais note, Map<Long, BigDecimal> htRenduParLigne, String auteur) {
        for (LigneNoteFrais l : note.getLignes()) {
            BigDecimal rendu = htRenduParLigne.get(l.getId());
            if (rendu == null || rendu.signum() <= 0 || l.getCompteImputation() == null
                || !ComptesProjetService.estCompteProjet(l.getCompteImputation().getLibelle())) {
                continue;
            }
            List<Map<String, Object>> achat = jdbc.queryForList(
                "select article_id, quantite, montant_usd from projet_mouvements where ligne_note_id = ? and type = 'ACHAT'", l.getId());
            if (achat.isEmpty()) continue;
            Long articleId = ((Number) achat.get(0).get("article_id")).longValue();
            BigDecimal qte = (BigDecimal) achat.get(0).get("quantite");
            BigDecimal montant = (BigDecimal) achat.get(0).get("montant_usd");
            BigDecimal qteRendue = montant.signum() > 0
                ? qte.multiply(rendu).divide(montant, 3, RoundingMode.HALF_UP).min(qte) : BigDecimal.ZERO;
            jdbc.update("""
                insert into projet_mouvements (article_id, type, quantite, montant_usd, note_id, ligne_note_id, commentaire, auteur)
                values (?, 'RETOUR_FONDS', ?, ?, ?, ?, ?, ?)
                """, articleId, qteRendue.negate(), rendu.setScale(2, RoundingMode.HALF_UP).negate(), note.getId(), l.getId(),
                "Retour de fonds sur " + note.getReference(), auteur);
        }
    }

    /**
     * Rattrapage : les notes payées avant la mise en place (ou par un autre canal) dont une ligne « Projet … » n'est pas
     * encore entrée. Idempotent. Renvoie le nombre de lignes entrées.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'COMPTABLE', 'DFIN')")
    @Transactional
    public int synchroniser() {
        List<Long> ids = jdbc.queryForList("""
            select distinct n.id from notes_frais n join lignes_note_frais l on l.note_id = n.id
            join comptes_ohada c on c.id = l.compte_imputation_id
            where n.statut = 'PAYEE' and lower(trim(c.libelle)) like 'projet%'
              and not exists (select 1 from projet_mouvements m where m.ligne_note_id = l.id and m.type = 'ACHAT')
            """, Long.class);
        int avant = compterAchats();
        for (Long id : ids) {
            NoteFrais note = notes.findById(id).orElse(null);
            if (note == null) continue;
            BigDecimal taux = tauxDuPaiement(note.getId());
            for (LigneNoteFrais l : note.getLignes()) {
                entrerAchat(note, l, taux, "Synchronisation");
            }
        }
        return compterAchats() - avant;
    }

    private void entrerAchat(NoteFrais note, LigneNoteFrais l, BigDecimal taux, String auteur) {
        CompteOHADA compte = l.getCompteImputation();
        if (compte == null || l.isAchatMarchandise() || !ComptesProjetService.estCompteProjet(compte.getLibelle())) {
            return;
        }
        // Stock commun des projets (V169) : un article sans projet par compte ; le logisticien l'affecte ensuite.
        Long articleId = jdbc.query("select id from projet_articles where compte_id = ? and projet_id is null",
                (rs, i) -> rs.getLong(1), compte.getId())
            .stream().findFirst()
            .orElseGet(() -> jdbc.queryForObject("""
                insert into projet_articles (projet_id, designation, unite, nature, compte_id)
                values (null, ?, (select unite from projet_articles where compte_id = ? and unite is not null order by id desc limit 1),
                        ?, ?) returning id
                """, Long.class, designation(compte.getLibelle()), compte.getId(),
                compte.getNumero().matches("2[1-4].*|6058.*") ? "ACQUISITION" : "ACHAT", compte.getId()));
        Devise devise = note.getDevise() == null ? Devise.CDF : note.getDevise();
        BigDecimal montant = conversion.enDeviseBase(l.montantHtTotal(), devise, taux).montantBase().setScale(2, RoundingMode.HALF_UP);
        BigDecimal quantite = l.getQuantiteProjet() != null && l.getQuantiteProjet().signum() > 0 ? l.getQuantiteProjet() : BigDecimal.ONE;
        int n = jdbc.update("""
            insert into projet_mouvements (article_id, date_mouvement, type, quantite, montant_usd, note_id, ligne_note_id, commentaire, auteur)
            values (?, ?, 'ACHAT', ?, ?, ?, ?, ?, ?)
            on conflict (ligne_note_id) where type = 'ACHAT' do nothing
            """, articleId, LocalDate.now(com.mbsc.finapp.config.FuseauEntreprise.ZONE), quantite, montant, note.getId(), l.getId(),
            "Achat payé — " + note.getReference(), auteur);
        if (n > 0) {
            log.info("Stock projet : achat {} ({} USD, qté {}) entré sur l'article {}", note.getReference(), montant, quantite, articleId);
        }
    }

    /** « Projet — Riz 25 kg » → « Riz 25 kg ». */
    static String designation(String libelle) {
        String d = libelle.trim().replaceFirst("(?i)^projet\\s*[—–-]?\\s*", "").trim();
        return d.isEmpty() ? libelle.trim() : d;
    }

    private BigDecimal tauxDuPaiement(Long noteId) {
        return jdbc.query("""
            select taux_journalier from (
              select taux_journalier from transactions_caisse where note_id = ? and sens = 'DECAISSEMENT'
              union all select taux_journalier from transactions_bancaires where note_id = ? and sens = 'DECAISSEMENT'
              union all select taux_journalier from transactions_mobile_money where note_id = ? and sens = 'DECAISSEMENT') t
            where taux_journalier is not null limit 1
            """, (rs, i) -> rs.getBigDecimal(1), noteId, noteId, noteId).stream().findFirst()
            .orElseGet(conversion::tauxCourant);
    }

    private int compterAchats() {
        Integer n = jdbc.queryForObject("select count(*) from projet_mouvements where type = 'ACHAT'", Integer.class);
        return n == null ? 0 : n;
    }
}
