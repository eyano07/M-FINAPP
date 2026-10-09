package com.mbsc.finapp.service.ia;

import java.util.List;
import java.util.Map;

/**
 * Un fournisseur d'IA (OpenAI, Anthropic). Les méthodes d'appel utilisent la configuration courante
 * ({@link ParametresIaService#config()}) ; une exception propre à l'IA ({@link ErreurIa}) classe l'échec pour
 * que {@link IaRouteur} décide du repli.
 */
public interface FournisseurIa {

    String nom();

    /** true si une clé est configurée pour ce fournisseur. */
    boolean configure();

    /** Réponse courte en texte libre. {@code null} si le modèle ne renvoie rien. */
    String texte(String systeme, String utilisateur, int maxTokens);

    /** Réponse contrainte par un schéma JSON (sorties structurées). */
    String json(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson);

    /** Comme {@link #json}, avec un document joint (PDF ou image). */
    String jsonAvecDocument(String systeme, String consigne, String nomFichier, String typeMime, byte[] contenu,
                            int maxTokens, Map<String, Object> schemaJson);

    /** Modèles de texte proposés par le fournisseur pour la clé donnée (clé vérifiée par la même occasion). */
    List<String> listerModeles(String cle);

    /** Appel minimal avec la clé et le modèle donnés ; lève une {@link ErreurIa} classée en cas d'échec. */
    void tester(String cle, String modele);
}
