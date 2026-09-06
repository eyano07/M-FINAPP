package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.ChargeCamionMinerai;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.ModeleChargeMinerai;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import com.mbsc.finapp.dto.logistique.CamionMineraiRequest;
import com.mbsc.finapp.dto.logistique.CamionMineraiResponse;
import com.mbsc.finapp.dto.logistique.ChargeCamionRequest;
import com.mbsc.finapp.dto.logistique.ChargeCamionResponse;
import com.mbsc.finapp.dto.logistique.DupliquerCamionRequest;
import com.mbsc.finapp.dto.logistique.ModeleChargeResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.CamionMineraiRepository;
import com.mbsc.finapp.repository.ModeleChargeMineraiRepository;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li><b>Reception</b> (logistique) — constate l'arrivee physique, pas un
 *       achat : aucune ecriture, aucune entree en stock. Voir
 *       {@link #receptionner}.</li>
 *   <li><b>Frais accessoires</b> (logistique) — transport, pont bascule,
 *       peage, documents : <b>D 61x/62x / C 4011</b> puis <b>D 311x /
 *       C 6031</b>. Ils entrent dans le cout d'acquisition, pas dans les
 *       charges de la periode. Saisissables des la reception (le camion peut
 *       etre A_VALIDER), tout comme les frais standards du minerais ({@link
 *       ModeleChargeMinerai}, rejoues automatiquement a chaque reception) :
 *       sans stock a incorporer, la charge reste alors en attente et n'est
 *       postee qu'a la validation de l'achat. Voir {@link #ajouterCharge} et
 *       {@link #receptionner}.</li>
 *   <li><b>Validation de l'achat et reglement</b> (logistique -> DFIN -> DA
 *       -> DFIN -> tresorerie) — la logistique selectionne les camions a
 *       payer (valides ou non) et cree une note de reglement ({@code
 *       NoteFraisService.creerReglementCamionsMinerai}), soumise directement
 *       au DFIN. Son paiement par la caisse, au bout du circuit, fait tout
 *       en un geste pour un camion encore A_VALIDER : <b>D 601x Achats hors
 *       taxes (+ D 4452 TVA recuperable si soumis) / C 4011 Fournisseurs
 *       TTC</b> (journal ACHATS) et <b>D 311x Stock / C 6031 Variation</b>
 *       (journal STOCK) constatent l'achat ({@link #validerAchatInterne}),
 *       puis <b>D 4011 / C 571 Caisse</b> solde aussitot la dette — sans
 *       toucher au stock ni au resultat. Un camion deja valide (dette
 *       existante) ne fait que se solder. Voir {@link
 *       #finaliserReglementNoteInterne}.</li>
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
    /** Frais standards d'un minerais, rejoues a chaque reception. */
    private final ModeleChargeMineraiRepository modeleRepository;
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
            ? camionRepository.findAllByOrderByDateReceptionDescIdDesc()
            : camionRepository.findByArticleIdOrderByDateReceptionDescIdDesc(articleId);
        // Une seule requete groupee pour le total des frais connexes de
        // chaque camion (postes ou en attente), plutot qu'une par camion.
        Map<Long, BigDecimal> totauxFrais = camions.isEmpty() ? Map.of() : chargeRepository
            .sommerParCamionIds(camions.stream().map(CamionMinerai::getId).toList()).stream()
            .collect(Collectors.toMap(
                ChargeCamionMineraiRepository.TotalFraisParCamion::getCamionId,
                ChargeCamionMineraiRepository.TotalFraisParCamion::getTotal));
        return camions.stream()
            .map(c -> CamionMineraiResponse.from(c, totauxFrais.get(c.getId()), detteEstimee(c)))
            .toList();
    }

    /**
     * Dette fournisseur reelle (achat deja valide : {@link
     * CamionMinerai#detteFournisseur()}) ou, pour un camion encore
     * A_VALIDER, estimee TTC au taux de TVA du jour de reception — pour que
     * le montant affiche a la logistique corresponde a ce qui sera reellement
     * constate si ce camion est inclus dans une note de reglement (voir
     * {@code NoteFraisService.creerReglementCamionsMinerai}).
     */
    private BigDecimal detteEstimee(CamionMinerai c) {
        if (c.getStatut() != StatutCamionMinerai.A_VALIDER) {
            return c.detteFournisseur();
        }
        BigDecimal prixHt = c.getPrixAchat();
        if (!c.getArticle().isSoumisTva()) {
            return prixHt;
        }
        BigDecimal tva = prixHt.multiply(tauxTvaService.tauxALaDate(c.getDateReception()))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return prixHt.add(tva);
    }

    /** Camions encore en stock d'un minerais : ce que le caissier peut vendre. */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CamionMineraiResponse> listerDisponibles(Long articleId) {
        return camionRepository
            .findByArticleIdAndStatutOrderByDateReceptionAscIdAsc(articleId, StatutCamionMinerai.EN_STOCK)
            .stream().map(CamionMineraiResponse::from).toList();
    }

    /**
     * Camions dont la dette fournisseur reste a solder : ce que le caissier
     * peut regler directement (dette deja constatee, camion EN_STOCK ou
     * VENDU), ou que la logistique peut inclure dans une nouvelle note de
     * reglement — y compris un camion encore A_VALIDER, dont l'achat sera
     * constate au paiement de cette note (voir {@code
     * NoteFraisService.creerReglementCamionsMinerai}). Un camion deja
     * rattache a une note de reglement en cours est exclu dans tous les cas.
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'LOGISTIQUE', 'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<CamionMineraiResponse> listerARegler() {
        return camionRepository
            .findByRegleFalseAndNoteFraisReglementIsNullOrderByDateReceptionAscIdAsc()
            .stream().map(c -> CamionMineraiResponse.from(c, BigDecimal.ZERO, detteEstimee(c))).toList();
    }

    // ---------------------------------------------------------------------
    // Reception (logistique)
    // ---------------------------------------------------------------------

    /**
     * Receptionne un chargement : constate son ARRIVEE PHYSIQUE, pas un achat.
     * Le camion nait {@link StatutCamionMinerai#A_VALIDER} — aucune ecriture,
     * aucune entree en stock. Les frais standards du minerais ({@link
     * ModeleChargeMinerai}) sont mis en attente des cet instant, comme tout
     * frais saisi a la main sur un camion pas encore valide : sans cela, le
     * libelle « reappliques a chaque reception » serait faux, et le camion
     * resterait sans aucun frais visible jusqu'a la validation. Voir {@link
     * #posterCharge}, qui les poste reellement des que le caissier valide
     * l'achat (voir {@link #validerAchatInterne}).
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public CamionMineraiResponse receptionner(CamionMineraiRequest req) {
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
        if (camionRepository.existsByArticleIdAndPlaqueIgnoreCaseAndDateReception(
                article.getId(), plaque, req.dateReception())) {
            throw new IllegalArgumentException(
                "Le camion " + plaque + " est deja receptionne pour ce minerais au " + req.dateReception() + ".");
        }
        Entrepot entrepot = stockService.entrepotParIdInterne(req.entrepotId());
        BigDecimal prixPropose = req.prixAchat().setScale(2, RoundingMode.HALF_UP);

        CamionMinerai camion = camionRepository.save(CamionMinerai.builder()
            .article(article)
            .entrepot(entrepot)
            .plaque(plaque)
            .dateReception(req.dateReception())
            .prixAchat(prixPropose)
            .montantTva(BigDecimal.ZERO)
            // Affichage indicatif avant validation : ni ecriture ni stock reels
            // ne dependent encore de ce montant.
            .coutAcquisition(prixPropose)
            .statut(StatutCamionMinerai.A_VALIDER)
            .regle(false)
            .build());

        // Frais standards du minerais : mis en attente des maintenant (voir
        // incorporerCharge, qui ne poste rien tant que le camion est
        // A_VALIDER) — postes reellement a la validation par la caisse.
        List<ModeleChargeMinerai> modeles = modeleRepository.findByArticleIdOrderByLibelleAsc(article.getId());
        for (ModeleChargeMinerai modele : modeles) {
            incorporerCharge(camion, new ChargeCamionRequest(
                modele.getLibelle(),
                modele.getCompteCharge().getNumero(),
                modele.getMontant(),
                camion.getDateReception(),
                false));
        }

        log.info("Camion de minerais receptionne, en attente de validation [plaque={}, article={}, prix propose={}, fraisStandards={}]",
            plaque, article.getCode(), prixPropose, modeles.size());
        return CamionMineraiResponse.from(camion);
    }

    /**
     * Duplique un camion deja receptionne : meme minerais, meme entrepot,
     * meme prix propose — seule la plaque, demandee au guichet, change.
     * Raccourci pour un arrivage de plusieurs camions identiques du meme
     * projet, plutot que ressaisir le formulaire complet a chaque camion.
     *
     * <p>Les frais standards du minerais s'appliquent d'eux-memes, comme pour
     * toute reception (voir {@link #receptionner}). Les frais <em>ad hoc</em>
     * du camion source — ceux qui ne correspondent a aucun standard — sont en
     * plus reproduits a l'identique : sans cela, dupliquer un camion qui a
     * son propre frais particulier (ex. une panne reparee en route) perdrait
     * cette information.</p>
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public CamionMineraiResponse dupliquerDepuis(Long camionSourceId, DupliquerCamionRequest req) {
        CamionMinerai source = charger(camionSourceId);
        CamionMineraiResponse cree = receptionner(new CamionMineraiRequest(
            source.getArticle().getId(),
            source.getEntrepot().getId(),
            req.plaque(),
            LocalDate.now(),
            source.getPrixAchat()));
        CamionMinerai nouveau = charger(cree.id());

        Set<String> libellesStandards = modeleRepository
            .findByArticleIdOrderByLibelleAsc(source.getArticle().getId()).stream()
            .map(m -> m.getLibelle().trim().toLowerCase())
            .collect(Collectors.toSet());
        List<ChargeCamionMinerai> chargesSource = chargeRepository
            .findByCamionIdOrderByDateChargeAscIdAsc(source.getId());
        int fraisAdHocRepris = 0;
        for (ChargeCamionMinerai chargeSource : chargesSource) {
            // Deja rejoue automatiquement par receptionner() ci-dessus : le
            // reprendre aussi ici doublerait le montant.
            if (libellesStandards.contains(chargeSource.getLibelle().trim().toLowerCase())) {
                continue;
            }
            incorporerCharge(nouveau, new ChargeCamionRequest(
                chargeSource.getLibelle(),
                chargeSource.getCompteCharge().getNumero(),
                chargeSource.getMontant(),
                LocalDate.now(),
                false));
            fraisAdHocRepris++;
        }

        log.info("Camion de minerais duplique [source={}, nouveau={}, fraisAdHocRepris={}]",
            source.getPlaque(), nouveau.getPlaque(), fraisAdHocRepris);
        return CamionMineraiResponse.from(nouveau);
    }

    /**
     * Valide l'achat d'un camion : constate la depense et fait entrer la
     * marchandise en stock. Declenchee par le paiement de la note de
     * reglement qui couvre ce camion (voir {@code
     * NoteFraisService.creerReglementCamionsMinerai} et {@link
     * #finaliserReglementNoteInterne}) — plus par un geste isole de la
     * caisse : le prix engage et sa validation passent tous deux desormais
     * par le circuit logistique -> DFIN -> DA -> DFIN -> tresorerie.
     *
     * <ol>
     *   <li><b>D 601x Achats hors taxes (+ D 4452 TVA recuperable si le
     *       minerais y est soumis) / C 4011 Fournisseurs toutes taxes
     *       comprises</b> (journal ACHATS) — la dette nait ici, pas a la
     *       reception.</li>
     *   <li><b>D 311x Stock / C 6031 Variation</b> (journal STOCK) pour le
     *       seul montant hors taxes — la TVA recuperable n'est jamais un
     *       element du cout d'acquisition.</li>
     *   <li>Tout frais connexe en attente sur ce camion — ad hoc ou issu des
     *       frais standards du minerais ({@link ModeleChargeMinerai}), mis en
     *       attente des la reception, voir {@link #receptionner} — est poste
     *       a son tour : impossible avant, faute de stock a incorporer.</li>
     * </ol>
     *
     * <p>Le prix constate est celui propose par la logistique a la reception
     * ({@link CamionMinerai#getPrixAchat()}) : rien, dans ce circuit, ne
     * permet de le corriger apres coup. La date, elle, reste celle de la
     * reception — meme convention que {@code VenteService} (date de la
     * vente, pas de sa validation).</p>
     */
    private void validerAchatInterne(CamionMinerai camion, User auteur) {
        if (camion.getStatut() != StatutCamionMinerai.A_VALIDER) {
            throw new IllegalStateException(
                "Le camion " + camion.designation() + " est deja " + camion.getStatut() + " : rien a valider.");
        }
        Article article = camion.getArticle();
        String libelle = "Achat minerais " + article.getLibelle() + " - camion " + camion.getPlaque();

        BigDecimal prixHt = camion.getPrixAchat();
        BigDecimal tva = article.isSoumisTva()
            ? prixHt.multiply(tauxTvaService.tauxALaDate(camion.getDateReception()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // 1. Achat a credit : D 601x HT (+ D 4452 TVA) / C 4011 TTC.
        List<EcritureGrandLivre> achat = new ArrayList<>();
        achat.add(ecriture(article.getCompteAchat(), prixHt, BigDecimal.ZERO, libelle, camion.getDateReception()));
        if (tva.signum() > 0) {
            achat.add(ecriture(comptabilite.compteParNumero(COMPTE_TVA_RECUPERABLE),
                tva, BigDecimal.ZERO, libelle, camion.getDateReception()));
        }
        achat.add(ecriture(comptabilite.compteParNumero(COMPTE_FOURNISSEURS),
            BigDecimal.ZERO, prixHt.add(tva), libelle, camion.getDateReception()));
        PieceComptable pieceAchat = comptabiliteService.creerPieceInterne(
            JournalComptable.ACHATS, libelle, camion.getDateReception(), achat, auteur);

        // 2. Entree en stock : D 311x / C 6031.
        MouvementStock mouvement = stockService.enregistrerEntreeMineraiInterne(
            camion.getDateReception(), libelle, article, camion.getEntrepot(), prixHt, auteur);

        camion.setMontantTva(tva);
        camion.setCoutAcquisition(prixHt);
        camion.setStatut(StatutCamionMinerai.EN_STOCK);
        camion.setMouvement(mouvement);
        camion.setPieceReception(pieceAchat);
        camionRepository.save(camion);

        // 3. Tout frais connexe en attente sur ce camion — saisi a la main ou
        // issu des frais standards du minerais, mis en attente des la
        // reception (voir receptionner) — est poste maintenant que le stock
        // existe.
        List<ChargeCamionMinerai> chargesEnAttente = chargeRepository.findByCamionIdAndPieceIsNull(camion.getId());
        for (ChargeCamionMinerai chargeEnAttente : chargesEnAttente) {
            posterCharge(camion, chargeEnAttente, auteur);
        }

        log.info("Achat de camion minerais valide [plaque={}, article={}, ht={}, tva={}, chargesEnAttente={}, par={}]",
            camion.getPlaque(), article.getCode(), prixHt, tva, chargesEnAttente.size(), auteur.getEmail());
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
     * <p>Un camion encore A_VALIDER n'a pas de stock a incorporer : la charge
     * est quand meme enregistree (la logistique n'a pas a attendre la caisse
     * pour constater qu'elle a paye un peage ou un pont bascule) mais reste
     * <em>en attente</em>, sans piece — voir {@link #posterCharge}, rejoue par
     * {@link #validerAchatInterne} des que le stock existe.</p>
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
        // Le frais devient un standard du minerais : il sera rejoue a chaque
        // reception suivante (voir receptionner). Sans cela, cocher la case
        // n'aurait valu que pour les camions deja en stock a cet instant, et
        // un chargement receptionne le lendemain repartait sans frais.
        enregistrerModele(camion.getArticle(), req);

        // Saisie groupee : le meme frais sur chaque camion encore en stock du
        // minerais, PLUS le camion actuellement ouvert meme s'il n'y est pas
        // encore (A_VALIDER) — sinon cocher la case sur le tout premier
        // camion d'un minerais n'aurait aucun effet visible avant sa propre
        // validation. Chacun recoit sa propre ligne et ses propres
        // ecritures — elles restent donc modifiables camion par camion
        // ensuite. Aucun camion en stock n'est pas une erreur : le standard
        // vient d'etre enregistre, il jouera sur les prochaines receptions.
        List<CamionMinerai> cibles = new ArrayList<>(camionRepository
            .findByArticleIdAndStatutOrderByDateReceptionAscIdAsc(
                camion.getArticle().getId(), StatutCamionMinerai.EN_STOCK));
        if (cibles.stream().noneMatch(c -> c.getId().equals(camion.getId()))) {
            cibles.add(0, camion);
        }
        List<ChargeCamionResponse> creees = new ArrayList<>();
        for (CamionMinerai cible : cibles) {
            creees.add(incorporerCharge(cible, req));
        }
        log.info("Frais « {} » applique a {} camion(s) de {}",
            req.libelle(), creees.size(), camion.getArticle().getLibelle());
        return creees;
    }

    /** Cree ou met a jour le frais standard du minerais (une seule ligne par nature). */
    private void enregistrerModele(Article article, ChargeCamionRequest req) {
        String libelle = req.libelle().trim();
        ModeleChargeMinerai modele = modeleRepository
            .findByArticleIdAndLibelleIgnoreCase(article.getId(), libelle)
            .orElseGet(() -> ModeleChargeMinerai.builder().article(article).libelle(libelle).build());
        modele.setCompteCharge(comptabilite.compteParNumero(req.compteChargeNumero()));
        modele.setMontant(req.montant().setScale(2, RoundingMode.HALF_UP));
        modeleRepository.save(modele);
    }

    /** Frais standards d'un minerais, rejoues a chaque reception. */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<ModeleChargeResponse> listerModeles(Long articleId) {
        return modeleRepository.findByArticleIdOrderByLibelleAsc(articleId).stream()
            .map(ModeleChargeResponse::from)
            .toList();
    }

    /**
     * Retire un frais des standards du minerais. Les lignes deja creees sur
     * les camions ne bougent pas : seules les receptions futures cessent de
     * le rejouer.
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'DFIN', 'ADMIN')")
    @Transactional
    public void supprimerModele(Long modeleId) {
        ModeleChargeMinerai modele = modeleRepository.findById(modeleId)
            .orElseThrow(() -> RessourceIntrouvableException.of("ModeleChargeMinerai", modeleId));
        modeleRepository.delete(modele);
        log.info("Frais standard retire [minerais={}, libelle={}]",
            modele.getArticle().getLibelle(), modele.getLibelle());
    }

    /**
     * Enregistre un frais accessoire pour UN camion. Postee tout de suite si
     * le camion est EN_STOCK, mise en attente s'il est encore A_VALIDER (voir
     * {@link #posterCharge}), refusee s'il est VENDU.
     */
    private ChargeCamionResponse incorporerCharge(CamionMinerai camion, ChargeCamionRequest req) {
        User auteur = currentUser.requireUser();
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException(
                "Le camion " + camion.designation() + " est vendu : son cout d'acquisition est fige."
                + " Une depense posterieure releve des charges de la periode.");
        }
        CompteOHADA compteCharge = comptabilite.compteParNumero(req.compteChargeNumero());
        BigDecimal montant = req.montant().setScale(2, RoundingMode.HALF_UP);

        ChargeCamionMinerai charge = chargeRepository.save(ChargeCamionMinerai.builder()
            .camion(camion)
            .libelle(req.libelle().trim())
            .compteCharge(compteCharge)
            .montant(montant)
            .dateCharge(req.dateCharge())
            .regle(false)
            .build());

        if (camion.getStatut() == StatutCamionMinerai.A_VALIDER) {
            log.info("Charge connexe mise en attente [camion={}, libelle={}, montant={}] : "
                + "sera incorporee a la validation de l'achat.", camion.getPlaque(), req.libelle(), montant);
            return ChargeCamionResponse.from(charge);
        }

        posterCharge(camion, charge, auteur);
        return ChargeCamionResponse.from(charge);
    }

    /**
     * Poste les deux ecritures d'un frais accessoire deja enregistre (charge
     * par nature D 61x/62x / C 4011, puis incorporation au stock D 311x /
     * C 6031) et met a jour le cout d'acquisition du camion. Appelee tout de
     * suite pour une charge saisie sur un camion EN_STOCK, ou rejouee par
     * {@link #validerAchatInterne} pour les charges restees en attente
     * pendant que le camion etait A_VALIDER.
     */
    private void posterCharge(CamionMinerai camion, ChargeCamionMinerai charge, User auteur) {
        Article article = camion.getArticle();
        BigDecimal montant = charge.getMontant();
        LocalDate date = charge.getDateCharge();
        String libelle = charge.getLibelle() + " - camion " + camion.getPlaque();

        // 1. Charge par nature : D 61x/62x / C 4011.
        PieceComptable pieceNature = comptabiliteService.creerPieceInterne(
            JournalComptable.ACHATS, libelle, date,
            List.of(
                ecriture(charge.getCompteCharge(), montant, BigDecimal.ZERO, libelle, date),
                ecriture(comptabilite.compteParNumero(COMPTE_FOURNISSEURS),
                    BigDecimal.ZERO, montant, libelle, date)),
            auteur);

        // 2. Incorporation au stock : D 311x / C 6031.
        PieceComptable pieceIncorporation = comptabiliteService.creerPieceInterne(
            JournalComptable.STOCK, "Incorporation au stock - " + libelle, date,
            List.of(
                ecriture(article.getCompteStock(), montant, BigDecimal.ZERO, libelle, date),
                ecriture(article.getCompteCharge(), BigDecimal.ZERO, montant, libelle, date)),
            auteur);

        // La quantite ne change pas : seule la valorisation monte.
        stockService.ajusterValeurStockInterne(article, camion.getEntrepot(), montant, date);
        camion.setCoutAcquisition(camion.getCoutAcquisition().add(montant));
        camionRepository.save(camion);

        charge.setPiece(pieceNature);
        charge.setPieceIncorporation(pieceIncorporation);
        chargeRepository.save(charge);

        log.info("Charge connexe incorporee [camion={}, libelle={}, montant={}, cout={}]",
            camion.getPlaque(), charge.getLibelle(), montant, camion.getCoutAcquisition());
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

        // Une charge encore en attente (camion A_VALIDER au moment de la
        // saisie) n'a jamais ete postee : rien a extourner, rien a retirer du
        // cout d'acquisition ou de la valorisation du stock.
        boolean posee = charge.getPiece() != null;
        if (charge.getPiece() != null) {
            comptabiliteService.annulerInterne(charge.getPiece().getId(), auteur);
        }
        if (charge.getPieceIncorporation() != null) {
            comptabiliteService.annulerInterne(charge.getPieceIncorporation().getId(), auteur);
        }
        if (posee) {
            stockService.ajusterValeurStockInterne(camion.getArticle(), camion.getEntrepot(),
                charge.getMontant().negate(), charge.getDateCharge());
            camion.setCoutAcquisition(camion.getCoutAcquisition().subtract(charge.getMontant()));
            camionRepository.save(camion);
        }
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
        // Une note de reglement en cours a ete calculee sur la dette de ce
        // camion : le supprimer extournerait cette dette sans toucher a la
        // note, qui serait alors payee pour un montant qui ne correspond
        // plus a rien.
        if (camion.getNoteFraisReglement() != null) {
            throw new IllegalStateException(
                "Le camion " + camion.getPlaque() + " a une note de reglement en cours ("
                + camion.getNoteFraisReglement().getReference() + ") : annulez-la d'abord.");
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

    /**
     * Finalise le reglement d'une note de camions minerais, une fois celle-ci
     * payee — pendant du reglement direct en caisse ({@link
     * #marquerReglesInterne}), pour le circuit DFIN/DA/Tresorerie. Un camion
     * encore A_VALIDER voit d'abord son achat constate ({@link
     * #validerAchatInterne}) — la validation et le paiement n'y font plus
     * qu'un — puis, comme tout camion rattache, est marque regle. Appele par
     * {@code CaisseService.payerNote}.
     */
    @Transactional
    public void finaliserReglementNoteInterne(Long noteFraisId, TransactionCaisse transaction, User auteur) {
        List<CamionMinerai> camions = camionRepository.findByNoteFraisReglementId(noteFraisId);
        for (CamionMinerai camion : camions) {
            if (camion.getStatut() == StatutCamionMinerai.A_VALIDER) {
                validerAchatInterne(camion, auteur);
            }
            camion.setRegle(true);
            camion.setTransactionReglement(transaction);
        }
        camionRepository.saveAll(camions);
    }

    /** Marque le camion vendu : appele par VenteService a la validation de la vente. */
    @Transactional
    public void marquerVenduInterne(CamionMinerai camion) {
        if (camion.getStatut() == StatutCamionMinerai.VENDU) {
            throw new IllegalStateException(
                "Le camion " + camion.getPlaque() + " (" + camion.getDateReception() + ") est deja vendu.");
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
