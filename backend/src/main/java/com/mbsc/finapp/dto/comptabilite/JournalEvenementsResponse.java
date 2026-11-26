package com.mbsc.finapp.dto.comptabilite;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Journal des événements comptables (audit SYSCOHADA, C-09) : dernières lignes et état de la chaîne d'empreintes
 * ({@code premiereLigneAlteree} null = chaîne intacte).
 */
public record JournalEvenementsResponse(boolean chaineIntacte, Long premiereLigneAlteree, long total,
                                        List<Evenement> evenements) {
    public record Evenement(Long id, OffsetDateTime horodatage, String auteur, String action, String objetType,
                            Long objetId, String objetReference, String avant, String apres, String motif,
                            String empreinte) {
    }
}
