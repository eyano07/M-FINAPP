package com.mbsc.finapp.service;

import com.mbsc.finapp.controller.SyncController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Séparation des tâches : l'administrateur configure l'application mais n'encaisse pas, ne décaisse pas et ne vend
 * pas ; les seuils de priorité des notes sont fixés par le DA seul ; seul le comptable annule une comptabilisation, le
 * DFIN et l'administrateur la rétablissent. Ces règles sont portées par {@code @PreAuthorize} : le test les relit
 * pour qu'un changement involontaire fasse échouer le build.
 */
class SeparationDesTachesTest {

    private static String regle(Class<?> classe, String methode) {
        List<String> regles = Arrays.stream(classe.getDeclaredMethods())
            .filter(m -> m.getName().equals(methode))
            .map(m -> m.getAnnotation(PreAuthorize.class))
            .filter(Objects::nonNull)
            .map(PreAuthorize::value)
            .toList();
        assertEquals(1, regles.size(), classe.getSimpleName() + "." + methode + " doit porter une et une seule règle @PreAuthorize");
        return regles.get(0);
    }

    private static void reserveAuCaissier(Class<?> classe, String... methodes) {
        for (String methode : methodes) {
            assertEquals("hasRole('CAISSIER')", regle(classe, methode), classe.getSimpleName() + "." + methode);
        }
    }

    @Test
    void lAdministrateurNEncaissePasNeDecaissePasEtNeVendPas() {
        reserveAuCaissier(CaisseService.class, "enregistrer", "acheterMarchandise", "reglerCamionsMinerai", "payerNote", "encaisserNote");
        reserveAuCaissier(BanqueService.class, "enregistrer", "payerNote");
        reserveAuCaissier(MobileMoneyService.class, "enregistrer", "payerNote");
        reserveAuCaissier(VenteService.class, "creer", "ajouterLigne", "valider", "reglerCreance", "reglerAdditionTable");
        reserveAuCaissier(RetourFondsService.class, "valider", "refuser");   // l'argent d'une note reduite rentre en caisse
        reserveAuCaissier(SyncController.class, "batch");     // synchronisation des opérations du poste de caisse
    }

    /**
     * Hôtellerie (V152) : la réception (et la direction de l'hébergement) encaisse les folios, les acomptes et les cautions ;
     * jamais l'administrateur, et pas le caissier depuis l'hôtellerie (il garde la caisse de M-FINAPP).
     */
    @Test
    void laReceptionEncaisseLesFoliosEtPasLAdministrateur() {
        for (String methode : List.of("encaisserFolio", "reglerFolio", "acompte", "caution", "restituerCaution", "rembourserAcompte",
            "creerClient")) {
            String r = regle(com.mbsc.finapp.service.hotellerie.PmsIntegrationService.class, methode);
            assertEquals("hasAnyRole('RECEPTIONNISTE', 'DIR_HEBERGEMENT')", r, "PmsIntegrationService." + methode);
        }
    }

    @Test
    void lAdministrateurGardeLaConsultationDeLaTresorerie() {
        for (String lecture : List.of("listerTransactions", "journal")) {
            assertEquals(true, regle(CaisseService.class, lecture).contains("ADMIN"), "CaisseService." + lecture);
        }
        assertEquals(true, regle(RapportCaisseService.class, "rapport").contains("ADMIN"), "rapport de caisse : consultation seule");
        assertEquals(true, regle(BanqueService.class, "journal").contains("ADMIN"));
        assertEquals(true, regle(MobileMoneyService.class, "journal").contains("ADMIN"));
        assertEquals(true, regle(VenteService.class, "lister").contains("ADMIN"));
    }

    @Test
    void leRetraitDesPlatsPerimesAutomatiqueOuManuelSeChoisitParLAdministrateurSeul() {
        assertEquals("hasRole('ADMIN')", regle(ParametresRestaurantService.class, "definirRetraitPerimes"));
    }

    @Test
    void lesSeuilsDePrioriteSontReservesAuDa() {
        assertEquals("hasRole('DA')", regle(ParametresPrioriteNoteService.class, "enregistrer"));
        assertFalse(regle(ParametresPrioriteNoteService.class, "consulter").contains("ADMIN"));
    }

    /**
     * Demande du 13/11/2026 : une comptabilisation annulée par le comptable peut être rétablie par le DFIN ou
     * l'administrateur ; personne d'autre que le comptable ne l'annule (ni le DFIN, ni l'administrateur).
     */
    @Test
    void seulLeComptableAnnuleUneComptabilisationEtLeDfinOuLAdministrateurLaRetablit() {
        assertEquals("hasRole('COMPTABLE')", regle(ComptabiliteService.class, "annuler"));
        String restaurer = regle(ComptabiliteService.class, "restaurer");
        for (String role : List.of("COMPTABLE", "DFIN", "ADMIN")) {
            assertTrue(restaurer.contains("'" + role + "'"), "ComptabiliteService.restaurer doit accepter " + role);
        }
        for (String role : List.of("DA", "DG", "CAISSIER")) {
            assertFalse(restaurer.contains("'" + role + "'"), "ComptabiliteService.restaurer ne doit pas accepter " + role);
        }
    }
}
