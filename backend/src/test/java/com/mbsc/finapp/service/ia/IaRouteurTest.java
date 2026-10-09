package com.mbsc.finapp.service.ia;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Routage : Claude pour les analyses, OpenAI pour le reste, repli sur OpenAI quand Claude n'est pas utilisable. */
class IaRouteurTest {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private final ParametresIaService parametres = mock(ParametresIaService.class);
    private final OpenAiFournisseur openai = mock(OpenAiFournisseur.class);
    private final AnthropicFournisseur anthropic = mock(AnthropicFournisseur.class);
    private IaRouteur routeur;

    private static ConfigIa config(boolean openai, boolean anthropic, Instant epuise, Instant refusee) {
        return new ConfigIa(openai ? "sk-o" : null, "o4-mini", null, anthropic ? "sk-a" : null, "claude-sonnet-5-5", null, epuise, refusee);
    }

    private void config(ConfigIa c) {
        when(parametres.config()).thenReturn(c);
        when(openai.configure()).thenReturn(c.openaiConfigure());
        when(anthropic.configure()).thenReturn(c.anthropicConfigure());
    }

    @BeforeEach
    void init() {
        routeur = new IaRouteur(parametres, openai, anthropic);
        when(openai.json(anyString(), anyString(), anyInt(), any())).thenReturn("{\"via\":\"openai\"}");
        when(anthropic.json(anyString(), anyString(), anyInt(), any())).thenReturn("{\"via\":\"anthropic\"}");
        when(openai.texte(anyString(), anyString(), anyInt())).thenReturn("openai");
        when(anthropic.texte(anyString(), anyString(), anyInt())).thenReturn("anthropic");
    }

    @Test
    void uneAnalyseVaChezClaudeLeResteChezOpenAi() {
        config(config(true, true, null, null));
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("anthropic");
        assertThat(routeur.json(IaRouteur.Usage.COURANT, "s", "u", 10, SCHEMA)).contains("openai");
        assertThat(routeur.texte(IaRouteur.Usage.COURANT, "s", "u", 10)).isEqualTo("openai");
    }

    @Test
    void creditEpuiseRepliSurOpenAiEtEtatMemorise() {
        config(config(true, true, null, null));
        when(anthropic.json(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new ErreurIa(ErreurIa.Genre.CREDIT_EPUISE, "épuisé", null));

        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        verify(parametres).marquerAnthropicEpuise();
    }

    @Test
    void cleRefuseeRepliSurOpenAiEtEtatMemorise() {
        config(config(true, true, null, null));
        when(anthropic.json(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new ErreurIa(ErreurIa.Genre.CLE_REFUSEE, "refusée", null));

        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        verify(parametres).marquerAnthropicRefusee();
    }

    @Test
    void echecTemporaireRepliPourCetAppelSeulementSansMarquerLeCredit() {
        config(config(true, true, null, null));
        when(anthropic.json(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new ErreurIa(ErreurIa.Genre.TEMPORAIRE, "surchargé", null));

        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        verify(parametres, never()).marquerAnthropicEpuise();
        verify(parametres, never()).marquerAnthropicRefusee();
    }

    @Test
    void tantQueLeCreditEstEpuiseLesAnalysesVontDirectementChezOpenAiPuisSondeApresReprise() {
        config(config(true, true, Instant.now(), null));
        routeur.reessayerAnthropic();
        // Première analyse : la sonde est due, Claude est essayé ; il refuse toujours.
        when(anthropic.json(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new ErreurIa(ErreurIa.Genre.CREDIT_EPUISE, "épuisé", null));
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        // Deuxième : la prochaine sonde n'est pas due, Claude n'est pas rappelé.
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        verify(anthropic, times(1)).json(anyString(), anyString(), anyInt(), any());
        // L'administrateur clique sur « Tester » : Claude redevient candidat aussitôt, et répond.
        routeur.reessayerAnthropic();
        org.mockito.Mockito.doReturn("{\"via\":\"anthropic\"}").when(anthropic).json(anyString(), anyString(), anyInt(), any());
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("anthropic");
        verify(parametres).marquerAnthropicRetabli();
    }

    @Test
    void sansOpenAiLaCleAnthropicServiraAussiAuxAppelsCourants() {
        config(config(false, true, null, null));
        assertThat(routeur.texte(IaRouteur.Usage.COURANT, "s", "u", 10)).isEqualTo("anthropic");
    }

    @Test
    void sansAucuneCleLeResultatEstNulPourLeRepliLocal() {
        config(config(false, false, null, null));
        assertThat(routeur.disponible()).isFalse();
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).isNull();
        assertThat(routeur.texte(IaRouteur.Usage.COURANT, "s", "u", 10)).isNull();
    }

    @Test
    void analyseSansClaudeConfigureVaChezOpenAi() {
        config(config(true, false, null, null));
        assertThat(routeur.json(IaRouteur.Usage.ANALYSE, "s", "u", 10, SCHEMA)).contains("openai");
        verify(anthropic, never()).json(anyString(), anyString(), anyInt(), any());
    }
}
