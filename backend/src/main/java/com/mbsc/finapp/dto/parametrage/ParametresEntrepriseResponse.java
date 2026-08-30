package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.ParametresEntreprise;

public record ParametresEntrepriseResponse(
    String nom,
    String nomComplet,
    String slogan,
    String logoUrl
) {
    public static ParametresEntrepriseResponse from(ParametresEntreprise p) {
        // Parametre de cache-busting (?v=...) : sans lui, le navigateur
        // continuerait de servir l'ancien logo depuis son cache apres un
        // remplacement, l'URL etant sinon toujours la meme.
        String logoUrl = p.getLogoCheminStockage() != null
            ? "/parametres/logo?v=" + (p.getDateMaj() != null ? p.getDateMaj().toEpochMilli() : 0)
            : null;
        return new ParametresEntrepriseResponse(p.getNom(), p.getNomComplet(), p.getSlogan(), logoUrl);
    }
}
