package com.mbsc.finapp.security;

import com.mbsc.finapp.dto.admin.UserCreateRequest;
import com.mbsc.finapp.dto.auth.LoginRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identifiant de connexion : adresse e-mail OU nom d'utilisateur. Le formulaire « Nouvel utilisateur »
 * refusait « dfin_bruno » (« doit être une adresse électronique syntaxiquement correcte ») ; la connexion
 * n'acceptait que des adresses.
 */
class IdentifiantConnexionTest {

    private static ValidatorFactory fabrique;
    private static Validator validateur;

    @BeforeAll
    static void ouvrir() {
        fabrique = Validation.buildDefaultValidatorFactory();
        validateur = fabrique.getValidator();
    }

    @AfterAll
    static void fermer() {
        fabrique.close();
    }

    private static UserCreateRequest creation(String identifiant) {
        return new UserCreateRequest("Kalunga", "Bruno", identifiant, "motdepasse-ok", "094589334", null,
            "Direction Financière", "Lubumbashi", List.of("DFIN"));
    }

    private static Set<String> erreursCreation(String identifiant) {
        return validateur.validate(creation(identifiant)).stream()
            .map(v -> v.getPropertyPath() + " : " + v.getMessage())
            .collect(Collectors.toSet());
    }

    private static Set<String> erreursConnexion(String identifiant) {
        return validateur.validate(new LoginRequest(identifiant, "x")).stream()
            .map(v -> v.getPropertyPath() + " : " + v.getMessage())
            .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------------
    // Création d'un compte
    // ---------------------------------------------------------------------

    @Test
    void un_nom_d_utilisateur_est_accepte_a_la_creation() {
        assertThat(erreursCreation("dfin_bruno")).isEmpty();
        assertThat(erreursCreation("bruno.kalunga")).isEmpty();
        assertThat(erreursCreation("b-kalunga+dfin")).isEmpty();
        assertThat(erreursCreation("Frédéric_01")).isEmpty();
    }

    @Test
    void une_adresse_e_mail_reste_acceptee_a_la_creation() {
        assertThat(erreursCreation("dfin@mbsc.cd")).isEmpty();
        assertThat(erreursCreation("bruno.kalunga@mbsc.cd")).isEmpty();
    }

    @Test
    void espace_balisage_et_longueur_hors_limites_sont_refuses_a_la_creation() {
        for (String refuse : List.of("dfin bruno", " dfin_bruno", "dfin_bruno ", "ab", "", "   ", "a<b>c", "x\ny", "a\"b", "a/b", "a'b",
            "a".repeat(151))) {
            assertThat(erreursCreation(refuse)).as("« %s »", refuse.replace("\n", "\\n")).isNotEmpty();
        }
        assertThat(erreursCreation("a".repeat(150))).isEmpty();
        assertThat(erreursCreation("abc")).isEmpty();
    }

    @Test
    void le_message_de_refus_dit_ce_qui_est_attendu_et_ne_parle_plus_d_adresse_electronique() {
        assertThat(erreursCreation("dfin bruno"))
            .containsExactly("email : " + IdentifiantConnexion.MESSAGE_CREATION);
        assertThat(IdentifiantConnexion.MESSAGE_CREATION).doesNotContain("adresse électronique");
    }

    @Test
    void le_mail_de_notification_reste_une_adresse() {
        UserCreateRequest req = new UserCreateRequest("Kalunga", "Bruno", "dfin_bruno", "motdepasse-ok", null,
            "pas-une-adresse", null, null, List.of("DFIN"));
        assertThat(validateur.validate(req)).extracting(v -> v.getPropertyPath().toString()).containsExactly("emailNotification");
    }

    // ---------------------------------------------------------------------
    // Connexion
    // ---------------------------------------------------------------------

    @Test
    void la_connexion_accepte_un_nom_d_utilisateur_et_toute_adresse_existante() {
        assertThat(erreursConnexion("dfin_bruno")).isEmpty();
        assertThat(erreursConnexion("Dfin_Bruno")).isEmpty();
        assertThat(erreursConnexion("admin@mbsc.cd")).isEmpty();
        // Adresses valides au sens de @Email mais hors du jeu de caractères de la création : elles doivent continuer de se connecter.
        assertThat(erreursConnexion("o'brien@exemple.cd")).isEmpty();
        assertThat(erreursConnexion("a!b#c$d%e@exemple.cd")).isEmpty();
        assertThat(erreursConnexion("admin@localhost")).isEmpty();
    }

    @Test
    void la_connexion_refuse_vide_espace_controle_et_caracteres_invisibles() {
        // La route est publique : sans cette garde, un saut de ligne pourrait falsifier les journaux.
        for (String refuse : List.of("", "   ", "dfin bruno", "dfin_bruno ", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u0085b",
            "a\u2028b", "a\u200bb", "a\u202eb")) {
            assertThat(erreursConnexion(refuse)).as("« %s »", refuse.replace("\n", "\\n").replace("\r", "\\r")).isNotEmpty();
        }
        assertThat(erreursConnexion("a".repeat(191))).isNotEmpty();
        assertThat(erreursConnexion("a".repeat(190))).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Normalisation et adresse de contact
    // ---------------------------------------------------------------------

    @Test
    void l_identifiant_est_enregistre_en_minuscules_sans_espaces_autour() {
        assertThat(IdentifiantConnexion.normaliser("  Dfin_Bruno ")).isEqualTo("dfin_bruno");
        assertThat(IdentifiantConnexion.normaliser("Bruno.Kalunga@MBSC.CD")).isEqualTo("bruno.kalunga@mbsc.cd");
        assertThat(IdentifiantConnexion.normaliser(null)).isNull();
    }

    @Test
    void seule_une_adresse_sert_de_destinataire() {
        assertThat(IdentifiantConnexion.estUneAdresse("dfin@mbsc.cd")).isTrue();
        assertThat(IdentifiantConnexion.estUneAdresse(" dfin@mbsc.cd ")).isTrue();
        assertThat(IdentifiantConnexion.estUneAdresse("admin@localhost")).isTrue();
        assertThat(IdentifiantConnexion.estUneAdresse("dfin_bruno")).isFalse();
        assertThat(IdentifiantConnexion.estUneAdresse("@mbsc.cd")).isFalse();
        assertThat(IdentifiantConnexion.estUneAdresse("dfin@")).isFalse();
        assertThat(IdentifiantConnexion.estUneAdresse("a@b@c")).isFalse();
        assertThat(IdentifiantConnexion.estUneAdresse("")).isFalse();
        assertThat(IdentifiantConnexion.estUneAdresse(null)).isFalse();
    }

    @Test
    void l_adresse_de_contact_est_l_identifiant_s_il_en_est_une_sinon_le_mail_de_notification() {
        assertThat(IdentifiantConnexion.adresseDeContact("dfin@mbsc.cd", "autre@exemple.cd")).isEqualTo("dfin@mbsc.cd");
        assertThat(IdentifiantConnexion.adresseDeContact("dfin@mbsc.cd", null)).isEqualTo("dfin@mbsc.cd");
        assertThat(IdentifiantConnexion.adresseDeContact("dfin_bruno", " bruno@exemple.cd ")).isEqualTo("bruno@exemple.cd");
        assertThat(IdentifiantConnexion.adresseDeContact("dfin_bruno", null)).isNull();
        assertThat(IdentifiantConnexion.adresseDeContact("dfin_bruno", "  ")).isNull();
    }
}
