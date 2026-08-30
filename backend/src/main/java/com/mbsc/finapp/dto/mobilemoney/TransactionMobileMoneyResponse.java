package com.mbsc.finapp.dto.mobilemoney;

import com.mbsc.finapp.domain.TransactionMobileMoney;
import com.mbsc.finapp.domain.enums.SensTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue d'une transaction mobile money enregistree.
 */
public record TransactionMobileMoneyResponse(
    Long id,
    UUID uuid,
    String reference,
    BigDecimal montant,
    SensTransaction sens,
    String libelle,
    String numeroRecu,
    String operateurNom,
    Long noteFraisId,
    String noteFraisReference,
    Instant dateOperation,
    Instant dateEnregistrement,
    BigDecimal tauxJournalier,
    String etablissementNom
) {
    public static TransactionMobileMoneyResponse from(TransactionMobileMoney t) {
        var operateur = t.getOperateur();
        String nom = operateur == null ? null
            : ((operateur.getPrenom() == null ? "" : operateur.getPrenom()) + " "
             + (operateur.getNom() == null ? "" : operateur.getNom())).trim();
        var note = t.getNoteFrais();
        return new TransactionMobileMoneyResponse(
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
            t.getTauxJournalier(),
            t.getEtablissement() == null ? null : t.getEtablissement().getNom()
        );
    }
}
