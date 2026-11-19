package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.LigneMouvementStock;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;
import com.mbsc.finapp.dto.logistique.LigneMouvementRequest;
import com.mbsc.finapp.dto.logistique.MouvementRequest;
import com.mbsc.finapp.dto.logistique.MouvementResponse;
import com.mbsc.finapp.dto.logistique.SortieConsommableRequest;
import com.mbsc.finapp.dto.logistique.SortieConsommableResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.MouvementStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sorties de consommables (19/11/2026) : fournitures remises à un service ou à une personne. Aucune mécanique propre :
 * une sortie est un mouvement de stock de SORTIE ({@link StockService}), créé et validé d'un coup, qui débite le compte
 * de charge de chaque article au coût moyen et crédite son compte de stock. Seuls les articles CONSOMMABLE sont admis.
 */
@Service
@RequiredArgsConstructor
public class SortieConsommableService {

    private static final int LIBELLE_MAX = 255;

    private final StockService stock;
    private final ArticleRepository articles;
    private final MouvementStockRepository mouvements;

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<SortieConsommableResponse> lister() {
        return mouvements.lignesSortiesConsommables().stream().map(SortieConsommableService::ligne).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public MouvementResponse sortir(SortieConsommableRequest req) {
        for (SortieConsommableRequest.Ligne l : req.lignes()) {
            Article a = articles.findById(l.articleId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Article", l.articleId()));
            if (a.getType() != TypeArticle.CONSOMMABLE) {
                throw new IllegalArgumentException("« " + a.getLibelle() + " » n'est pas un consommable : sa sortie se "
                    + "fait depuis Logistique › Mouvements.");
            }
        }
        String libelle = "Sortie de consommables — " + req.beneficiaire().trim()
            + (req.motif() == null || req.motif().isBlank() ? "" : " : " + req.motif().trim());
        if (libelle.length() > LIBELLE_MAX) libelle = libelle.substring(0, LIBELLE_MAX);
        List<LigneMouvementRequest> lignes = req.lignes().stream()
            .map(l -> new LigneMouvementRequest(l.articleId(), l.entrepotId(), null, l.quantite(), null))
            .toList();
        MouvementResponse brouillon = stock.creerMouvement(
            new MouvementRequest(TypeMouvementStock.SORTIE, req.dateSortie(), libelle, null, lignes));
        return stock.valider(brouillon.id());
    }

    static SortieConsommableResponse ligne(LigneMouvementStock l) {
        var m = l.getMouvement();
        var a = l.getArticle();
        var auteur = m.getCreatedBy();
        String nom = auteur == null ? null
            : ((auteur.getPrenom() == null ? "" : auteur.getPrenom() + " ") + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        return new SortieConsommableResponse(m.getId(), m.getReference(), m.getDateMouvement(), m.getLibelle(),
            m.getStatut() == null ? null : m.getStatut().name(), a.getId(), a.getCode(), a.getLibelle(), a.getUniteMesure(),
            l.getEntrepotSource() == null ? null : l.getEntrepotSource().getNom(), l.getQuantite(), l.getCoutUnitaire(),
            l.getMontant(), a.getCompteCharge() == null ? null : a.getCompteCharge().getNumero(),
            m.getPiece() == null ? null : m.getPiece().getReference(),
            nom == null || nom.isBlank() ? (auteur == null ? null : auteur.getEmail()) : nom);
    }
}
