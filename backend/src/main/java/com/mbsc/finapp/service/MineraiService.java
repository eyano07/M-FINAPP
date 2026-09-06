package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.ChargeCamionMinerai;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import com.mbsc.finapp.dto.logistique.CamionMineraiRequest;
import com.mbsc.finapp.dto.logistique.CamionMineraiResponse;
import com.mbsc.finapp.dto.logistique.ChargeCamionRequest;
import com.mbsc.finapp.dto.logistique.ChargeCamionResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.CamionMineraiRepository;
import com.mbsc.finapp.repository.ChargeCamionMineraiRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Suivi des minerais camion par camion.
 *
 * <p>Une marchandise ordinaire se suit en quantite : dix sacs de ciment sont
 * interchangeables, un seul prix de vente suffit. Un minerais, non : chaque
 * chargement se revend a son propre prix selon sa teneur et le cours du jour,
 * alors que tous partagent le meme prix d'achat et les memes comptes. D'ou ce
 * service, qui identifie chaque camion ({@link CamionMinerai}) sans
 * dupliquer le moteur de stock — un camion vaut une unite de l'article.</p>
 *
 * <p><b>Identification specifique plutot que cout moyen pondere.</b> Chaque
 * camion sort du stock a SON propre cout d'acquisition — prix d'achat plus
 * frais accessoires ({@link ChargeCamionMinerai}) — et non au CMP de
 * l'article. Deux chargements n'ayant ni la meme teneur ni les memes frais de
 * route ne sont pas interchangeables : le SYSCOHADA revise admet alors
 * l'identification specifique, qui est ici la seule methode permettant une
 * marge juste camion par camion. Le CMP moyennerait des couts heterogenes et
 * ferait porter a un chargement les frais d'un autre. Voir
 * {@code StockService.SortieVente.coutImpose}.</p>
 *
 * <p><b>Trois cycles, conformes au SYSCOHADA revise :</b></p>
 * <ol>
 *   <li><b>Reception</b> (logistique) — la marchandise arrive avant d'etre
 *       payee : <b>D 601x Achats hors taxes (+ D 4452 TVA recuperable si le
 *       minerais y est soumis) / C 4011 Fournisseurs toutes taxes comprises</b>
 *       (journal ACHATS) fait naitre la dette, <b>D 311x Stock / C 6031
 *       Variation</b> (journal STOCK) constate l'entree pour le seul montant
 *       hors taxes — la TVA recuperable est une creance sur l'Etat, jamais un
 *       element du cout d'acquisition. Voir {@link #receptionner}.</li>
 *   <li><b>Frais accessoires</b> (logistique) — transport, pont bascule,
 *       peage, documents : <b>D 61x/62x / C 4011</b> puis <b>D 311x /
 *       C 6031</b>. Ils entrent dans le cout d'acquisition, pas dans les
 *       charges de la periode. Voir {@link #ajouterCharge}.</li>
 *   <li><b>Reglement</b> (caisse) — <b>D 4011 Fournisseurs / C 571 Caisse</b>
 *       solde la dette (camions et frais), sans toucher ni au stock ni au
 *       resultat. Voir {@code CaisseService.reglerCamionsMinerai}.</li>
 * </ol>
 *
 * <p>La vente reste celle de n'importe quelle marchandise — sortie de stock
 * puis produit au prix convenu — a ceci pres que le prix ET le cout sont
 * propres au chargement (voir {@code VenteService}).</p>
 */
@Service
@RequiredArgsConstructor
public class MineraiService {

    private static final Logger log = LoggerFactory.getLogger(MineraiService.class);

    /** Dette envers le fournisseur du minerais, nee a la reception et soldee par la caisse. */
    public static final String COMPTE_FOURNISSEURS = "4011";

    /** TVA recuperable sur achats — meme compte que la note de frais et la caisse. */
    public static final String COMPTE_TVA_RECUPERABLE = "4452";

    private final CamionMineraiRepository camionRepository;
    private final ChargeCamionMineraiRepository chargeRepository;
    private final StockService stockService;
    private final EcritureComptableService comptabilite;
    private final ComptabiliteService comptabiliteService;
    private final CurrentUserProvider currentUser;
    /** Taux de TVA en vigueur a la date de reception. */
    private final TauxTvaService tauxTvaService;

    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CamionMineraiResponse> lister(Long articleId) {
        var camions = articleId == null
            ? camionRepository.findAllByOrderByDateAchatDescIdDesc()
            : camionRepository.findByArticleIdOrderByDateAchatDescIdDesc(articleId);
        return camions.stream().map(CamionMineraiResponse::from).toList();
    }

    /** Camions encore en stock d'un minerais : ce que le caissier peut vendre. */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CamionMineraiResponse> listerDisponibles(Long articleId) {
        return camionRepository
            .findByArticleIdAndStatutOrderByDateAchatAscIdAsc(articleId, StatutCamionMinerai.EN_STOCK)
            .stream().map(CamionMineraiResponse::from).toList();
    }

    /** Camions dont la dette fournisseur reste a solder : ce que le caissier peut regler. */
    @PreAuthorize("hasAnyRole('CAISSIER', 'LOGISTIQUE', 'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CamionMineraiResponse> listerARegler() {
        return camionRepository.findByRegleFalseOrderByDateAchatAscIdAsc()
            .stream().map(CamionMineraiResponse::from).toList();
    }

    // ---------------------------------------------------------------------
    // Reception (logistique)
    // ---------------------------------------------------------------------

    /**
     * Receptionne un chargement : entree en stock d'une unite et naissance de
     * la dette fournisseur. Voir la javadoc de la classe pour les ecritures.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public CamionMineraiResponse receptionner(CamionMineraiRequest req) {
        User auteur = currentUser.requireUser();
        Article article = stockService.articleParIdInterne(req.articleId());
        if (!article.isMinerais()) {
            throw new IllegalArgumentException(
                "\"" + article.getLibelle() + "\" n'est pas un minerais : son stock se gere en quantite,"
                + " pas camion par camion.");
        }
        if (article.getCompteAchat() == null || article.getCompteStock() == null
            || article.getCompteCharge() == null) {
            throw new IllegalArgumentException(
                "Les comptes d'achat, de stock et de variation doivent etre definis pour \""
                + article.getLibelle() + "\".");
        }
        String plaque = req.plaque().trim().toUpperCase();
        if (camionRepository.existsByArticleIdAndPlaqueIgnoreCaseAndDateAchat(
                article.getId(), plaque, req.dateAchat())) {
            throw new IllegalArgumentException(
                "Le camion " + plaque + " est deja receptionne pour ce minerais au " + req.dateAchat() + ".");
        }
        Entrepot entrepot = stockService.entrepotParIdInterne(req.entrepotId());

        String libelle = "Reception minerais " + article.getLibelle() + " - camion " + plaque;

        // Le prix saisi est HORS TAXES : la TVA s'ajoute par-dessus et reste
        // hors du cout d'acquisition (creance sur l'Etat), mais entre dans la
        // dette fournisseur que la caisse soldera.
        BigDecimal prixHt = req.prixAchat().setScale(2, RoundingMode.HALF_UP);
        BigDecimal tva = article.isSoumisTva()
            ? prixHt.multiply(tauxTvaService.tauxALaDate(req.dateAchat()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // 1. Achat a credit : D 601x HT (+ D 4452 TVA) / C 4011 TTC.
        List<EcritureGrandLivre> achat = new ArrayList<>();
        achat.add(ecriture(article.getCompteAchat(), prixHt, BigDecimal.ZERO, libelle, req.dateAchat()));
        if (tva.signum() > 0) {
            achat.add(ecriture(comptabilite.compteParNumero(COMPTE_TVA_RECUPERABLE),
                tva, BigDecimal.ZERO, libelle, req.dateAchat()));
        }
        achat.add(ecriture(comptabilite.compteParNumero(COMPTE_FOURNISSEURS),
            BigDecimal.ZERO, prixHt.add(tva), libelle, req.dateAchat()));
        PieceComptable pieceAchat = comptabiliteService.creerPieceInterne(
            JournalComptable.ACHATS, libelle, req.dateAchat(), achat, auteur);

        // 2. Entree en stock : D 311x / C 6031.
        MouvementStock mouvement = stockService.enregistrerEntreeMineraiInterne(
            req.dateAchat(), libelle, article, entrepot, prixHt, auteur);

        CamionMinerai camion = camionRepository.save(CamionMinerai.builder()
            .article(article)
            .entrepot(entrepot)
            .plaque(plaque)
            .dateAchat(req.dateAchat())
            .prixAchat(prixHt)
            .montantTva(tva)
            // Aucun frais accessoire encore engage : le cout d'acquisition part
            // du seul prix hors taxes, puis grossit a chaque charge connexe.
            .coutAcquisition(prixHt)
            .statut(StatutCamionMinerai.EN_STOCK)
            .regle(false)
            .mouvement(mouvement)
            .pieceReception(pieceAchat)
            .build());

        log.info("Camion de minerais receptionne [plaque={}, article={}, ht={}, tva={}, par={}]",
            plaque, article.getCode(), prixHt, tva, auteur.getEmail());
        return CamionMineraiResponse.from(camion);
    }

    // ---------------------------------------------------------------------
    // Charges connexes (frais accessoires d'achat)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ChargeCamionResponse> listerCharges(Long camionId) {
        return chargeRepository.findByCamionIdOrderByDateChargeAscIdAsc(camionId)
            .stream().map(ChargeCamionResponse::from).toList();
    }

    /** Charges dont la dette prestataire reste a solder, proposees au reglement en caisse. */
    @PreAuthorize("hasAnyRole('CAISSIER', 'LOGISTIQUE', 'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ChargeCamionResponse> listerChargesARegler() {
        return chargeRepository.findByRegleFalseOrderByDateChargeAscIdAsc()
            .stream().map(ChargeCamionResponse::from).toList();
    }

    /**
     * Incorpore un frais accessoire (transport, pont bascule, peage, documents
     * de chargement/dechargement...) au cout d'acquisition d'un camion.
     *
     * <p>Deux ecritures, comme la reception : <b>D 61x/62x charge par nature /
     * C 4011 Fournisseurs</b> (journal ACHATS) constate la depense, puis
     * <b>D 311x Stock / C 6031 Variation</b> (journal STOCK) la porte au stock.
     * Le cout d'acquisition du camion et la valorisation du niveau de stock
     * augmentent d'autant, sans que la quantite bouge — un camion reste un
     * camion.</p>
     *
     * <p>Refuse apres la vente : le cout du camion est alors deja sorti du
     * stock, l'incorporer retroactivement gonflerait un stock qui n'existe
     * plus. Une depense decouverte apres coup releve d'une charge de la
     * periode, pas du cout d'acquisition.</p>
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public List<ChargeCamionResponse> ajouterCharge(Long camionId, ChargeCamionRequest req) {
        CamionMinerai camion = charger(camionId);
        if (!Boolean.TRUE.equals(req.appliquerATousLesCamions())) {
            return List.of(incorporerCharge(camion, req));
        }
        // Saisie groupee : le meme frais sur chaque camion encore en stock du
        // minerais. Chacun recoit sa propre ligne et ses propres ecritures —
        // elles restent donc modifiables camion par camion ensuite.
        List<CamionMinerai> cibles = camionRepository
            .findByArticleIdAndStatutOrderByDateAchatAscIdAsc(
                camion.getArticle().getId(), StatutCamionMinerai.EN_STOCK);
        if (cibles.isEmpty()) {
            throw new IllegalStateException(
                "Aucun camion en stock pour " + camion.getArticle().getLibelle() + ".");
        }
        List<ChargeCamionResponse> creees = new ArrayList<>();
        for (CamionMinerai cible : cibles) {
            creees.add(incorporerCharge(cible, req));
        }
        log.info("Frais « {} » applique a {} camion(s) de {}",
            req.libelle(), creees.size(), camion.getArticle().getLibelle());
        return creees;
    }

    /** Incorpore un frais accessoire au cout d'acquisition d'UN camion. */
    private ChargeCamionResponse incorporerCharge(CamionMinerai camion, ChargeCamionRequest req) {
        User auteur = currentUser.requireUser();
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException(
                "Le camion " + camion.designation() + " est vendu : son cout d'acquisition est fige."
                + " Une depense posterieure releve des charges de la periode.");
        }
        Article article = camion.getArticle();
        CompteOHADA compteCharge = comptabilite.compteParNumero(req.compteChargeNumero());
        BigDecimal montant = req.montant().setScale(2, RoundingMode.HALF_UP);

        String libelle = req.libelle().trim() + " - camion " + camion.getPlaque();

        // 1. Charge par nature : D 61x/62x / C 4011.
        PieceComptable pieceNature = comptabiliteService.creerPieceInterne(
            JournalComptable.ACHATS, libelle, req.dateCharge(),
            List.of(
                ecriture(compteCharge, montant, BigDecimal.ZERO, libelle, req.dateCharge()),
                ecriture(comptabilite.compteParNumero(COMPTE_FOURNISSEURS),
                    BigDecimal.ZERO, montant, libelle, req.dateCharge())),
            auteur);

        // 2. Incorporation au stock : D 311x / C 6031.
        PieceComptable pieceIncorporation = comptabiliteService.creerPieceInterne(
            JournalComptable.STOCK, "Incorporation au stock - " + libelle, req.dateCharge(),
            List.of(
                ecriture(article.getCompteStock(), montant, BigDecimal.ZERO, libelle, req.dateCharge()),
                ecriture(article.getCompteCharge(), BigDecimal.ZERO, montant, libelle, req.dateCharge())),
            auteur);

        // La quantite ne change pas : seule la valorisation monte.
        stockService.ajusterValeurStockInterne(article, camion.getEntrepot(), montant, req.dateCharge());
        camion.setCoutAcquisition(camion.getCoutAcquisition().add(montant));
        camionRepository.save(camion);

        ChargeCamionMinerai charge = chargeRepository.save(ChargeCamionMinerai.builder()
            .camion(camion)
            .libelle(req.libelle().trim())
            .compteCharge(compteCharge)
            .montant(montant)
            .dateCharge(req.dateCharge())
            .regle(false)
            .piece(pieceNature)
            .pieceIncorporation(pieceIncorporation)
            .build());

        log.info("Charge connexe incorporee [camion={}, libelle={}, montant={}, cout={}]",
            camion.getPlaque(), req.libelle(), montant, camion.getCoutAcquisition());
        return ChargeCamionResponse.from(charge);
    }

    /**
     * Retire un frais accessoire saisi par erreur : extourne les deux pieces et
     * ramene le cout d'acquisition et la valorisation du stock a leur niveau
     * anterieur. Refuse une fois la charge reglee ou le camion vendu, pour la
     * meme raison qu'{@link #ajouterCharge}.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public void supprimerCharge(Long chargeId) {
        User auteur = currentUser.requireUser();
        ChargeCamionMinerai charge = chargeRepository.findById(chargeId)
            .orElseThrow(() -> RessourceIntrouvableException.of("ChargeCamionMinerai", chargeId));
        CamionMinerai camion = charge.getCamion();
        if (charge.isRegle()) {
            throw new IllegalStateException("Cette charge est deja reglee : elle ne peut plus etre supprimee.");
        }
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException(
                "Le camion " + camion.designation() + " est vendu : son cout d'acquisition est fige.");
        }

        if (charge.getPiece() != null) {
            comptabiliteService.annulerInterne(charge.getPiece().getId(), auteur);
        }
        if (charge.getPieceIncorporation() != null) {
            comptabiliteService.annulerInterne(charge.getPieceIncorporation().getId(), auteur);
        }
        stockService.ajusterValeurStockInterne(camion.getArticle(), camion.getEntrepot(),
            charge.getMontant().negate(), charge.getDateCharge());
        camion.setCoutAcquisition(camion.getCoutAcquisition().subtract(charge.getMontant()));
        camionRepository.save(camion);
        chargeRepository.delete(charge);

        log.info("Charge connexe supprimee [camion={}, montant={}, cout={}]",
            camion.getPlaque(), charge.getMontant(), camion.getCoutAcquisition());
    }

    /**
     * Supprime un camion receptionne par erreur. Refuse des qu'il est vendu ou
     * regle : l'annulation devrait alors extourner des ecritures deja
     * rattachees a une vente ou a un decaissement, ce qui releve d'une
     * correction comptable et non d'une suppression de fiche.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        CamionMinerai camion = charger(id);
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException("Le camion " + camion.getPlaque() + " est vendu : il ne peut plus etre supprime.");
        }
        if (camion.isRegle()) {
            throw new IllegalStateException("Le camion " + camion.getPlaque() + " est deja regle : il ne peut plus etre supprime.");
        }
        // Les charges connexes portent leurs propres ecritures et leur propre
        // incorporation au stock : les extourner ici en cascade serait une
        // correction comptable silencieuse. On demande de les retirer d'abord.
        if (chargeRepository.existsByCamionId(camion.getId())) {
            throw new IllegalStateException(
                "Le camion " + camion.getPlaque() + " porte des charges connexes :"
                + " supprimez-les d'abord.");
        }
        // Une vente annulee conserve ses lignes (piste d'audit) : le camion est
        // bien revenu EN_STOCK mais reste reference, la cle etrangere interdit
        // donc de le supprimer. Message explicite plutot qu'erreur technique.
        if (camionRepository.estReferenceParUneVente(camion.getId())) {
            throw new IllegalStateException(
                "Le camion " + camion.getPlaque() + " figure sur une vente (meme annulee) :"
                + " il ne peut plus etre supprime, son historique doit rester tracable.");
        }
        User auteur = currentUser.requireUser();
        // Extourne les deux pieces de la reception et reintegre le stock.
        stockService.annulerMouvementInterne(camion.getMouvement(), auteur);
        if (camion.getPieceReception() != null) {
            comptabiliteService.annulerInterne(camion.getPieceReception().getId(), auteur);
        }
        camionRepository.delete(camion);
        log.info("Camion de minerais supprime [plaque={}, par={}]", camion.getPlaque(), auteur.getEmail());
    }

    // ---------------------------------------------------------------------
    // Acces interne (vente, caisse)
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CamionMinerai charger(Long id) {
        return camionRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("CamionMinerai", id));
    }

    @Transactional(readOnly = true)
    public ChargeCamionMinerai chargerCharge(Long id) {
        return chargeRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("ChargeCamionMinerai", id));
    }

    /** Persiste le marquage « regle » pose par la caisse sur les camions et frais soldes. */
    @Transactional
    public void marquerReglesInterne(List<CamionMinerai> camions, List<ChargeCamionMinerai> charges) {
        camionRepository.saveAll(camions);
        chargeRepository.saveAll(charges);
    }

    /** Marque le camion vendu : appele par VenteService a la validation de la vente. */
    @Transactional
    public void marquerVenduInterne(CamionMinerai camion) {
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException(
                "Le camion " + camion.getPlaque() + " (" + camion.getDateAchat() + ") est deja vendu.");
        }
        camion.setStatut(StatutCamionMinerai.VENDU);
        camionRepository.save(camion);
    }

    /** Remet le camion en stock : appele a l'annulation d'une vente. */
    @Transactional
    public void remettreEnStockInterne(CamionMinerai camion) {
        camion.setStatut(StatutCamionMinerai.EN_STOCK);
        camionRepository.save(camion);
    }

    private EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit,
                                        BigDecimal credit, String libelle, java.time.LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .dateEcriture(date)
            .build();
    }
}
