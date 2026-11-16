package com.mbsc.finapp.service;

import org.junit.jupiter.api.Test;

import static com.mbsc.finapp.service.GardesParRole.autorise;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demande du 15/11/2026 : le Grand Livre et la Balance (/caisse/grand-livre, /caisse/balance) sont fermés au caissier ;
 * un compte qui cumule CAISSIER et un rôle comptable les garde. Le caissier lit toujours les soldes de trésorerie
 * (/caisse/soldes-tresorerie) dont ont besoin l'écran Caisse et son tableau de bord.
 */
class GrandLivreBalanceCaissierTest {

    @Test
    void leCaissierNeLitNiLeGrandLivreNiLaBalance() {
        assertFalse(autorise(CaisseService.class, "grandLivre", "CAISSIER"));
        assertFalse(autorise(CaisseService.class, "balance", "CAISSIER"));
    }

    @Test
    void lesRolesComptablesEtLaDirectionLesGardent() {
        for (String role : new String[] {"COMPTABLE", "DFIN", "DA", "DG", "ADMIN"}) {
            assertTrue(autorise(CaisseService.class, "grandLivre", role), role);
            assertTrue(autorise(CaisseService.class, "balance", role), role);
        }
    }

    @Test
    void unCompteQuiCumuleCaissierEtUnRoleComptableGardeLAcces() {
        for (String role : new String[] {"COMPTABLE", "DFIN", "ADMIN"}) {
            assertTrue(autorise(CaisseService.class, "grandLivre", "CAISSIER", role), "CAISSIER + " + role);
            assertTrue(autorise(CaisseService.class, "balance", "CAISSIER", role), "CAISSIER + " + role);
        }
    }

    @Test
    void leCaissierLitLesSoldesDeTresorerie() {
        assertTrue(autorise(CaisseService.class, "soldesTresorerie", "CAISSIER"));
        assertTrue(autorise(CaisseService.class, "soldesTresorerie", "DFIN"));
        assertFalse(autorise(CaisseService.class, "soldesTresorerie", "LOGISTIQUE"));
    }
}
