package com.mbsc.finapp.domain.enums;

/**
 * Roles fonctionnels MBSC.
 */
public enum RoleType {
    ADMIN,
    DG,        // Directeur General
    DA,        // Directeur Administratif
    DFIN,      // Directeur Financier
    DIRECTEUR, // Directeur metier (ancien createur de notes, cf. COMPTABLE)
    CAISSIER,
    COMPTABLE, // Cree les notes de frais et consulte l'ensemble des rapports comptables/ventes/logistique/patrimoine, en lecture seule hors notes de frais
    LOGISTIQUE, // Gestion du stock et du transport
    GEST_PATRIMOINE, // Gestion des biens immobilises et des consommables
    RESP_DRH, // Responsable DRH : personnel, presences, paie, missions
    RESP_RESTAURANT // Responsable restaurant : carte (plats, boissons) et parc d'emballages consignes
}
