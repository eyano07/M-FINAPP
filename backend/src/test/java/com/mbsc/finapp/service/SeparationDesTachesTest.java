package com.mbsc.finapp.service;

import com.mbsc.finapp.controller.SyncController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Séparation des tâches : l'administrateur configure l'application mais n'encaisse pas, ne décaisse pas et ne vend
 * pas ; les seuils de priorité des notes sont fixés par le DA seul. Ces règles sont portées par {@code @PreAuthorize} :
 * le test les relit pour qu'un changement involontaire fasse échouer le build.
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
        reserveAuCaissier(SyncController.class, "batch");     // synchronisation des opérations du poste de caisse
    }

    @Test
    void lAdministrateurGardeLaConsultationDeLaTresorerie() {
        for (String lecture : List.of("listerTransactions", "journal")) {
            assertEquals(true, regle(CaisseService.class, lecture).contains("ADMIN"), "CaisseService." + lecture);
        }
        assertEquals(true, regle(BanqueService.class, "journal").contains("ADMIN"));
        assertEquals(true, regle(MobileMoneyService.class, "journal").contains("ADMIN"));
        assertEquals(true, regle(VenteService.class, "lister").contains("ADMIN"));
    }

    @Test
    void lesSeuilsDePrioriteSontReservesAuDa() {
        assertEquals("hasRole('DA')", regle(ParametresPrioriteNoteService.class, "enregistrer"));
        assertFalse(regle(ParametresPrioriteNoteService.class, "consulter").contains("ADMIN"));
    }
}
