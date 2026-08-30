package com.mbsc.finapp.dto.admin;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.TypeCompte;

/**
 * Detail d'un compte, rubriques du referentiel SYSCOHADA commente incluses.
 *
 * <p>Les cinq rubriques proviennent du referentiel officiel (AUDCIF 2017) :
 * ce que le compte enregistre, les precisions d'application, les regles de
 * debit/credit, ce qu'il ne doit pas enregistrer, et les pieces permettant
 * de le controler. Elles ne sont renseignees que sur les comptes principaux
 * du plan officiel.</p>
 */
public record CompteDetailResponse(
    Long id,
    String numero,
    String libelle,
    TypeCompte type,
    Integer classe,
    boolean manuel,
    boolean imputable,
    boolean actif,
    String parentNumero,
    String parentLibelle,
    String contenu,
    String commentaires,
    String fonctionnement,
    String exclusions,
    String controle
) {
    public static CompteDetailResponse from(CompteOHADA c) {
        var parent = c.getParent();
        return new CompteDetailResponse(
            c.getId(),
            c.getNumero(),
            c.getLibelle(),
            c.getType(),
            c.getClasse(),
            c.isManuel(),
            c.isImputable(),
            c.isActif(),
            parent == null ? null : parent.getNumero(),
            parent == null ? null : parent.getLibelle(),
            c.getContenu(),
            c.getCommentaires(),
            c.getFonctionnement(),
            c.getExclusions(),
            c.getControle()
        );
    }
}
