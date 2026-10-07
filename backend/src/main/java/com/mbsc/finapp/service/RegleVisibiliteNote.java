package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.util.EnumSet;
import java.util.Set;

/**
 * Qui voit quelles notes de frais : une seule regle par utilisateur, deduite
 * de ses roles. Elle sert a la fois a filtrer la liste, a refuser l'acces
 * direct a une note (anti-IDOR) et a expliquer a l'utilisateur pourquoi une
 * note peut lui etre invisible : le texte affiche ne peut donc pas diverger
 * de la regle appliquee.
 *
 * <ul>
 *   <li>{@link #SES_NOTES} : le Directeur metier (DIRECTEUR), le responsable
 *       restaurant (RESP_RESTAURANT) et la logistique (LOGISTIQUE), quand ils
 *       n'ont aucun role de la chaine de validation, ne voient que les notes
 *       qu'ils ont eux-memes creees : ils n'interviennent pas dans le circuit
 *       DFIN/DA/Caissier et n'ont pas a consulter les depenses des autres
 *       services ;</li>
 *   <li>{@link #CAISSIER} : le caissier « pur » (sans role DFIN/DA/DG/ADMIN)
 *       voit les notes deja validees par le DA ({@link #STATUTS_VISIBLES_CAISSIER}),
 *       quel qu'en soit le createur, plus ses propres notes a n'importe quel
 *       stade (il reste un employe qui peut soumettre ses propres depenses et
 *       doit pouvoir en suivre l'avancement) ;</li>
 *   <li>{@link #DA} : le DA ne voit pas les notes encore non traitees par le
 *       DFIN (etats {@link StatutNote#BROUILLON} et {@link StatutNote#SOUMISE}) ;</li>
 *   <li>{@link #TOUTES} : les autres roles (DFIN, ADMIN, DG...) conservent une
 *       visibilite complete.</li>
 * </ul>
 */
enum RegleVisibiliteNote {

    SES_NOTES(true,
        "Vous ne voyez ici que les notes que vous avez créées vous-même. Les notes des autres "
        + "collaborateurs ne vous sont pas accessibles : si une note n'apparaît pas, c'est peut-être "
        + "que vous n'êtes pas autorisé à la consulter."),

    CAISSIER(true,
        "Vous voyez vos propres notes, quel que soit leur état, ainsi que les notes déjà validées par "
        + "le DA (validées, transmises à la caisse ou payées). Les autres notes ne vous sont pas "
        + "accessibles : si une note n'apparaît pas, c'est peut-être que vous n'êtes pas autorisé à "
        + "la consulter."),

    DA(true,
        "Les notes encore en brouillon ou soumises ne vous sont pas visibles : elles ne vous parviennent "
        + "qu'après la vérification du DFIN. Si une note n'apparaît pas, c'est peut-être que vous "
        + "n'êtes pas encore autorisé à la consulter."),

    TOUTES(false, "Vous avez accès à toutes les notes de frais.");

    /** Statuts visibles par un caissier « pur » : uniquement les notes deja validees par le DA. */
    static final Set<StatutNote> STATUTS_VISIBLES_CAISSIER = EnumSet.of(
        StatutNote.VALIDEE_DA, StatutNote.TRANSMISE_CAISSE, StatutNote.PAYEE);

    private final boolean restreinte;
    private final String explication;

    RegleVisibiliteNote(boolean restreinte, String explication) {
        this.restreinte = restreinte;
        this.explication = explication;
    }

    /** Vrai si la regle cache des notes a l'utilisateur (donc si l'expliquer a un sens). */
    boolean estRestreinte() {
        return restreinte;
    }

    /** Une ou deux phrases pour l'utilisateur concerne : ce qu'il voit, et pourquoi une note peut lui echapper. */
    String explication() {
        return explication;
    }

    /**
     * Regle applicable a un utilisateur, d'apres ses autorites Spring
     * (« ROLE_ADMIN », « ROLE_DA »...). Les roles s'additionnent : un seul role
     * « etroit » ne restreint la vue que s'il n'est pas accompagne d'un role
     * de la chaine de validation.
     */
    static RegleVisibiliteNote pour(Set<String> autorites) {
        boolean estDA = autorites.contains("ROLE_DA");
        boolean estCaissier = autorites.contains("ROLE_CAISSIER");
        boolean estDfinOuAdmin = autorites.contains("ROLE_DFIN") || autorites.contains("ROLE_ADMIN");
        boolean estDG = autorites.contains("ROLE_DG");
        boolean sansRoleDeLaChaine = !(autorites.contains("ROLE_ADMIN") || autorites.contains("ROLE_DG")
            || autorites.contains("ROLE_DA") || autorites.contains("ROLE_DFIN")
            || autorites.contains("ROLE_CAISSIER"));
        boolean estDirecteurSeul = autorites.contains("ROLE_DIRECTEUR") && sansRoleDeLaChaine;
        // La logistique ne cree que des notes de reglement de camions minerais :
        // meme logique que le Directeur metier, elle ne consulte pas les
        // depenses des autres services.
        boolean estLogistiqueSeul = autorites.contains("ROLE_LOGISTIQUE") && sansRoleDeLaChaine;
        boolean estCaissierSeul = estCaissier && !estDfinOuAdmin && !estDA && !estDG;

        if (estDirecteurSeul || estRespRestaurantSeul(autorites) || estLogistiqueSeul) {
            return SES_NOTES;
        }
        if (estCaissierSeul) {
            return CAISSIER;
        }
        if (estDA && !estDfinOuAdmin) {
            return DA;
        }
        return TOUTES;
    }

    /**
     * Vrai si l'utilisateur n'a QUE le role RESP_RESTAURANT (pas
     * COMPTABLE/CAISSIER/DFIN/DA/DG/ADMIN). Contrairement au Directeur et a la
     * logistique, un COMPTABLE en plus suffit a lui rendre la vue complete.
     */
    static boolean estRespRestaurantSeul(Set<String> autorites) {
        return autorites.contains("ROLE_RESP_RESTAURANT")
            && !(autorites.contains("ROLE_ADMIN") || autorites.contains("ROLE_DG")
                || autorites.contains("ROLE_DA") || autorites.contains("ROLE_DFIN")
                || autorites.contains("ROLE_CAISSIER") || autorites.contains("ROLE_COMPTABLE"));
    }

    /** Vrai si cet utilisateur a le droit de voir cette note. */
    boolean voit(NoteFrais note, Long utilisateurId) {
        return switch (this) {
            case SES_NOTES -> estCreateur(note, utilisateurId);
            case CAISSIER -> estCreateur(note, utilisateurId) || STATUTS_VISIBLES_CAISSIER.contains(note.getStatut());
            case DA -> note.getStatut() != StatutNote.BROUILLON && note.getStatut() != StatutNote.SOUMISE;
            case TOUTES -> true;
        };
    }

    private static boolean estCreateur(NoteFrais note, Long utilisateurId) {
        return note.getCreateur() != null && note.getCreateur().getId().equals(utilisateurId);
    }
}
