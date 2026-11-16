package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.LigneRetourFonds;
import com.mbsc.finapp.domain.RetourFondsNote;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.StatutRetourFonds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Une demande de retour de fonds sur une note payee, avec l'ancien et le nouveau de chaque ligne reduite. */
public record RetourFondsResponse(
    Long id,
    Long noteId,
    String noteReference,
    String noteObjet,
    String noteBeneficiaire,
    Devise devise,
    StatutRetourFonds statut,
    String motif,
    /** Montant rendu, TTC, devise de la note. */
    BigDecimal montant,
    String demandeurNom,
    Instant dateDemande,
    String caissierNom,
    Instant dateDecision,
    String motifRefus,
    String transactionReference,
    String numeroRecu,
    List<Ligne> lignes
) {
    public record Ligne(
        Long ligneNoteId,
        String description,
        String articleLibelle,
        String unite,
        BigDecimal ancienMontant,
        BigDecimal nouveauMontant,
        BigDecimal ancienneQuantite,
        BigDecimal nouvelleQuantite,
        BigDecimal montantRetour
    ) {
        static Ligne from(LigneRetourFonds l) {
            LigneNoteFrais ln = l.getLigneNote();
            var article = ln == null ? null : ln.getArticle();
            return new Ligne(ln == null ? null : ln.getId(), ln == null ? null : ln.getDescription(),
                article == null ? null : article.getLibelle(), article == null ? null : article.getUniteMesure(),
                l.getAncienMontant(), l.getNouveauMontant(), l.getAncienneQuantite(), l.getNouvelleQuantite(), l.getMontantRetour());
        }
    }

    public static RetourFondsResponse from(RetourFondsNote r) {
        var note = r.getNote();
        var t = r.getTransaction();
        return new RetourFondsResponse(
            r.getId(),
            note.getId(),
            note.getReference(),
            note.getObjet(),
            note.getBeneficiaire(),
            note.getDevise(),
            r.getStatut(),
            r.getMotif(),
            r.getMontant(),
            nom(r.getDemandeur()),
            r.getDateDemande(),
            nom(r.getCaissier()),
            r.getDateDecision(),
            r.getMotifRefus(),
            t == null ? null : t.getReference(),
            t == null ? null : t.getNumeroRecu(),
            r.getLignes().stream().map(Ligne::from).toList()
        );
    }

    static String nom(User u) {
        if (u == null) {
            return null;
        }
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }
}
