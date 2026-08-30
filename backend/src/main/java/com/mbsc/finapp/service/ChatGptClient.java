package com.mbsc.finapp.service;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;

/**
 * Client générique pour l'API Chat Completions d'OpenAI (modèles ChatGPT),
 * partagé par tous les agents IA de l'application (suggestion de compte,
 * reformulation de libellé, analyse des états financiers...). Centralise la
 * configuration (clé API, modèle, délais, activation) et le mode dégradé :
 * {@link #disponible()} renvoie false si aucune clé n'est configurée ou si
 * l'IA est désactivée, ce qui permet à chaque appelant de basculer sur son
 * propre repli local sans jamais bloquer le circuit métier.
 *
 * <p>Remplace {@code ClaudeClient} (API Anthropic) à la demande explicite de
 * l'utilisateur — même contrat public ({@code disponible}/{@code texte}/
 * {@code json}), donc aucun changement requis dans les appelants
 * ({@code IaAssistantService}, {@code ImportIaService},
 * {@code AnalyseFinanciereIaService}).</p>
 */
@Component
public class ChatGptClient {

    /** Client SDK, ou {@code null} en mode dégradé (IA désactivée ou clé absente). */
    private final com.openai.client.OpenAIClient client;
    private final String modele;
    private final Duration timeoutCourt;
    private final Duration timeoutAnalyse;

    public ChatGptClient(@Value("${app.ia.enabled:true}") boolean actif,
                          @Value("${app.ia.openai-api-key:}") String cleApi,
                          @Value("${app.ia.openai-model:gpt-5}") String modele,
                          @Value("${app.ia.openai-base-url:}") String baseUrl,
                          @Value("${app.ia.timeout-ms:8000}") int timeoutMs,
                          @Value("${app.ia.timeout-analyse-ms:60000}") int timeoutAnalyseMs) {
        this.modele = modele;
        this.timeoutCourt = Duration.ofMillis(timeoutMs);
        this.timeoutAnalyse = Duration.ofMillis(timeoutAnalyseMs);

        if (actif && StringUtils.hasText(cleApi)) {
            OpenAIOkHttpClient.Builder constructeur = OpenAIOkHttpClient.builder()
                .apiKey(cleApi)
                .timeout(this.timeoutAnalyse)
                // Aucune reprise automatique : chaque appel reste borne par son
                // delai (le SDK reessaie sinon jusqu'a 2 fois, ce qui triplerait
                // l'attente de l'utilisateur en caisse). Un echec bascule
                // immediatement sur le repli local de l'appelant.
                .maxRetries(0);
            if (StringUtils.hasText(baseUrl)) {
                constructeur.baseUrl(baseUrl);
            }
            this.client = constructeur.build();
        } else {
            this.client = null;
        }
    }

    public boolean disponible() {
        return client != null;
    }

    /**
     * Plancher de tokens pour un appel {@link #texte}, quel que soit le
     * {@code maxTokens} demandé par l'appelant. Constaté empiriquement : à
     * budget serré (12 tokens, ex. suggestion de compte à un seul chiffre),
     * gpt-5 consomme la totalité du budget en tokens de raisonnement — même
     * avec {@code reasoning_effort=minimal} — et ne renvoie aucun texte
     * visible ({@code finish_reason: length}, contenu vide). Ce plancher est
     * la plus petite valeur testée qui élimine le raisonnement (0 token) et
     * renvoie la réponse attendue ; les appelants continuent de raisonner en
     * "longueur de réponse utile", pas en "budget total GPT".
     */
    private static final int PLANCHER_TOKENS_TEXTE = 32;

    /**
     * Appel court, réponse en texte libre (suggestion de compte, reformulation
     * de libellé).
     */
    public String texte(String systeme, String utilisateur, int maxTokens) {
        if (client == null) return null;
        ChatCompletionCreateParams params = requete(systeme, utilisateur, Math.max(maxTokens, PLANCHER_TOKENS_TEXTE))
            .build();
        return premierTexte(client.chat().completions().create(params, options(timeoutCourt)));
    }

    /**
     * Appel long dont la réponse est contrainte par un schéma JSON (sorties
     * structurées) : l'API garantit alors un JSON valide et conforme, sans
     * balise markdown ni texte parasite.
     *
     * @param schemaJson schéma JSON Schema de la réponse attendue ; doit porter
     *                   {@code "additionalProperties": false} et lister toutes
     *                   les clés dans {@code "required"}, comme l'exige l'API.
     */
    public String json(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson) {
        if (client == null) return null;
        // Le schema JSON lui-meme (type/properties/required/additionalProperties)
        // est un objet imbrique distinct du wrapper JsonSchema (qui ne porte que
        // name/schema/strict) : le construire directement sur JsonSchema.Builder
        // via putAdditionalProperty produit un objet plat sans le champ "schema"
        // attendu par l'API ("Missing required parameter:
        // 'response_format.json_schema.schema'").
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaImbrique =
            ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        schemaJson.forEach((cle, valeur) -> schemaImbrique.putAdditionalProperty(cle, JsonValue.from(valeur)));

        ResponseFormatJsonSchema.JsonSchema jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
            .name("reponse")
            .strict(true)
            .schema(schemaImbrique.build())
            .build();

        ChatCompletionCreateParams params = requete(systeme, utilisateur, maxTokens)
            .responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(jsonSchema).build())
            .build();
        return premierTexte(client.chat().completions().create(params, options(timeoutAnalyse)));
    }

    private ChatCompletionCreateParams.Builder requete(String systeme, String utilisateur, int maxTokens) {
        return ChatCompletionCreateParams.builder()
            .model(modele)
            .maxCompletionTokens(maxTokens)
            // Equivalent du thinking desactive cote Claude : sur les modeles
            // GPT a raisonnement (gpt-5...), les tokens de raisonnement sont
            // pris sur le meme budget que maxCompletionTokens. Sans ce
            // reglage, un maxTokens serre (12 pour une suggestion de compte)
            // est integralement consomme par le raisonnement invisible et ne
            // laisse aucun token pour la reponse — contenu vide, repli
            // silencieux sur le compte par defaut. Ces appels sont des taches
            // cadrees (donnees deja fournies) : le raisonnement etendu n'y
            // apporte rien.
            .reasoningEffort(ReasoningEffort.MINIMAL)
            .addSystemMessage(systeme)
            .addUserMessage(utilisateur);
    }

    private RequestOptions options(Duration delai) {
        return RequestOptions.builder().timeout(delai).build();
    }

    /**
     * Premier message texte de la réponse. Renvoie {@code null} si le modèle
     * n'a produit aucun texte (refus, réponse vide) : chaque appelant applique
     * alors son repli local.
     */
    private String premierTexte(ChatCompletion completion) {
        return completion.choices().stream()
            .findFirst()
            .flatMap(choix -> choix.message().content())
            .map(String::strip)
            .orElse(null);
    }
}
