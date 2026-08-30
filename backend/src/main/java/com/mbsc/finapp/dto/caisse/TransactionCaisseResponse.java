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
    Instant dateOperation,
    Instant dateEnregistrement,
    BigDecimal tauxJournalier
) {
    public static TransactionCaisseResponse from(TransactionCaisse t) {
        var caissier = t.getCaissier();
        String nom = caissier == null ? null
            : ((caissier.getPrenom() == null ? "" : caissier.getPrenom()) + " "
             + (caissier.getNom() == null ? "" : caissier.getNom())).trim();
        var note = t.getNoteFrais();
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
            t.getDateOperation(),
            t.getDateEnregistrement(),
            t.getTauxJournalier()
        );
    }
}
