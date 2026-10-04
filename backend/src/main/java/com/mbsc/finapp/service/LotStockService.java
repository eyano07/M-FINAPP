package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.MethodeSortieLots;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.repository.LotStockConsommationRepository;
import com.mbsc.finapp.repository.LotStockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Suivi de gestion des lots de stock (date d'achat, fournisseur, prix
 * d'achat et de transport) des boissons et provisions du module Restaurant,
 * et de leur déplétion à la sortie : FIFO ou au prorata (CMP).
 *
 * <p><b>Jamais comptable.</b> Cette classe n'écrit aucune écriture et ne
 * modifie jamais {@link StockNiveau} : le coût moyen pondéré reste l'unique
 * valorisation du grand livre, exactement comme avant ce suivi (voir le
 * commentaire d'en-tête de {@link StockService}). Elle existe uniquement
 * pour répondre, sur les pages de stock du module Restaurant, à « quel
 * fournisseur, à quelle date, à quel prix » — et pour ne jamais bloquer une
 * opération réelle (vente, paiement, annulation) si sa propre traçabilité
 * est incomplète : toute anomalie ici se journalise, ne lève jamais.</p>
 */
@Service
@RequiredArgsConstructor
public class LotStockService {

    private static final Logger log = LoggerFactory.getLogger(LotStockService.class);

    /** Seuls ces types sont achetés avec un fournisseur : un plat se produit, il n'a pas de lot. */
    private static final Set<TypeArticle> TYPES_SUIVIS = EnumSet.of(TypeArticle.BOISSON, TypeArticle.PROVISION);

    private final LotStockRepository lotRepository;
    private final LotStockConsommationRepository consommationRepository;

    /** Part d'une consommation : le lot touché et la quantité qui en a été prélevée. */
    public record Part(LotStock lot, BigDecimal quantite) {}

    /**
     * Crée le lot d'une entrée en stock (achat par note de frais, réception
     * directe de provision, arrivée d'une production...). Sans effet si
     * l'article n'est ni une boisson ni une provision, ou si la quantité
     * n'est pas positive.
     */
    @Transactional
    public void enregistrerEntree(Article article, Entrepot entrepot, java.time.LocalDate date, String fournisseur,
                                   BigDecimal quantite, BigDecimal prixAchatUnitaire, BigDecimal prixTransportUnitaire,
                                   LigneMouvementStock ligne) {
        if (!suivi(article) || quantite == null || quantite.signum() <= 0) {
            return;
        }
        lotRepository.save(LotStock.builder()
            .article(article)
            .entrepot(entrepot)
            .ligneMouvement(ligne)
            .dateEntree(date)
            .fournisseur(fournisseur)
            .quantiteInitiale(quantite)
            .quantiteRestante(quantite)
            .prixAchatUnitaire(prixAchatUnitaire == null ? BigDecimal.ZERO : prixAchatUnitaire)
            .prixTransportUnitaire(prixTransportUnitaire == null ? BigDecimal.ZERO : prixTransportUnitaire)
            .build());
    }

    /**
     * Déplète les lots restants d'un (article, entrepôt) pour une sortie de
     * {@code quantite}, selon la méthode de son type d'article — FIFO pour une
     * boisson, CMP pour une provision (voir {@link #methode}) — et journalise précisément ce qui a
     * été pris à chaque lot (pour une annulation exacte). Retourne les parts
     * prélevées, du lot le plus ancien au plus récent — vide si l'article
     * n'est pas suivi.
     *
     * <p>Jamais bloquant : si les lots connus ne couvrent pas {@code
     * quantite} (stock antérieur à cette fonctionnalité, écart d'arrondi),
     * le reliquat est seulement journalisé — ce n'est pas la comptabilité,
     * qui a déjà sa propre valorisation indépendante.</p>
     */
    @Transactional
    public List<Part> consommer(Article article, Entrepot entrepot, BigDecimal quantite, LigneMouvementStock ligne) {
        if (!suivi(article) || quantite == null || quantite.signum() <= 0) {
            return List.of();
        }
        List<LotStock> lots = lotRepository.findByArticleIdAndEntrepotIdAndQuantiteRestanteGreaterThanOrderByDateEntreeAscIdAsc(
            article.getId(), entrepot.getId(), BigDecimal.ZERO);
        // Au millième, l'échelle des quantités en base : une quantité saisie
        // plus finement ferait sinon échouer la répartition au prorata.
        BigDecimal demande = quantite.setScale(ECHELLE_QUANTITE, RoundingMode.HALF_UP);
        List<BigDecimal> prises = methode(article) == MethodeSortieLots.CMP
            ? prisesAuProrata(lots, demande)
            : prisesDansLOrdre(lots, demande);
        List<Part> parts = new ArrayList<>();
        BigDecimal servi = BigDecimal.ZERO;
        for (int i = 0; i < lots.size(); i++) {
            BigDecimal pris = prises.get(i);
            if (pris.signum() <= 0) {
                continue;
            }
            LotStock lot = lots.get(i);
            lot.setQuantiteRestante(lot.getQuantiteRestante().subtract(pris));
            consommationRepository.save(LotStockConsommation.builder()
                .ligneMouvement(ligne).lot(lot).quantite(pris).build());
            parts.add(new Part(lot, pris));
            servi = servi.add(pris);
        }
        BigDecimal manque = demande.subtract(servi);
        if (manque.signum() > 0) {
            log.warn("Traçabilité des lots incomplète : {} unité(s) sans lot d'origine identifié "
                + "[article={}, entrepôt={}] — sans effet sur la comptabilité.",
                manque.toPlainString(), article.getCode(), entrepot.getCode());
        }
        return parts;
    }

    private static final int ECHELLE_QUANTITE = 3;

    /** FIFO : épuise les lots l'un après l'autre, du plus ancien au plus récent. */
    private static List<BigDecimal> prisesDansLOrdre(List<LotStock> lots, BigDecimal demande) {
        List<BigDecimal> prises = new ArrayList<>();
        BigDecimal restant = demande;
        for (LotStock lot : lots) {
            BigDecimal pris = lot.getQuantiteRestante().min(restant);
            prises.add(pris);
            restant = restant.subtract(pris);
        }
        return prises;
    }

    /**
     * CMP : prélève sur chaque lot au prorata de ce qu'il lui reste, si bien
     * que tous perdent la même proportion. Calcul exact en millièmes
     * (entiers) : chaque lot reçoit la partie entière de sa part, puis les
     * millièmes manquants vont aux lots dont la part tronquée était la plus
     * forte (à égalité, au plus ancien). La somme des prises égale donc
     * exactement la demande — bornée à ce que les lots contiennent — et
     * aucune prise ne dépasse son lot.
     *
     * <p>Les quantités par lot peuvent en devenir décimales (un kilo de riz
     * sorti de deux lots de 20 et 10 kg en retire 0,667 et 0,333) : en CMP, une
     * unité sortie n'appartient à aucun lot en particulier. C'est pourquoi les
     * boissons, comptées en bouteilles, sortent en FIFO.</p>
     */
    private static List<BigDecimal> prisesAuProrata(List<LotStock> lots, BigDecimal demande) {
        List<BigInteger> restes = lots.stream()
            .map(l -> enMilliemes(l.getQuantiteRestante()))
            .toList();
        BigInteger disponible = restes.stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (disponible.signum() <= 0) {
            return lots.stream().map(l -> BigDecimal.ZERO).toList();
        }
        BigInteger aPrendre = enMilliemes(demande).min(disponible);

        List<BigInteger> prises = new ArrayList<>();
        List<BigInteger> fractions = new ArrayList<>();
        BigInteger distribue = BigInteger.ZERO;
        for (BigInteger reste : restes) {
            BigInteger[] quotientEtReste = aPrendre.multiply(reste).divideAndRemainder(disponible);
            prises.add(quotientEtReste[0]);
            fractions.add(quotientEtReste[1]);
            distribue = distribue.add(quotientEtReste[0]);
        }
        int manquants = aPrendre.subtract(distribue).intValueExact();
        List<Integer> parFractionDecroissante = java.util.stream.IntStream.range(0, lots.size()).boxed()
            .sorted((i, j) -> {
                int c = fractions.get(j).compareTo(fractions.get(i));
                return c != 0 ? c : Integer.compare(i, j);
            })
            .toList();
        for (int k = 0; k < manquants; k++) {
            int i = parFractionDecroissante.get(k);
            prises.set(i, prises.get(i).add(BigInteger.ONE));
        }
        return prises.stream()
            .map(p -> new BigDecimal(p, ECHELLE_QUANTITE))
            .toList();
    }

    private static BigInteger enMilliemes(BigDecimal quantite) {
        return quantite.setScale(ECHELLE_QUANTITE, RoundingMode.HALF_UP).unscaledValue();
    }

    /**
     * Recrée dans l'entrepôt cible, à l'identique (même fournisseur, même
     * prix, même date), un lot par part consommée à la source d'un
     * transfert — la marchandise transférée garde sa traçabilité d'origine.
     */
    @Transactional
    public void transferer(List<Part> parts, Entrepot cible, LigneMouvementStock ligne) {
        for (Part p : parts) {
            if (p.quantite().signum() <= 0) {
                continue;
            }
            LotStock source = p.lot();
            lotRepository.save(LotStock.builder()
                .article(source.getArticle())
                .entrepot(cible)
                .ligneMouvement(ligne)
                .dateEntree(source.getDateEntree())
                .fournisseur(source.getFournisseur())
                .quantiteInitiale(p.quantite())
                .quantiteRestante(p.quantite())
                .prixAchatUnitaire(source.getPrixAchatUnitaire())
                .prixTransportUnitaire(source.getPrixTransportUnitaire())
                .build());
        }
    }

    /**
     * Défait ce qu'une ligne de mouvement avait fait sur les lots, quel que
     * soit son type (une ligne de transfert a consommé ET créé) :
     * <ul>
     *   <li>restitue à chaque lot la quantité que cette ligne lui avait
     *       prise (voir {@link #consommer}) ;</li>
     *   <li>retire de tout lot que cette ligne a créé (voir {@link
     *       #enregistrerEntree}/{@link #transferer}) sa quantité initiale,
     *       bornée à 0 — jamais négative même si un lot a déjà été
     *       partiellement consommé par une sortie postérieure, auquel cas
     *       l'écart (de traçabilité seulement) est journalisé.</li>
     * </ul>
     */
    @Transactional
    public void annuler(LigneMouvementStock ligne) {
        for (LotStockConsommation c : consommationRepository.findByLigneMouvementId(ligne.getId())) {
            LotStock lot = c.getLot();
            lot.setQuantiteRestante(lot.getQuantiteRestante().add(c.getQuantite()));
        }
        for (LotStock lot : lotRepository.findByLigneMouvementId(ligne.getId())) {
            BigDecimal dejaConsomme = lot.getQuantiteInitiale().subtract(lot.getQuantiteRestante());
            if (dejaConsomme.signum() > 0) {
                log.warn("Lot {} annulé alors que {} unité(s) déjà consommée(s) : écart de traçabilité, "
                    + "sans effet sur la comptabilité.", lot.getId(), dejaConsomme.toPlainString());
            }
            lot.setQuantiteRestante(lot.getQuantiteRestante().subtract(lot.getQuantiteInitiale()).max(BigDecimal.ZERO));
        }
    }

    private boolean suivi(Article article) {
        return TYPES_SUIVIS.contains(article.getType());
    }

    /**
     * Méthode fixée par type d'article, et non plus paramétrable :
     * <ul>
     *   <li>boisson : FIFO — une bouteille est une unité physique, la plus
     *       ancienne livraison part d'abord, les lots restent en bouteilles
     *       entières ;</li>
     *   <li>provision : CMP — riz, huile ou épices se mélangent en cuisine,
     *       chaque sortie prélève sur tous les lots au prorata.</li>
     * </ul>
     * La comptabilité, elle, reste au coût moyen pondéré dans les deux cas.
     */
    static MethodeSortieLots methode(Article article) {
        return article.getType() == TypeArticle.BOISSON ? MethodeSortieLots.FIFO : MethodeSortieLots.CMP;
    }
}
