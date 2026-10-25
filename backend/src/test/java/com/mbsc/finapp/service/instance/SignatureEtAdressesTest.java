package com.mbsc.finapp.service.instance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cle d'appairage, signature des appels entre serveurs et adresse du serveur en ligne (V132).
 */
class SignatureEtAdressesTest {

    private static final byte[] CORPS = "{\"changements\":[]}".getBytes(StandardCharsets.UTF_8);

    @Test
    void une_cle_generee_se_relit_a_l_identique_meme_avec_des_espaces_de_copier_coller() {
        SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
        String saisie = "  " + cle.texte().substring(0, 20) + "\n" + cle.texte().substring(20) + " ";

        SignatureReplication.Cle lue = SignatureReplication.lire(saisie);

        assertThat(cle.texte()).startsWith("MBSC1.");
        assertThat(lue).isEqualTo(cle);
        assertThat(cle.fin()).hasSize(6);
        assertThat(SignatureReplication.nouvelleCle().secret()).isNotEqualTo(cle.secret());
    }

    @Test
    void une_cle_mal_recopiee_est_refusee_avec_une_explication() {
        for (String mauvaise : new String[] {"", "MBSC1.abc", "MBSC2.3f1e4c1a-1111-2222-3333-444455556666.abcdefghijklmnopqrstuvwxyzabcdefghijklmnop"}) {
            assertThatThrownBy(() -> SignatureReplication.lire(mauvaise))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MBSC1.");
        }
    }

    @Test
    void la_signature_couvre_l_appairage_l_horodatage_le_nonce_la_methode_le_chemin_et_le_corps() {
        SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
        String sig = SignatureReplication.signer(cle.secret(), cle.appairageId(), 1000L, "n1", "POST", "/replication/lot", CORPS);

        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "POST", "/replication/lot", CORPS, sig)).isTrue();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "post", "/replication/lot", CORPS, sig))
            .as("methode insensible a la casse").isTrue();
        assertThat(SignatureReplication.verifier("autre-secret", cle.appairageId(), 1000L, "n1", "POST", "/replication/lot", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), "autre", 1000L, "n1", "POST", "/replication/lot", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1001L, "n1", "POST", "/replication/lot", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n2", "POST", "/replication/lot", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "GET", "/replication/lot", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "POST", "/replication/audit", CORPS, sig)).isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "POST", "/replication/lot",
            "{\"changements\":[1]}".getBytes(StandardCharsets.UTF_8), sig)).as("corps modifie").isFalse();
        assertThat(SignatureReplication.verifier(cle.secret(), cle.appairageId(), 1000L, "n1", "POST", "/replication/lot", CORPS, null)).isFalse();
    }

    @Test
    void l_adresse_saisie_est_normalisee() {
        assertThat(ReplicationClient.normaliserAdresse("192.0.2.10:9090")).isEqualTo("http://192.0.2.10:9090");
        assertThat(ReplicationClient.normaliserAdresse("192.0.2.10")).isEqualTo("http://192.0.2.10");
        assertThat(ReplicationClient.normaliserAdresse("erp.exemple.com")).isEqualTo("https://erp.exemple.com");
        assertThat(ReplicationClient.normaliserAdresse(" https://erp.exemple.com/api/ ")).isEqualTo("https://erp.exemple.com");
        assertThat(ReplicationClient.normaliserAdresse("http://serveur-test:9090/")).isEqualTo("http://serveur-test:9090");
        assertThatThrownBy(() -> ReplicationClient.normaliserAdresse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplicationClient.normaliserAdresse("http://")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void les_empreintes_differentes_designent_les_tables_a_recopier() {
        assertThat(EmpreintesService.differences(Map.of("users", "3:a", "notes_frais", "2:b"), Map.of("users", "3:a", "notes_frais", "2:c")))
            .containsExactly("notes_frais");
        assertThat(EmpreintesService.differences(Map.of("users", "3:a"), Map.of("users", "3:a", "ventes", "0:")))
            .containsExactly("ventes");
        assertThat(EmpreintesService.differences(Map.of("users", "3:a"), Map.of("users", "3:a"))).isEmpty();
    }

    @Test
    void les_colonnes_que_la_copie_ecrit_elle_meme_sont_fusionnees() {
        assertThat(ReplicationApplicateur.fusion("users", "version_jetons"))
            .startsWith("cible.version_jetons + ").contains("cible.mot_de_passe is distinct from excluded.mot_de_passe")
            .doesNotContain("excluded.version_jetons");
        assertThat(ReplicationApplicateur.fusion("users", "actif"))
            .contains("ACTIVER_UTILISATEUR").contains("EN_ATTENTE").contains("excluded.actif");
        assertThat(ReplicationApplicateur.fusion("notifications", "lue")).isEqualTo("cible.lue or excluded.lue");
        assertThat(ReplicationApplicateur.fusion("notes_frais", "objet")).isEqualTo("excluded.\"objet\"");
        assertThat(List.copyOf(ReplicationSchema.NON_RECOPIEES))
            .contains("parametres_instance", "journal_replication", "actions_distantes", "parametres_ia", "parametres_mail",
                "parametres_emcf", "flyway_schema_history");
    }
}
