package com.mbsc.finapp.dto.caisse;

import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.enums.SensTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue d'une transaction de caisse enregistree.
 */
public record TransactionCaisseResponse(
    Long id,
    UUID uuid,
    String reference,
    BigDecimal montant,
    SensTransaction sens,
    String libelle,
    String numeroRecu,
    String caissierNom,
    Long noteFraisId,
    String noteFraisReference,
    String beneficiaire,
    Instant dateOperation,
    Instant dateEnregistrement,
    BigDecimal tauxJournalier,
    /** Retour de fonds : la note payee que cet encaissement reduit (null sinon). Distinct de noteFraisId, le paiement. */
    Long noteRetourId,
    String noteRetourReference
) {
    public static TransactionCaisseResponse from(TransactionCaisse t) {
        var caissier = t.getCaissier();
        String nom = caissier == null ? null
            : ((caissier.getPrenom() == null ? "" : caissier.getPrenom()) + " "
             + (caissier.getNom() == null ? "" : caissier.getNom())).trim();
        var note = t.getNoteFrais();
        var noteRetour = t.getNoteRetour();
        return new TransactionCaisseResponse(
            t.getId(),
            t.getUuid(),
            t.getReference(),
            t.getMontant(),
            t.getSens(),
            t.getLibelle(),
            t.getNumeroRecu(),
            nom,
            note == null ? null : note.getId(),
            note == null ? null : note.getReference(),
            note == null ? null : note.getBeneficiaire(),
            t.getDateOperation(),
            t.getDateEnregistrement(),
            t.getTauxJournalier(),
            noteRetour == null ? null : noteRetour.getId(),
            noteRetour == null ? null : noteRetour.getReference()
        );
    }
}
