package com.mbsc.finapp.audit;

import com.mbsc.finapp.audit.AuditModele.Compte;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditAnalyseIaServiceTest {

    private static final List<Compte> TERMINAUX = List.of(
        new Compte("Ordinateur", 10, 0), new Compte("Mobile", 3, 0), new Compte("Tablette", 1, 0),
        new Compte("Application", 4, 0), new Compte("Inconnu", 9, 0));
    private static final List<Compte> SYSTEMES = List.of(
        new Compte("Windows", 8, 0), new Compte("Android", 3, 0), new Compte("iOS", 1, 0), new Compte("Inconnu", 9, 0));

    @Test
    void reconnaitLesTerminauxEtSystemesCitesDansLaQuestion() {
        assertEquals(Set.of("Mobile"), AuditAnalyseIaService.citees("qui s'est connecté depuis un mobile ?", TERMINAUX));
        assertEquals(Set.of("Mobile", "Tablette"), AuditAnalyseIaService.citees("les connexions sur mobiles et tablettes", TERMINAUX));
        assertEquals(Set.of("Ordinateur"), AuditAnalyseIaService.citees("actions depuis un ordinateur", TERMINAUX));
        assertEquals(Set.of("Windows", "Android"), AuditAnalyseIaService.citees("windows ou android ?", SYSTEMES));
    }

    @Test
    void neConfondPasUnMotCourantAvecUnTerminalOuUnSysteme() {
        assertEquals(Set.of(), AuditAnalyseIaService.citees("une curiosité sur l'activité", SYSTEMES));          // « ios » dans « curiosité »
        assertEquals(Set.of(), AuditAnalyseIaService.citees("quelles actions dans l'application ?", TERMINAUX));   // « Application » ignoré
        assertEquals(Set.of(), AuditAnalyseIaService.citees("un utilisateur inconnu", TERMINAUX));                  // « Inconnu » ignoré
        assertEquals(Set.of("iOS"), AuditAnalyseIaService.citees("depuis ios", SYSTEMES));
    }
}
