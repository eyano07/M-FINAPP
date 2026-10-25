package com.mbsc.finapp.dto.vente;

import com.mbsc.finapp.domain.LigneRetourVente;
import com.mbsc.finapp.domain.RetourVente;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.CanalRemboursement;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeReglement;
import com.mbsc.finapp.domain.enums.StatutRetourVente;
import com.mbsc.finapp.domain.enums.TypeArticle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un retour de vente payee et son etat (V134).
 *
 * @param canalPropose canal du remboursement si le caissier n'en choisit pas : CREANCE pour une vente a credit pas encore
 *                     reglee, sinon celui du reglement de la vente (CAISSE pour une creance deja encaissee)
 */
public record RetourVenteResponse(
    Long id,
    String reference,
    Long venteId,
    String venteReference,
    String client,
    Devise devise,
    ModeReglement venteModeReglement,
    StatutRetourVente statut,
    String motif,
    String demandeur,
    Instant dateDemande,
    String approbateur,
    Instant dateDecision,
    String commentaireDecision,
    String caissier,
    LocalDate dateRemboursement,
    CanalRemboursement canal,
    String etablissement,
    CanalRemboursement canalPropose,
    BigDecimal montantHt,
    BigDecimal montantTva,
    BigDecimal montantTtc,
    String pieceReference,
    List<Ligne> lignes
) {
    public record Ligne(
        Long ligneVenteId,
        String designation,
        TypeArticle type,
        BigDecimal quantite,
        BigDecimal prixUnitaire,
        boolean remiseEnStock,
        BigDecimal montantHt,
        BigDecimal montantTva,
        BigDecimal montantTtc,
        String mouvementReference
    ) {
        static Ligne from(LigneRetourVente l) {
            var lv = l.getLigneVente();
            return new Ligne(lv.getId(), lv.getDesignation(), lv.getArticle() == null ? null : lv.getArticle().getType(),
                l.getQuantite(), lv.getPrixUnitaire(), l.isRemiseEnStock(), l.getMontantHt(), l.getMontantTva(),
                l.getMontantHt().add(l.getMontantTva()),
                l.getMouvementStock() == null ? null : l.getMouvementStock().getReference());
        }
    }

    public static RetourVenteResponse from(RetourVente r) {
        Vente v = r.getVente();
        return new RetourVenteResponse(
            r.getId(), r.getReference(), v.getId(), v.getReference(), v.designationClient(), v.getDevise(), v.getModeReglement(),
            r.getStatut(), r.getMotif(), nom(r.getDemandeur()), r.getDateDemande(), nom(r.getApprobateur()), r.getDateDecision(),
            r.getCommentaireDecision(), nom(r.getCaissier()), r.getDateRemboursement(), r.getCanal(),
            r.getEtablissement() == null ? null : r.getEtablissement().getNom(),
            canalPropose(v),
            r.getMontantHt(), r.getMontantTva(), r.getMontantTtc(),
            r.getPiece() == null ? null : r.getPiece().getReference(),
            r.getLignes().stream().map(Ligne::from).toList());
    }

    /** Canal d'un remboursement par defaut : CREANCE si la vente a credit n'est pas encore reglee, sinon celui du reglement. */
    public static CanalRemboursement canalPropose(Vente v) {
        return switch (v.getModeReglement()) {
            case CREDIT -> v.getPieceReglement() == null ? CanalRemboursement.CREANCE : CanalRemboursement.CAISSE;
            case CAISSE -> CanalRemboursement.CAISSE;
            case BANQUE -> CanalRemboursement.BANQUE;
            case MOBILE_MONEY -> CanalRemboursement.MOBILE_MONEY;
        };
    }

    private static String nom(User u) {
        if (u == null) {
            return null;
        }
        String complet = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return complet.isEmpty() ? u.getEmail() : complet;
    }
}
