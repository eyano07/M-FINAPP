package com.mbsc.finapp.service.ia;

import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Instantané de la configuration de l'IA (clés en clair, modèles retenus, état du crédit Anthropic), lu une
 * fois par appel : à défaut de valeur saisie dans l'écran Administration, les variables d'environnement
 * {@code APP_OPENAI_*} restent le secours d'une installation existante.
 */
public record ConfigIa(
    String openaiCle,
    String openaiModele,
    String openaiBaseUrl,
    String anthropicCle,
    String anthropicModele,
    String anthropicBaseUrl,
    Instant anthropicEpuiseDepuis,
    Instant anthropicRefuseeDepuis
) {
    public boolean openaiConfigure() {
        return StringUtils.hasText(openaiCle);
    }

    public boolean anthropicConfigure() {
        return StringUtils.hasText(anthropicCle);
    }
}
