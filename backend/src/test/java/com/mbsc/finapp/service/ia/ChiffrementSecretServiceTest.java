package com.mbsc.finapp.service.ia;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChiffrementSecretServiceTest {

    private static final String CLE = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void allerRetourEtNonceAleatoire() {
        ChiffrementSecretService s = new ChiffrementSecretService(CLE, "jwt");
        String a = s.chiffrer("sk-proj-abcdef123456");
        String b = s.chiffrer("sk-proj-abcdef123456");
        assertThat(a).isNotEqualTo(b).doesNotContain("sk-proj");
        assertThat(s.dechiffrer(a)).isEqualTo("sk-proj-abcdef123456");
        assertThat(s.dechiffrer(b)).isEqualTo("sk-proj-abcdef123456");
    }

    @Test
    void uneAutreCleOuUneValeurAlterreeDonneNullSansException() {
        String chiffre = new ChiffrementSecretService(CLE, "jwt").chiffrer("secret");
        ChiffrementSecretService autre = new ChiffrementSecretService(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}), "jwt");
        assertThat(autre.dechiffrer(chiffre)).isNull();
        assertThat(new ChiffrementSecretService(CLE, "jwt").dechiffrer("pas-du-base64-valide-!!")).isNull();
        assertThat(new ChiffrementSecretService(CLE, "jwt").dechiffrer(null)).isNull();
    }

    @Test
    void sansCleDediee_laCleDeriveeDuSecretJwtFonctionne() {
        ChiffrementSecretService s = new ChiffrementSecretService("", "mon-secret-jwt");
        assertThat(s.dechiffrer(s.chiffrer("x"))).isEqualTo("x");
        assertThat(new ChiffrementSecretService("", "autre-secret").dechiffrer(s.chiffrer("x"))).isNull();
    }

    @Test
    void cleDeMauvaiseTailleRefusee() {
        assertThatThrownBy(() -> new ChiffrementSecretService(Base64.getEncoder().encodeToString(new byte[16]), "jwt"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 octets");
    }
}
