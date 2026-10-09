package com.mbsc.finapp.service;

import com.mbsc.finapp.service.ia.IaRouteur;
import com.mbsc.finapp.service.ia.IaRouteur.Usage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Point d'entrée unique des fonctions IA de l'application (suggestion de compte, libellés, import, lecture de
 * relevés, analyses...). Le nom est historique : le client est désormais un simple routeur, configuré dans
 * Administration › Intelligence artificielle — OpenAI pour les appels courants, Claude (Anthropic) pour les
 * analyses, avec repli sur OpenAI si le crédit Anthropic est épuisé. Voir {@link IaRouteur}.
 *
 * <p>Mode dégradé : {@link #disponible()} renvoie false si aucune clé n'est configurée ou si l'IA est
 * désactivée, ce qui permet à chaque appelant de basculer sur son propre repli local sans jamais bloquer le
 * circuit métier. Les appels renvoient {@code null} quand le modèle ne produit aucun texte.</p>
 */
@Component
public class ChatGptClient {

    private final IaRouteur routeur;

    public ChatGptClient(IaRouteur routeur) {
        this.routeur = routeur;
    }

    public boolean disponible() {
        return routeur.disponible();
    }

    /** Appel court, réponse en texte libre (suggestion de compte, reformulation de libellé). */
    public String texte(String systeme, String utilisateur, int maxTokens) {
        return routeur.texte(Usage.COURANT, systeme, utilisateur, maxTokens);
    }

    /**
     * Appel dont la réponse est contrainte par un schéma JSON (sorties structurées) : JSON valide et conforme,
     * sans balise markdown. Le schéma doit porter {@code "additionalProperties": false} et lister toutes les
     * clés dans {@code "required"}, comme l'exigent les deux API.
     */
    public String json(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson) {
        return routeur.json(Usage.COURANT, systeme, utilisateur, maxTokens, schemaJson);
    }

    /** Comme {@link #json}, pour une <b>analyse</b> : confiée à Claude quand il est utilisable, sinon à OpenAI. */
    public String jsonAnalyse(String systeme, String utilisateur, int maxTokens, Map<String, Object> schemaJson) {
        return routeur.json(Usage.ANALYSE, systeme, utilisateur, maxTokens, schemaJson);
    }

    /**
     * Comme {@link #json}, avec un document joint au message : PDF (lu par le modèle, texte et images) ou image
     * (photo ou scan). Utilisé pour extraire les opérations d'un relevé bancaire.
     *
     * @param typeMime {@code application/pdf} ou {@code image/...}
     */
    public String jsonAvecDocument(String systeme, String consigne, String nomFichier, String typeMime, byte[] contenu,
                                   int maxTokens, Map<String, Object> schemaJson) {
        return routeur.jsonAvecDocument(Usage.COURANT, systeme, consigne, nomFichier, typeMime, contenu, maxTokens, schemaJson);
    }
}
