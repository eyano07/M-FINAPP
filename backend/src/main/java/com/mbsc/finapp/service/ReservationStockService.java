package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.repository.VenteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock mis de cote par les commandes en attente (V134) : ce qu'une table a commande (vente BROUILLON) ne peut plus etre
 * vendu a une autre table, ni sorti en perte ou en cadeau, jusqu'a ce que la commande soit validee (sortie de stock et,
 * pour une bouteille consignee, vide ajoute), qu'une ligne en soit retiree ou qu'elle soit abandonnee.
 *
 * <p>Le reserve se calcule a la volee sur les lignes des ventes BROUILLON (aucune table dediee, rien a desynchroniser) ;
 * le controle verrouille la ligne de stock, si bien que deux caisses qui mettent de cote la derniere bouteille en meme
 * temps passent l'une apres l'autre et que la seconde est refusee. Concerne les articles du restaurant que l'on commande :
 * boissons (consignees ou non) et plats.</p>
 *
 * <p>Sans {@code @PreAuthorize} : l'autorisation est portee par les services appelants (vente, restaurant).</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationStockService {

    private static final Long AUCUNE_VENTE = -1L;

    private final VenteRepository venteRepository;
    private final StockNiveauRepository niveauRepository;

    /** true pour un article qu'une commande en attente met de cote : boisson ou plat. */
    public static boolean concerne(Article article) {
        return article != null && (article.getType() == TypeArticle.BOISSON || article.getType() == TypeArticle.PLAT);
    }

    /** Quantite que mettent de cote les commandes en attente, sauf la vente {@code venteExclue} (nulle : aucune). */
    public BigDecimal reserve(Long articleId, Long entrepotId, Long venteExclue) {
        BigDecimal r = venteRepository.quantiteReservee(articleId, entrepotId, venteExclue == null ? AUCUNE_VENTE : venteExclue);
        return r == null ? BigDecimal.ZERO : r;
    }

    /**
     * Exige que chaque article puisse sortir dans la quantite demandee sans prendre ce que d'autres commandes en attente
     * ont mis de cote. Les lignes de stock sont verrouillees dans l'ordre des identifiants d'article (meme ordre pour
     * toutes les transactions : pas d'attente croisee).
     *
     * @param quantites   quantite totale demandee par article (une commande peut citer deux fois le meme article)
     * @param venteExclue la commande qui demande : sa propre reservation compte comme disponible (nulle : aucune)
     */
    public void exigerDisponibles(Map<Article, BigDecimal> quantites, Entrepot entrepot, Long venteExclue) {
        if (entrepot == null || quantites == null) return;
        quantites.entrySet().stream()
            .filter(e -> concerne(e.getKey()) && e.getValue() != null && e.getValue().signum() > 0)
            .sorted(Comparator.comparing(e -> e.getKey().getId()))
            .forEach(e -> exigerDisponible(e.getKey(), entrepot, e.getValue(), venteExclue));
    }

    /** Variante pour un seul article — voir {@link #exigerDisponibles}. */
    public void exigerDisponible(Article article, Entrepot entrepot, BigDecimal quantite, Long venteExclue) {
        if (!concerne(article) || entrepot == null || quantite == null || quantite.signum() <= 0) return;
        BigDecimal stock = niveauRepository.verrouiller(article.getId(), entrepot.getId())
            .map(StockNiveau::getQuantite)
            .orElse(BigDecimal.ZERO);
        Long exclue = venteExclue == null ? AUCUNE_VENTE : venteExclue;
        BigDecimal reserve = reserve(article.getId(), entrepot.getId(), exclue);
        if (quantite.compareTo(stock.subtract(reserve)) > 0) {
            List<String> commandes = reserve.signum() > 0
                ? venteRepository.referencesReservant(article.getId(), entrepot.getId(), exclue)
                : List.of();
            throw new IllegalArgumentException(messageInsuffisant(article.getLibelle(), stock, reserve, quantite, commandes));
        }
    }

    /** Message du refus : ce qu'il y a en stock, ce que d'autres commandes ont mis de cote (et lesquelles), ce qui est demande. */
    static String messageInsuffisant(String libelle, BigDecimal stock, BigDecimal reserve, BigDecimal demande,
                                     List<String> commandes) {
        StringBuilder m = new StringBuilder("Stock disponible insuffisant pour « ").append(libelle).append(" » : ")
            .append(nombre(stock)).append(" en stock");
        if (reserve.signum() > 0) {
            m.append(", dont ").append(nombre(reserve)).append(" mis de côté par ")
                .append(commandes.size() == 1 ? "la commande en attente " : "les commandes en attente ")
                .append(String.join(", ", commandes.stream().limit(5).toList()))
                .append(commandes.size() > 5 ? "…" : "");
        }
        BigDecimal disponible = stock.subtract(reserve).max(BigDecimal.ZERO);
        return m.append(" ; disponible : ").append(nombre(disponible)).append(", demandé : ").append(nombre(demande)).append('.')
            .toString();
    }

    /** Tout le stock mis de cote, par article puis par entrepot. */
    public Map<Long, Map<Long, BigDecimal>> reservations() {
        Map<Long, Map<Long, BigDecimal>> r = new HashMap<>();
        for (Object[] l : venteRepository.reservations()) {
            r.computeIfAbsent((Long) l[0], k -> new HashMap<>()).put((Long) l[1], (BigDecimal) l[2]);
        }
        return r;
    }

    private static String nombre(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }
}
