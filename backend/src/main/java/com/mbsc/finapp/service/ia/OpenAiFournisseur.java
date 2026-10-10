package com.mbsc.finapp.service.ia;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ReasoningEffort;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI (API Chat Completions, modèles ChatGPT) : fournisseur par défaut de toutes les fonctions IA
 * (suggestion de compte, libellés, import, lecture de relevés) et repli des analyses quand Claude n'est pas
 * utilisable. Le client est reconstruit quand la clé ou l'adresse changent dans l'écran Administration.
 */
@Component
public class OpenAiFournisseur implements FournisseurIa {

    private final ParametresIaService parametres;
    private final Duration timeoutCourt;
    private final Duration timeoutAnalyse;

    private record Memo(String cle, String baseUrl, OpenAIClient client) {}

    private volatile Memo memo;

    public OpenAiFournisseur(ParametresIaService parametres,
                             @Value("${app.ia.timeout-ms:8000}") int timeoutMs,
                             @Value("${app.ia.timeout-analyse-ms:60000}") int timeoutAnalyseMs) {
        this.parametres = parametres;
        this.timeoutCourt = Duration.ofMillis(timeoutMs);
        this.timeoutAnalyse = Duration.ofMillis(timeoutAnalyseMs);
    }

    @Override
    public String nom() {
        return "OpenAI";
    }

    @Override
    public boolean configure() {
        return parametres.config().openaiConfigure();
    }

    // ---------------------------------------------------------------------
    // Appels
    // ---------------------------------------------------------------------

    /**
     * Plancher de tokens d'un appel {@link #texte}. gpt-5 (raisonnement minimal) consomme tout un budget
     * serré en raisonnement invisible et ne renvoie rien en dessous de 32 ; les modèles de la série « o »
     * (o4-mini...) raisonnent au minimum « low » et ont besoin de bien plus.
     */
    private static final int PLANCHER_TEXTE = 32;
    private static final int PLANCHER_TEXTE_SERIE_O = 1024;
    /** Marge de raisonnement ajoutée aux appels JSON des modèles à raisonnement (tokens de réflexion inclus dans le budget). */
    private static final int MARGE_RAISONNEMENT = 4000;

    @Override
    public String texte(String systeme, String utilisateur, int maxTokens) {
        ConfigIa c = parametres.config();
        int plancher = serieO(c.openaiModele()) ? PLANCHER_TEXTE_SERIE_O : PLANCHER_TEXTE;
        return appeler(requete(c, systeme, utilisateur, Math.max(maxTokens, plancher)).build(), timeoutCourt);
    }

