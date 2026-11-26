package com.mbsc.finapp.service.emcf;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Facture (ou avoir) à faire certifier : contenu métier, indépendant du format d'échange du dispositif. */
public record EmcfDemande(
    String type,                 // FV (facture de vente) ou FA (avoir)
    String reference,
    Instant dateHeure,
    String devise,
    Emetteur emetteur,
    Client client,
    String origineUid,
    String modeReglement,
    List<Ligne> lignes,
    List<TotalGroupe> parGroupe,
    BigDecimal totalHt,
    BigDecimal totalTva,
    BigDecimal totalTtc
) {
    public record Emetteur(String nom, String nif, String rccm, String idNat, String adresse) {}

    public record Client(String nom, String nif, String type) {}

    public record Ligne(String designation, BigDecimal quantite, BigDecimal prixUnitaire, String groupe,
                        BigDecimal montantHt, BigDecimal montantTva, BigDecimal montantTtc) {}

    public record TotalGroupe(String groupe, BigDecimal taux, BigDecimal ht, BigDecimal tva, BigDecimal ttc) {}
}
