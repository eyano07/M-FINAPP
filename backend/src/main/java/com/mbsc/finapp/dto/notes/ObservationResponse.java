package com.mbsc.finapp.dto.notes;

import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.StatutNote;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Une entree de la chronologie (timeline) du workflow d'une note.
 *
 * <p>{@code auteurFonction} est la fonction inscrite sur la fiche de l'auteur au moment
 * de la consultation (« Directeur Financier ») : elle remplace la mention du role dans le
 * texte de l'etape (« Validee » et non plus « Validee par le DA »).</p>
 */
public record ObservationResponse(
    Long id,
    String auteurNom,
    String auteurFonction,
    StatutNote statutAuMoment,
    String commentaire,
    Instant dateAction
) {
    public static ObservationResponse from(ObservationNote o) {
        var auteur = o.getAuteur();
        String nom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom()) + " "
             + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        return new ObservationResponse(
            o.getId(),
            nom,
            fonctionDe(auteur),
            o.getStatutAuMoment(),
            o.getCommentaire(),
            o.getDateAction()
        );
    }

    /** Fonction de l'utilisateur sans espaces superflus ; {@code null} si elle n'est pas renseignee. */
    static String fonctionDe(User utilisateur) {
        return utilisateur != null && StringUtils.hasText(utilisateur.getFonction())
            ? utilisateur.getFonction().trim()
            : null;
    }
}