    @Override
    public String json(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson) {
        ConfigIa c = parametres.config();
        ChatCompletionCreateParams params = requete(c, systeme, utilisateur, budgetJson(c, maxTokens))
            .responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(schema(schemaJson)).build())
            .build();
        return appeler(params, timeoutAnalyse);
    }

    @Override
    public String jsonAvecDocument(String systeme, String consigne, String nomFichier, String typeMime, byte[] contenu,
                                   int maxTokens, Map<String, Object> schemaJson) {
        ConfigIa c = parametres.config();
        String donnees = "data:" + typeMime + ";base64," + Base64.getEncoder().encodeToString(contenu);
        ChatCompletionContentPart document = typeMime.startsWith("image/")
            ? ChatCompletionContentPart.ofImageUrl(ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                    .url(donnees).detail(ChatCompletionContentPartImage.ImageUrl.Detail.HIGH).build())
                .build())
            : ChatCompletionContentPart.ofFile(ChatCompletionContentPart.File.builder()
                .file(ChatCompletionContentPart.File.FileObject.builder()
                    .fileData(donnees).filename(nomFichier == null ? "document.pdf" : nomFichier).build())
                .build());
        ChatCompletionContentPart texte = ChatCompletionContentPart.ofText(
            ChatCompletionContentPartText.builder().text(consigne).build());
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
            .model(c.openaiModele())
            .maxCompletionTokens(budgetJson(c, maxTokens));
        effort(b, c.openaiModele(), ReasoningEffort.LOW);
        ChatCompletionCreateParams params = b
            .addSystemMessage(systeme)
            .addUserMessageOfArrayOfContentParts(List.of(texte, document))
            .responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(schema(schemaJson)).build())
            .build();
        // Un relevé de plusieurs pages demande plus de temps qu'une analyse ordinaire.
        Duration delai = timeoutAnalyse.compareTo(Duration.ofMinutes(3)) > 0 ? timeoutAnalyse : Duration.ofMinutes(3);
        return appeler(params, delai);
    }

    // ---------------------------------------------------------------------
    // Administration : liste des modèles et test
    // ---------------------------------------------------------------------

    @Override
    public List<String> listerModeles(String cle) {
        try {
            List<String> ids = new ArrayList<>();
            clientPour(cle, parametres.config().openaiBaseUrl()).models().list().autoPager()
                .forEach(m -> {
                    if (modeleDeTexte(m.id())) ids.add(m.id());
                });
            ids.sort(String::compareTo);
            return ids;
        } catch (RuntimeException e) {
            throw classer(e);
        }
    }

    @Override
    public void tester(String cle, String modele) {
        try {
            ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
                .model(modele)
                .maxCompletionTokens(serieO(modele) ? PLANCHER_TEXTE_SERIE_O : 16);
            effort(b, modele, ReasoningEffort.LOW);
            clientPour(cle, parametres.config().openaiBaseUrl()).chat().completions()
                .create(b.addUserMessage("Réponds par le mot : ok").build(), options(timeoutCourt));
        } catch (RuntimeException e) {
            throw classer(e);
        }
    }

    /** Modèles de conversation (gpt-*, chatgpt-*, série o), à l'exclusion des variantes audio, image, embeddings... */
    static boolean modeleDeTexte(String id) {
        String m = id.toLowerCase(Locale.ROOT);
        boolean famille = m.startsWith("gpt-") || m.startsWith("chatgpt-") || m.matches("o[1-9].*");
        boolean exclu = m.contains("embedding") || m.contains("audio") || m.contains("realtime") || m.contains("image")
            || m.contains("tts") || m.contains("transcribe") || m.contains("whisper") || m.contains("moderation")
            || m.contains("search") || m.contains("instruct") || m.contains("codex") || m.contains("-preview-");
        return famille && !exclu;
    }

    // ---------------------------------------------------------------------

    private String appeler(ChatCompletionCreateParams params, Duration delai) {
        try {
            OpenAIClient client = client();
            if (client == null) return null;
            ChatCompletion r = client.chat().completions().create(params, options(delai));
            return r.choices().stream().findFirst().flatMap(ch -> ch.message().content()).map(String::strip).orElse(null);
        } catch (OpenAIServiceException e) {
            throw classer(e);
        }
    }

    private ChatCompletionCreateParams.Builder requete(ConfigIa c, String systeme, String utilisateur, int maxTokens) {
        ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
            .model(c.openaiModele())
            .maxCompletionTokens(plafondReponse(c.openaiModele(), maxTokens));
        // Tâches cadrées (données déjà fournies) : le raisonnement étendu n'y apporte rien. L'effort minimal
        // n'existe que sur gpt-5 ; la série « o » (o4-mini...) accepte low, medium et high.
        effort(b, c.openaiModele(), serieO(c.openaiModele()) ? ReasoningEffort.LOW : ReasoningEffort.MINIMAL);
        return b.addSystemMessage(systeme).addUserMessage(utilisateur);
    }

    /** {@code reasoning_effort} seulement pour les modèles à raisonnement : les autres (gpt-4o...) le rejettent en 400. */
    private static void effort(ChatCompletionCreateParams.Builder b, String modele, ReasoningEffort souhaite) {
        if (serieO(modele)) {
            b.reasoningEffort(ReasoningEffort.LOW);
        } else if (gpt5(modele)) {
            b.reasoningEffort(souhaite == ReasoningEffort.LOW ? ReasoningEffort.LOW : ReasoningEffort.MINIMAL);
        }
    }

    private static int budgetJson(ConfigIa c, int maxTokens) {
        return plafondReponse(c.openaiModele(), serieO(c.openaiModele()) ? maxTokens + MARGE_RAISONNEMENT : maxTokens);
    }

    /**
     * Jetons de réponse au plus acceptés par le modèle : au-delà, OpenAI refuse toute la requête (400 « max_tokens is too
     * large »), ce qui empêchait par exemple la lecture d'un relevé (32 000 demandés) avec gpt-4o-mini (16 384 au plus).
     * Les modèles à raisonnement (série o, gpt-5) acceptent davantage que ce que l'application demande.
     */
    static int plafondReponse(String modele, int demande) {
        String m = modele == null ? "" : modele.toLowerCase(Locale.ROOT);
        int plafond = m.startsWith("gpt-4o") || m.startsWith("chatgpt-4o") ? 16_384
            : m.startsWith("gpt-4.1") ? 32_768
            : m.startsWith("gpt-4-turbo") || m.startsWith("gpt-3.5") ? 4_096
            : m.equals("gpt-4") || m.startsWith("gpt-4-0") ? 8_192
            : Integer.MAX_VALUE;
        return Math.min(demande, plafond);
    }

    static boolean serieO(String modele) {
        if (!StringUtils.hasText(modele)) return false;
        String m = modele.toLowerCase(Locale.ROOT).trim();
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4");
    }

    static boolean gpt5(String modele) {
        return StringUtils.hasText(modele) && modele.toLowerCase(Locale.ROOT).trim().startsWith("gpt-5");
    }

    private static ResponseFormatJsonSchema.JsonSchema schema(Map<String, Object> schemaJson) {
        // Le schéma lui-même est un objet imbriqué distinct du wrapper JsonSchema (name/schema/strict) : le
        // construire directement sur JsonSchema.Builder produit un objet plat sans le champ « schema » attendu
        // par l'API.
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder imbrique = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        schemaJson.forEach((cle, valeur) -> imbrique.putAdditionalProperty(cle, JsonValue.from(valeur)));
        return ResponseFormatJsonSchema.JsonSchema.builder().name("reponse").strict(true).schema(imbrique.build()).build();
    }

    private static RequestOptions options(Duration delai) {
        return RequestOptions.builder().timeout(delai).build();
    }

    private OpenAIClient client() {
        ConfigIa c = parametres.config();
        if (!c.openaiConfigure()) return null;
        return clientPour(c.openaiCle(), c.openaiBaseUrl());
    }

    private OpenAIClient clientPour(String cle, String baseUrl) {
        Memo m = memo;
        if (m != null && m.cle().equals(cle) && java.util.Objects.equals(m.baseUrl(), baseUrl)) {
            return m.client();
        }
        // Aucune reprise automatique : chaque appel reste borné par son délai, et un échec bascule aussitôt sur
        // le repli local de l'appelant.
        OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder().apiKey(cle).timeout(timeoutAnalyse).maxRetries(0);
        if (StringUtils.hasText(baseUrl)) b.baseUrl(baseUrl);
        OpenAIClient client = b.build();
        memo = new Memo(cle, baseUrl, client);
        return client;
    }

    static ErreurIa classer(RuntimeException e) {
        if (e instanceof ErreurIa ia) return ia;
        if (e instanceof OpenAIServiceException s) {
            int code = s.statusCode();
            String msg = (s.getMessage() == null ? "" : s.getMessage()).toLowerCase(Locale.ROOT);
            if (code == 401 || code == 403) {
                return new ErreurIa(ErreurIa.Genre.CLE_REFUSEE, "Clé OpenAI refusée (" + code + ").", e);
            }
            if (code == 402 || msg.contains("insufficient_quota") || msg.contains("billing")) {
                return new ErreurIa(ErreurIa.Genre.CREDIT_EPUISE, "Crédit OpenAI épuisé ou quota dépassé.", e);
            }
            if (code == 429 || code >= 500 || code == 408) {
                return new ErreurIa(ErreurIa.Genre.TEMPORAIRE, "OpenAI momentanément indisponible (" + code + ").", e);
            }
            return new ErreurIa(ErreurIa.Genre.AUTRE, "OpenAI a refusé la requête (" + code + ") : " + s.getMessage(), e);
        }
        org.slf4j.LoggerFactory.getLogger(OpenAiFournisseur.class).warn("OpenAI : appel en echec ({})", e + (e.getCause() != null ? " <- " + e.getCause() : ""));
        return new ErreurIa(ErreurIa.Genre.TEMPORAIRE, "OpenAI injoignable : " + e.getMessage(), e);
    }
}
