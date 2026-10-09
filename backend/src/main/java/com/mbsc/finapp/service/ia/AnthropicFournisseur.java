package com.mbsc.finapp.service.ia;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.models.ModelInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Anthropic (Claude), via le SDK officiel : réservé aux analyses (états financiers, restaurant, proposition de
 * budget). Les réponses structurées passent par {@code output_config.format} avec le schéma JSON déjà utilisé
 * pour OpenAI.
 *
 * <p>Réglages propres à Claude Sonnet 5.5 : ni {@code temperature} ni {@code tool_choice} forcé (refusés en
 * 400), réflexion adaptative par défaut dont les tokens comptent dans {@code max_tokens} — le budget demandé
 * est donc relevé à {@value #PLANCHER_TOKENS} — et effort {@code medium}.</p>
 */
@Component
public class AnthropicFournisseur implements FournisseurIa {

    /** Budget minimal : la réflexion adaptative consomme {@code max_tokens} avant le JSON de la réponse. */
    static final long PLANCHER_TOKENS = 16000;

    private final ParametresIaService parametres;
    private final Duration timeout;

    private record Memo(String cle, String baseUrl, AnthropicClient client) {}

    private volatile Memo memo;

    public AnthropicFournisseur(ParametresIaService parametres,
                                @Value("${app.ia.timeout-analyse-ms:60000}") int timeoutAnalyseMs) {
        this.parametres = parametres;
        // Une analyse avec réflexion dépasse vite 60 s : au moins deux minutes.
        this.timeout = Duration.ofMillis(Math.max(timeoutAnalyseMs, 120_000));
    }

    @Override
    public String nom() {
        return "Anthropic";
    }

    @Override
    public boolean configure() {
        return parametres.config().anthropicConfigure();
    }

    @Override
    public String texte(String systeme, String utilisateur, int maxTokens) {
        ConfigIa c = parametres.config();
        MessageCreateParams params = MessageCreateParams.builder()
            .model(c.anthropicModele())
            .maxTokens(Math.max(maxTokens, 4096))
            .system(systeme)
            .addUserMessage(utilisateur)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build();
        return appeler(params);
    }

    @Override
    public String json(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson) {
        ConfigIa c = parametres.config();
        MessageCreateParams params = MessageCreateParams.builder()
            .model(c.anthropicModele())
            .maxTokens(Math.max(maxTokens, PLANCHER_TOKENS))
            .system(systeme)
            .addUserMessage(utilisateur)
            .outputConfig(sortie(schemaJson))
            .build();
        return appeler(params);
    }

    @Override
    public String jsonAvecDocument(String systeme, String consigne, String nomFichier, String typeMime, byte[] contenu,
                                   int maxTokens, Map<String, Object> schemaJson) {
        ConfigIa c = parametres.config();
        String donnees = Base64.getEncoder().encodeToString(contenu);
        ContentBlockParam document = typeMime.startsWith("image/")
            ? ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder().data(donnees).mediaType(Base64ImageSource.MediaType.of(typeMime)).build())
                .build())
            : ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(donnees).build())
                .build());
        MessageCreateParams params = MessageCreateParams.builder()
            .model(c.anthropicModele())
            .maxTokens(Math.max(maxTokens, PLANCHER_TOKENS))
            .system(systeme)
            .addUserMessageOfBlockParams(List.of(document,
                ContentBlockParam.ofText(TextBlockParam.builder().text(consigne).build())))
            .outputConfig(sortie(schemaJson))
            .build();
        return appeler(params);
    }

    @Override
    public List<String> listerModeles(String cle) {
        try {
            List<String> ids = new ArrayList<>();
            clientPour(cle, parametres.config().anthropicBaseUrl()).models()
                .list(com.anthropic.models.models.ModelListParams.builder().limit(1000L).build()).data()
                .forEach((ModelInfo m) -> {
                    if (m.id().toLowerCase(Locale.ROOT).startsWith("claude-")) ids.add(m.id());
                });
            // Les plus récents d'abord : l'API les renvoie déjà dans cet ordre, on le conserve.
            return ids.stream().distinct().collect(Collectors.toList());
        } catch (RuntimeException e) {
            throw classer(e);
        }
    }

    @Override
    public void tester(String cle, String modele) {
        try {
            clientPour(cle, parametres.config().anthropicBaseUrl()).messages().create(MessageCreateParams.builder()
                .model(modele)
                .maxTokens(2048)
                .addUserMessage("Réponds par le mot : ok")
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .build());
        } catch (RuntimeException e) {
            throw classer(e);
        }
    }

    // ---------------------------------------------------------------------

    private static OutputConfig sortie(Map<String, Object> schemaJson) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        schemaJson.forEach((cle, valeur) -> schema.putAdditionalProperty(cle, JsonValue.from(valeur)));
        return OutputConfig.builder()
            .effort(OutputConfig.Effort.MEDIUM)
            .format(JsonOutputFormat.builder().schema(schema.build()).build())
            .build();
    }

    private String appeler(MessageCreateParams params) {
        ConfigIa c = parametres.config();
        if (!c.anthropicConfigure()) return null;
        try {
            Message r = clientPour(c.anthropicCle(), c.anthropicBaseUrl()).messages().create(params);
            if (r.stopReason().map(s -> s.equals(StopReason.REFUSAL)).orElse(false)) {
                throw new ErreurIa(ErreurIa.Genre.AUTRE, "Claude a refusé de répondre à cette demande.", null);
            }
            if (r.stopReason().map(s -> s.equals(StopReason.MAX_TOKENS)).orElse(false)) {
                throw new ErreurIa(ErreurIa.Genre.AUTRE, "La réponse de Claude a été tronquée (budget de tokens atteint).", null);
            }
            String texte = r.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).collect(Collectors.joining());
            return StringUtils.hasText(texte) ? texte.strip() : null;
        } catch (ErreurIa e) {
            throw e;
        } catch (RuntimeException e) {
            throw classer(e);
        }
    }

    private AnthropicClient clientPour(String cle, String baseUrl) {
        Memo m = memo;
        if (m != null && m.cle().equals(cle) && Objects.equals(m.baseUrl(), baseUrl)) {
            return m.client();
        }
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(cle).timeout(timeout).maxRetries(0);
        if (StringUtils.hasText(baseUrl)) b.baseUrl(baseUrl);
        AnthropicClient client = b.build();
        memo = new Memo(cle, baseUrl, client);
        return client;
    }

    /**
     * Classe un échec du SDK : facturation (402 {@code billing_error}, ou 400 « credit balance is too low »),
     * clé refusée (401/403), temporaire (429, 5xx dont 529 surcharge, réseau), autre.
     */
    static ErreurIa classer(RuntimeException e) {
        if (e instanceof ErreurIa ia) return ia;
        if (e instanceof AnthropicServiceException s) {
            int code = s.statusCode();
            String type = s.errorType().map(Object::toString).orElse("").toLowerCase(Locale.ROOT);
            String msg = (s.getMessage() == null ? "" : s.getMessage()).toLowerCase(Locale.ROOT);
            if (code == 402 || type.contains("billing") || msg.contains("credit balance") || msg.contains("billing_error")) {
                return new ErreurIa(ErreurIa.Genre.CREDIT_EPUISE, "Crédit Anthropic épuisé.", e);
            }
            if (code == 401 || code == 403) {
                return new ErreurIa(ErreurIa.Genre.CLE_REFUSEE, "Clé Anthropic refusée (" + code + ").", e);
            }
            if (code == 429 || code == 408 || code >= 500) {
                return new ErreurIa(ErreurIa.Genre.TEMPORAIRE, "Anthropic momentanément indisponible (" + code + ").", e);
            }
            return new ErreurIa(ErreurIa.Genre.AUTRE, "Anthropic a refusé la requête (" + code + ") : " + s.getMessage(), e);
        }
        org.slf4j.LoggerFactory.getLogger(AnthropicFournisseur.class).warn("Anthropic : appel en echec ({})", e.toString());
        return new ErreurIa(ErreurIa.Genre.TEMPORAIRE, "Anthropic injoignable : " + e.getMessage(), e);
    }
}
