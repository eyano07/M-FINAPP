package com.mbsc.finapp.dto.banque;

import com.mbsc.finapp.domain.TransactionBancaire;
import com.mbsc.finapp.domain.enums.SensTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Vue d'une transaction bancaire enregistree.
 */
public record TransactionBancaireResponse(
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
    public static TransactionBancaireResponse from(TransactionBancaire t) {
        var operateur = t.getOperateur();
        String nom = operateur == null ? null
            : ((operateur.getPrenom() == null ? "" : operateur.getPrenom()) + " "
             + (operateur.getNom() == null ? "" : operateur.getNom())).trim();
        var note = t.getNoteFrais();
        return new TransactionBancaireResponse(
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
