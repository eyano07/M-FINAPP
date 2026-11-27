package com.mbsc.finapp.service.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushServiceTest {

    @Test
    void seules_les_notifications_des_notes_de_frais_et_des_budgets_vont_au_telephone() {
        assertThat(PushService.concerneLeTelephone("/notes-frais/42")).isTrue();
        assertThat(PushService.concerneLeTelephone("/notes-frais")).isTrue();
        assertThat(PushService.concerneLeTelephone("/budgets/3")).isTrue();
        assertThat(PushService.concerneLeTelephone("/budgets")).isTrue();
        assertThat(PushService.concerneLeTelephone("/budgets/projets")).isFalse();
        assertThat(PushService.concerneLeTelephone("/budgets/services")).isFalse();
        assertThat(PushService.concerneLeTelephone("/budgetsX")).isFalse();
        assertThat(PushService.concerneLeTelephone("/restaurant/stock")).isFalse();
        assertThat(PushService.concerneLeTelephone("/notes-fraisX")).isFalse();
        assertThat(PushService.concerneLeTelephone(null)).isFalse();
    }

    @Test
    void un_jeton_inconnu_de_firebase_est_retire() {
        assertThat(PushService.jetonPerime(404, "{}")).isTrue();
        assertThat(PushService.jetonPerime(400, "{\"error\":{\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}")).isTrue();
        assertThat(PushService.jetonPerime(400, "{\"error\":{\"message\":\"Invalid JSON payload\"}}")).isFalse();
        assertThat(PushService.jetonPerime(500, "UNREGISTERED")).isFalse();
        assertThat(PushService.jetonPerime(401, "")).isFalse();
    }
}
