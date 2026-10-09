package com.mbsc.finapp.service.ia;

import com.mbsc.finapp.domain.ParametresIa;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.repository.ParametresIaRepository;
import com.mbsc.finapp.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Paramètres de l'IA : lecture de la configuration effective (base d'abord, variables d'environnement en
 * secours) et mise à jour. Les écritures se font chacune dans leur propre transaction : un appel d'IA se
 * produit souvent dans une transaction en lecture seule d'un autre service, où PostgreSQL refuserait l'INSERT
 * ou l'UPDATE. L'instantané en mémoire est invalidé à chaque écriture.
 */
@Service
public class ParametresIaService {

    private static final Logger log = LoggerFactory.getLogger(ParametresIaService.class);

    private final ParametresIaRepository repository;
    private final ChiffrementSecretService chiffrement;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    private final String envOpenaiCle;
    private final String envOpenaiModele;
    private final String envOpenaiBaseUrl;
    private final String envAnthropicBaseUrl;
    private final boolean iaActive;

    private volatile ConfigIa cache;

    public ParametresIaService(ParametresIaRepository repository, ChiffrementSecretService chiffrement,
                               NotificationService notifications, PlatformTransactionManager transactionManager,
                               @Value("${app.ia.enabled:true}") boolean iaActive,
                               @Value("${app.ia.openai-api-key:}") String envOpenaiCle,
                               @Value("${app.ia.openai-model:}") String envOpenaiModele,
                               @Value("${app.ia.openai-base-url:}") String envOpenaiBaseUrl,
                               @Value("${app.ia.anthropic-base-url:}") String envAnthropicBaseUrl) {
        this.repository = repository;
        this.chiffrement = chiffrement;
        this.notifications = notifications;
        this.iaActive = iaActive;
        this.envOpenaiCle = envOpenaiCle;
        this.envOpenaiModele = envOpenaiModele;
        this.envOpenaiBaseUrl = envOpenaiBaseUrl;
        this.envAnthropicBaseUrl = envAnthropicBaseUrl;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Configuration effective ; {@code APP_IA_ENABLED=false} coupe toute l'IA, quelles que soient les clés. */
    public ConfigIa config() {
        ConfigIa c = cache;
        if (c == null) {
            c = charger();
            cache = c;
        }
        return c;
    }

    private ConfigIa charger() {
        if (!iaActive) {
            return new ConfigIa(null, ParametresIa.MODELE_OPENAI_DEFAUT, null, null, ParametresIa.MODELE_ANTHROPIC_DEFAUT,
                null, null, null);
        }
        ParametresIa p = tx.execute(s -> repository.findById(ParametresIa.SINGLETON_ID).orElse(null));
        String cleOpenai = p == null ? null : chiffrement.dechiffrer(p.getOpenaiCleChiffree());
        String modeleOpenai = p == null ? null : p.getOpenaiModele();
        if (!StringUtils.hasText(cleOpenai)) {
            // Secours : installation existante pilotée par variables d'environnement.
            cleOpenai = StringUtils.hasText(envOpenaiCle) ? envOpenaiCle.trim() : null;
            if (cleOpenai != null && StringUtils.hasText(envOpenaiModele)) modeleOpenai = envOpenaiModele.trim();
        }
        if (!StringUtils.hasText(modeleOpenai)) modeleOpenai = ParametresIa.MODELE_OPENAI_DEFAUT;
        return new ConfigIa(cleOpenai, modeleOpenai, blanc(envOpenaiBaseUrl),
            p == null ? null : chiffrement.dechiffrer(p.getAnthropicCleChiffree()),
            p == null || !StringUtils.hasText(p.getAnthropicModele()) ? ParametresIa.MODELE_ANTHROPIC_DEFAUT : p.getAnthropicModele(),
            blanc(envAnthropicBaseUrl),
            p == null ? null : p.getAnthropicEpuiseDepuis(),
            p == null ? null : p.getAnthropicRefuseeDepuis());
    }

    /** Ligne en base (clés chiffrées, fins de clé), pour l'écran Administration. */
    public ParametresIa ligne() {
        return tx.execute(s -> repository.findById(ParametresIa.SINGLETON_ID).orElse(null));
    }

    /** true si la clé OpenAI vient des variables d'environnement et non de l'écran Administration. */
    public boolean openaiViaEnvironnement() {
        ParametresIa p = ligne();
        return (p == null || !StringUtils.hasText(p.getOpenaiCleChiffree())) && StringUtils.hasText(envOpenaiCle) && iaActive;
    }

    public boolean iaActive() {
        return iaActive;
    }

    // ---------------------------------------------------------------------
    // Écritures
    // ---------------------------------------------------------------------

    /** Modifie la ligne unique (créée au besoin) puis invalide l'instantané. */
    public void modifier(Consumer<ParametresIa> maj, User auteur) {
        tx.executeWithoutResult(s -> {
            ParametresIa p = repository.findById(ParametresIa.SINGLETON_ID)
                .orElseGet(() -> ParametresIa.builder().id(ParametresIa.SINGLETON_ID).build());
            maj.accept(p);
            p.setModifiePar(auteur);
            p.setDateMaj(Instant.now());
            repository.save(p);
        });
        cache = null;
    }

    public String chiffrer(String cleClair) {
        return chiffrement.chiffrer(cleClair);
    }

    /** Premier refus d'Anthropic pour crédit épuisé : mémorisé, et les administrateurs sont prévenus. */
    public void marquerAnthropicEpuise() {
        if (config().anthropicEpuiseDepuis() != null) return;
        tx.executeWithoutResult(s -> repository.findById(ParametresIa.SINGLETON_ID).ifPresent(p -> {
            p.setAnthropicEpuiseDepuis(Instant.now());
            p.setAnthropicRefuseeDepuis(null);
            repository.save(p);
            notifications.notifierRole(RoleType.ADMIN, TypeNotification.IA_CREDIT_EPUISE,
                "Crédit Anthropic épuisé",
                "Les analyses utilisent OpenAI en attendant. Rechargez le crédit puis cliquez sur « Tester » "
                    + "(Administration › Intelligence artificielle).",
                "/admin/ia", null);
        }));
        cache = null;
        log.warn("Credit Anthropic epuise : les analyses basculent sur OpenAI");
    }

    /** Clé Anthropic refusée (invalide, révoquée) : mémorisé pour l'écran Administration. */
    public void marquerAnthropicRefusee() {
        if (config().anthropicRefuseeDepuis() != null) return;
        tx.executeWithoutResult(s -> repository.findById(ParametresIa.SINGLETON_ID).ifPresent(p -> {
            p.setAnthropicRefuseeDepuis(Instant.now());
            repository.save(p);
        }));
        cache = null;
        log.warn("Cle Anthropic refusee : les analyses basculent sur OpenAI");
    }

    /** Anthropic répond de nouveau : efface les états « épuisé » et « refusée ». */
    public void marquerAnthropicRetabli() {
        ConfigIa c = config();
        if (c.anthropicEpuiseDepuis() == null && c.anthropicRefuseeDepuis() == null) return;
        tx.executeWithoutResult(s -> repository.findById(ParametresIa.SINGLETON_ID).ifPresent(p -> {
            p.setAnthropicEpuiseDepuis(null);
            p.setAnthropicRefuseeDepuis(null);
            repository.save(p);
        }));
        cache = null;
        log.info("Anthropic de nouveau disponible");
    }

    private static String blanc(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
