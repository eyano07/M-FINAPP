package com.mbsc.finapp.service.ia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Décide quel fournisseur répond à un appel d'IA.
 *
 * <ul>
 *   <li><b>Analyses</b> ({@link Usage#ANALYSE} : états financiers, restaurant, proposition de budget) : Claude
 *       tant que sa clé est présente et son crédit utilisable ; sinon OpenAI.</li>
 *   <li><b>Tout le reste</b> ({@link Usage#COURANT}) : OpenAI. Si seule la clé Anthropic est renseignée, elle
 *       sert aussi à ces appels plutôt que de laisser la fonction sans IA.</li>
 *   <li><b>Repli</b> : un échec d'Anthropic sur une analyse refait <em>cet appel</em> sur OpenAI. Un crédit
 *       épuisé ou une clé refusée sont mémorisés (les analyses suivantes vont directement sur OpenAI, les
 *       administrateurs sont prévenus) ; une surcharge ou un délai dépassé ne concernent que l'appel en cours.</li>
 *   <li><b>Reprise</b> : Anthropic est ressayé toutes les {@value #SONDE_MINUTES} minutes tant qu'il est écarté,
 *       et aussitôt quand l'administrateur enregistre une clé ou clique sur « Tester ».</li>
 * </ul>
 */
@Component
public class IaRouteur {

    private static final Logger log = LoggerFactory.getLogger(IaRouteur.class);
    static final int SONDE_MINUTES = 60;

    public enum Usage { COURANT, ANALYSE }

    private final ParametresIaService parametres;
    private final OpenAiFournisseur openai;
    private final AnthropicFournisseur anthropic;
    private final AtomicReference<Instant> prochaineSonde = new AtomicReference<>(Instant.MIN);

    public IaRouteur(ParametresIaService parametres, OpenAiFournisseur openai, AnthropicFournisseur anthropic) {
        this.parametres = parametres;
        this.openai = openai;
        this.anthropic = anthropic;
    }

    /** true si au moins un fournisseur est configuré. */
    public boolean disponible() {
        ConfigIa c = parametres.config();
        return c.openaiConfigure() || c.anthropicConfigure();
    }

    public String texte(Usage usage, String systeme, String utilisateur, int maxTokens) {
        return executer(usage, f -> f.texte(systeme, utilisateur, maxTokens));
    }

    public String json(Usage usage, String systeme, String utilisateur, int maxTokens, Map<String, Object> schema) {
        return executer(usage, f -> f.json(systeme, utilisateur, maxTokens, schema));
    }

    public String jsonAvecDocument(Usage usage, String systeme, String consigne, String nomFichier, String typeMime,
                                   byte[] contenu, int maxTokens, Map<String, Object> schema) {
        return executer(usage, f -> f.jsonAvecDocument(systeme, consigne, nomFichier, typeMime, contenu, maxTokens, schema));
    }

    /** L'administrateur vient d'enregistrer une clé ou de tester : Anthropic redevient candidat immédiatement. */
    public void reessayerAnthropic() {
        prochaineSonde.set(Instant.MIN);
    }

    private String executer(Usage usage, Function<FournisseurIa, String> appel) {
        if (usage == Usage.ANALYSE && anthropicUtilisable()) {
            try {
                String reponse = appel.apply(anthropic);
                parametres.marquerAnthropicRetabli();
                return reponse;
            } catch (ErreurIa e) {
                traiterEchecAnthropic(e);
            } catch (RuntimeException e) {
                log.warn("Analyse Anthropic en échec, repli sur OpenAI : {}", e.getMessage());
            }
        }
        if (openai.configure()) {
            return appel.apply(openai);
        }
        // OpenAI non configuré : la clé Anthropic seule sert aussi aux appels courants.
        if (usage == Usage.COURANT && anthropicUtilisable()) {
            return appel.apply(anthropic);
        }
        return null;
    }

    private void traiterEchecAnthropic(ErreurIa e) {
        switch (e.genre()) {
            case CREDIT_EPUISE -> parametres.marquerAnthropicEpuise();
            case CLE_REFUSEE -> parametres.marquerAnthropicRefusee();
            default -> log.warn("Analyse Anthropic en échec ({}), repli sur OpenAI pour cet appel : {}", e.genre(), e.getMessage());
        }
    }

    /** Clé présente et, si Anthropic a été écarté, une nouvelle tentative est due. */
    boolean anthropicUtilisable() {
        ConfigIa c = parametres.config();
        if (!c.anthropicConfigure()) return false;
        if (c.anthropicEpuiseDepuis() == null && c.anthropicRefuseeDepuis() == null) return true;
        Instant maintenant = Instant.now();
        Instant due = prochaineSonde.get();
        if (maintenant.isBefore(due)) return false;
        return prochaineSonde.compareAndSet(due, maintenant.plus(Duration.ofMinutes(SONDE_MINUTES)));
    }
}
