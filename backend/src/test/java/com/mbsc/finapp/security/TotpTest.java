package com.mbsc.finapp.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Codes à usage unique : vecteurs de test de la RFC 6238 (HMAC-SHA1), ramenés à 6 chiffres. */
class TotpTest {

    private static final String SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void le_secret_de_la_rfc_s_encode_en_base_32() {
        assertThat(SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.decoderBase32(SECRET)).isEqualTo("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void les_codes_correspondent_aux_vecteurs_de_la_rfc_6238() {
        assertThat(Totp.code(SECRET, Totp.pasCourant(59))).isEqualTo("287082");
        assertThat(Totp.code(SECRET, Totp.pasCourant(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(SECRET, Totp.pasCourant(2000000000))).isEqualTo("279037");
    }

    @Test
    void un_code_est_accepte_un_pas_avant_ou_apres_mais_pas_au_dela() {
        long pas = Totp.pasCourant(1111111109);
        String code = Totp.code(SECRET, pas);
        assertThat(Totp.pasDuCode(SECRET, code, pas)).isEqualTo(pas);
        assertThat(Totp.pasDuCode(SECRET, code, pas + 1)).isEqualTo(pas);
        assertThat(Totp.pasDuCode(SECRET, code, pas - 1)).isEqualTo(pas);
        assertThat(Totp.pasDuCode(SECRET, code, pas + 2)).isEqualTo(-1);
        assertThat(Totp.pasDuCode(SECRET, "12a456", pas)).isEqualTo(-1);
    }

    @Test
    void un_nouveau_secret_fait_32_caracteres_base_32() {
        assertThat(Totp.nouveauSecret()).matches("[A-Z2-7]{32}");
    }
}
