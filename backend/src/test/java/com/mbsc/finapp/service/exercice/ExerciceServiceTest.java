package com.mbsc.finapp.service.exercice;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ExerciceServiceTest {

    @Test
    void statuts() {
        LocalDate cloture = LocalDate.of(2025, 12, 31);
        assertThat(ExerciceService.exercice(2025, 2026, cloture).statut()).isEqualTo(ExerciceService.Statut.CLOTURE);
        assertThat(ExerciceService.exercice(2025, 2026, cloture).lectureSeule()).isTrue();
        assertThat(ExerciceService.exercice(2026, 2026, cloture).statut()).isEqualTo(ExerciceService.Statut.EN_COURS);
        assertThat(ExerciceService.exercice(2024, 2026, null).statut()).isEqualTo(ExerciceService.Statut.A_CLOTURER);
        assertThat(ExerciceService.exercice(2027, 2026, cloture).statut()).isEqualTo(ExerciceService.Statut.A_VENIR);
    }

    @Test
    void clotureEnCoursDAnneeNeFermePasLExercice() {
        assertThat(ExerciceService.estCloture(2025, LocalDate.of(2025, 6, 30))).isFalse();
        assertThat(ExerciceService.estCloture(2024, LocalDate.of(2025, 6, 30))).isTrue();
    }
}
