package com.mbsc.finapp.service;

import com.mbsc.finapp.exception.TransitionInvalideException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.mbsc.finapp.service.instance.InstanceService;

/** La tâche planifiée ne laisse jamais une erreur remonter, et fait signaler au responsable l'échec d'un retrait automatique. */
class PlatsPerimesPlanificateurTest {

    private PlatsPerimesAutomatiqueService service;
    private PlatsPerimesPlanificateur planificateur;

    @BeforeEach
    void preparer() {
        service = mock(PlatsPerimesAutomatiqueService.class);
        planificateur = new PlatsPerimesPlanificateur(service, mock(InstanceService.class));
    }

    @Test
    void chaqueVerificationTraiteLeJourMeme() {
        planificateur.verifier();

        verify(service).traiter(any(LocalDate.class));
        verify(service, never()).signalerEchec(any(), anyString());
    }

    @Test
    void unEchecDuRetraitEstSignaleAvecLeMessageDeLaRegleMetier() {
        when(service.traiter(any())).thenThrow(new TransitionInvalideException("La période comptable est clôturée."));

        assertThatCode(planificateur::verifier).doesNotThrowAnyException();

        verify(service).signalerEchec(any(LocalDate.class), eq("La période comptable est clôturée."));
    }

    @Test
    void uneErreurTechniqueEstSignaleeSansSesDetails() {
        when(service.traiter(any())).thenThrow(new RuntimeException("could not execute statement [ERROR: deadlock detected]"));

        planificateur.verifier();

        verify(service).signalerEchec(any(LocalDate.class), eq("erreur technique, voir les journaux du serveur"));
    }

    @Test
    void siLAlerteElleMemeEchoueLaTacheNeLaisseRienRemonter() {
        when(service.traiter(any())).thenThrow(new IllegalStateException("boum"));
        when(service.signalerEchec(any(), anyString())).thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(planificateur::verifier).doesNotThrowAnyException();
    }

    @Test
    void laCauseLisibleEstTronqueeEtNeDivulguePasLesErreursTechniques() {
        assertThat(PlatsPerimesPlanificateur.cause(new IllegalArgumentException("x".repeat(500)))).hasSize(200).endsWith("…");
        assertThat(PlatsPerimesPlanificateur.cause(new IllegalStateException("  "))).isEqualTo("erreur technique, voir les journaux du serveur");
        assertThat(PlatsPerimesPlanificateur.cause(new NullPointerException("secret interne"))).doesNotContain("secret");
    }
}
