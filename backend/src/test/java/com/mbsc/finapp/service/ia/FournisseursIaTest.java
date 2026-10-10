package com.mbsc.finapp.service.ia;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Classement des erreurs des fournisseurs, réglage de l'effort de raisonnement et filtre des modèles. */
class FournisseursIaTest {

    private static JsonValue corps(String type, String message) {
        return JsonValue.from(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }

    @Test
    void anthropicCreditEpuiseEnHttp400() {
        BadRequestException e = BadRequestException.builder().headers(Headers.builder().build())
            .body(corps("invalid_request_error", "Your credit balance is too low to access the Anthropic API.")).build();
        assertThat(AnthropicFournisseur.classer(e).genre()).isEqualTo(ErreurIa.Genre.CREDIT_EPUISE);
    }

    @Test
    void anthropicCleRefusee() {
        UnauthorizedException e = UnauthorizedException.builder().headers(Headers.builder().build())
            .body(corps("authentication_error", "invalid x-api-key")).build();
        assertThat(AnthropicFournisseur.classer(e).genre()).isEqualTo(ErreurIa.Genre.CLE_REFUSEE);
    }

    @Test
    void anthropicLimiteDeDebitEstTemporaire() {
        RateLimitException e = RateLimitException.builder().headers(Headers.builder().build())
            .body(corps("rate_limit_error", "slow down")).build();
        assertThat(AnthropicFournisseur.classer(e).genre()).isEqualTo(ErreurIa.Genre.TEMPORAIRE);
    }

    @Test
    void erreurReseauEstTemporaire() {
        assertThat(AnthropicFournisseur.classer(new RuntimeException("connexion refusée")).genre())
            .isEqualTo(ErreurIa.Genre.TEMPORAIRE);
    }

    @Test
    void familleDeModelesOpenAi() {
        assertThat(OpenAiFournisseur.serieO("o4-mini")).isTrue();
        assertThat(OpenAiFournisseur.serieO("gpt-5")).isFalse();
        assertThat(OpenAiFournisseur.gpt5("gpt-5-mini")).isTrue();
        assertThat(OpenAiFournisseur.serieO("gpt-4o")).isFalse();
    }

    @Test
    void seulsLesModelesDeConversationSontProposes() {
        for (String ok : new String[] {"o4-mini", "gpt-5", "gpt-4o", "gpt-4.1-mini", "o3", "chatgpt-4o-latest"}) {
            assertThat(OpenAiFournisseur.modeleDeTexte(ok)).as(ok).isTrue();
        }
        for (String ko : new String[] {"text-embedding-3-small", "gpt-4o-audio-preview", "gpt-image-1", "whisper-1",
            "tts-1", "omni-moderation-latest", "gpt-4o-realtime-preview", "dall-e-3", "gpt-4o-transcribe"}) {
            assertThat(OpenAiFournisseur.modeleDeTexte(ko)).as(ko).isFalse();
        }
    }

    @Test
    void openaiPlafonneLesJetonsDeReponseSelonLeModele() {
        assertThat(OpenAiFournisseur.plafondReponse("gpt-4o-mini", 32000)).isEqualTo(16_384);
        assertThat(OpenAiFournisseur.plafondReponse("gpt-4o-2024-08-06", 32000)).isEqualTo(16_384);
        assertThat(OpenAiFournisseur.plafondReponse("gpt-4.1-mini", 40000)).isEqualTo(32_768);
        assertThat(OpenAiFournisseur.plafondReponse("gpt-4o-mini", 6000)).isEqualTo(6000);
        assertThat(OpenAiFournisseur.plafondReponse("o4-mini", 36000)).isEqualTo(36000);
        assertThat(OpenAiFournisseur.plafondReponse("gpt-5", 32000)).isEqualTo(32000);
        assertThat(OpenAiFournisseur.plafondReponse(null, 500)).isEqualTo(500);
    }
}
