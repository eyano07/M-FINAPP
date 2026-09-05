package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.*;
import com.mbsc.finapp.domain.enums.*;
import com.mbsc.finapp.dto.vente.LigneVenteRequest;
import com.mbsc.finapp.dto.vente.VenteRequest;
import com.mbsc.finapp.dto.vente.VenteResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.dto.vente.ReglerCreanceRequest;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.VenteRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module Vente : marchandises (avec sortie de stock) et services.
 *
 * <p>Une vente validee produit jusqu'a deux pieces comptables :</p>
 * <ul>
 *   <li>journal VENTES — debit de la contrepartie (client si vente a
 *       credit, compte de tresorerie si vente au comptant) pour le TTC,
 *       credit des comptes de produits pour le HT et du compte
 *       {@value #COMPTE_TVA_FACTUREE} pour la TVA collectee ;</li>
 *   <li>journal STOCK — cout des ventes au CMP, produit par
 *       {@link StockService} pour les seules lignes de marchandises.</li>
 * </ul>
 *
 * <p>Le taux de TVA est celui en vigueur a la date de la vente ; il est
 * fige sur la vente au moment de la validation.</p>
 */
@Service
@RequiredArgsConstructor
public class VenteService {

    private static final Logger log = LoggerFactory.getLogger(VenteService.class);

    /** Compte OHADA "TVA facturee" (PASSIF). */
    public static final String COMPTE_TVA_FACTUREE = "4431";
    /** Compte OHADA "Clients locaux" : contrepartie d'une vente a credit. */
    public static final String COMPTE_CLIENTS = "4111";
    /** Compte OHADA "Caisse" : contrepartie d'une vente reglee en especes. */
    public static final String COMPTE_CAISSE = "571";
    private static final String COMPTE_PERTE_CHANGE = "676";
    private static final String COMPTE_GAIN_CHANGE = "776";

    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    private final VenteRepository venteRepository;
    private final ArticleRepository articleRepository;
    private final EntrepotRepository entrepotRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final StockService stockService;
    // Consigne des emballages : la vente d'une boisson alimente le stock de
    // bouteilles vides. Dependance a sens unique (RestaurantService ne connait
    // pas VenteService), donc aucun cycle.
    private final RestaurantService restaurantService;
    /** Suivi camion par camion des minerais : identite du chargement et statut de vente. */
    private final MineraiService mineraiService;
    private final TauxTvaService tauxTvaService;
    private final ClientService clientService;
    private final EtablissementTresorerieService etablissements;
    private final ConversionDeviseService conversionDevise;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final EcritureGrandLivreRepository ecritureRepository;

    // ---------------------------------------------------------------------
    // Consultation
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<VenteResponse> lister() {
        return venteRepository.findAllPourListe().stream()
            .map(v -> VenteResponse.from(v, false))
            .toList();
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public VenteResponse consulter(Long id) {
        return VenteResponse.from(charger(id));
    }

    // ---------------------------------------------------------------------
    // Saisie
    // ---------------------------------------------------------------------

    /** Enregistre la vente en BROUILLON : aucune ecriture, aucun mouvement de stock. */
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public VenteResponse creer(VenteRequest req) {
        User auteur = currentUser.requireUser();
        LocalDate date = req.dateVente() != null ? req.dateVente() : LocalDate.now();
        Devise devise = req.devise() != null ? req.devise() : ConversionDeviseService.DEVISE_BASE;

        // Taux en vigueur a la DATE DE LA VENTE (et non taux du jour) : une
        // vente antidatee doit porter la contre-valeur de sa propre date.
        // Ce taux n'est qu'indicatif tant que la vente est en brouillon ; il
        // est refige a la validation, seul moment ou la vente devient une
        // ecriture (meme convention que ComptabiliteService.comptabiliser).
        BigDecimal tauxDuJour = conversionDevise.tauxALaDate(date);
        if (devise != ConversionDeviseService.DEVISE_BASE && (tauxDuJour == null || tauxDuJour.signum() <= 0)) {
            throw new IllegalStateException(
                "Aucun taux de change valide n'est defini : impossible d'enregistrer une vente en "
                + devise + ". Demandez a un administrateur d'enregistrer le taux du jour.");
        }

        Vente vente = Vente.builder()
            .reference(referenceGenerator.pourVente())
            .dateVente(date)
            .client(req.clientId() == null ? null : clientService.requireClient(req.clientId()))
            .clientNom(req.clientNom())
            .statut(StatutVente.BROUILLON)
            .modeReglement(req.modeReglement())
            .devise(devise)
            .tauxJournalier(tauxDuJour)
            .createdBy(auteur)
            .build();

        boolean contientMarchandise = false;
        int ordre = 1;
        for (LigneVenteRequest l : req.lignes()) {
            Article article = articleRepository.findById(l.articleId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Article", l.articleId()));
            exigerVendable(article);

            BigDecimal prix = l.prixUnitaire();
            contientMarchandise |= article.getType().estStocke();

            // Minerais : la ligne cede UN camion identifie, a son propre prix.
            // Le camion vaut une unite de l'article, d'ou la quantite forcee a
            // 1 : accepter une quantite libre ferait sortir du stock plus (ou
            // moins) que le chargement reellement cede.
            CamionMinerai camion = null;
            String designation = article.getLibelle();
            BigDecimal quantite = l.quantite();
            if (article.isMinerais()) {
                if (l.camionId() == null) {
                    throw new IllegalArgumentException(
                        "\"" + article.getLibelle() + "\" est un minerais : precisez le camion vendu.");
                }
                camion = mineraiService.charger(l.camionId());
                if (!camion.getArticle().getId().equals(article.getId())) {
                    throw new IllegalArgumentException(
                        "Le camion " + camion.getPlaque() + " ne porte pas \"" + article.getLibelle() + "\".");
                }
                if (camion.getStatut() != StatutCamionMinerai.EN_STOCK) {
                    throw new IllegalArgumentException(
                        "Le camion " + camion.designation() + " n'est plus en stock.");
                }
                designation = article.getLibelle() + " - camion " + camion.getPlaque();
                quantite = BigDecimal.ONE;
            } else if (l.camionId() != null) {
                throw new IllegalArgumentException(
                    "\"" + article.getLibelle() + "\" n'est pas un minerais : aucun camion ne peut lui etre rattache.");
            }

            vente.addLigne(LigneVente.builder()
                .article(article)
                .camion(camion)
                .designation(designation)
                .quantite(quantite)
                .prixUnitaire(prix.setScale(2, RoundingMode.HALF_UP))
                .soumisTva(article.isSoumisTva())
                .ordre(ordre++)
                .build());
        }

        // Contreparties : trésorerie pour un comptant, entrepôt pour une marchandise.
        if (req.modeReglement() == ModeReglement.BANQUE || req.modeReglement() == ModeReglement.MOBILE_MONEY) {
            if (req.etablissementId() == null) {
                throw new IllegalArgumentException(
                    "Un reglement par " + libelleCanal(req.modeReglement()) + " exige de preciser l'etablissement.");
            }
            vente.setEtablissement(etablissements.requireEtablissement(
                req.etablissementId(), typeEtablissement(req.modeReglement())));
        }
        if (contientMarchandise) {
            if (req.entrepotId() == null) {
                throw new IllegalArgumentException(
                    "Une vente de marchandises exige de preciser l'entrepot de sortie.");
            }
            vente.setEntrepot(entrepotRepository.findById(req.entrepotId())
                .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", req.entrepotId())));
        }

        // Totaux indicatifs tant que la vente est en brouillon ; ils sont
        // recalculés (et figés avec le taux) à la validation.
        calculerTotaux(vente, tauxTvaService.tauxALaDate(date));

        Vente saved = venteRepository.save(vente);
        log.info("Vente creee [ref={}, client={}, lignes={}]",
            saved.getReference(), saved.designationClient(), saved.getLignes().size());
        return VenteResponse.from(saved);
    }

    // ---------------------------------------------------------------------
    // Validation : le seul endroit qui produit des effets comptables
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public VenteResponse valider(Long id) {
        Vente vente = charger(id);
        if (vente.getStatut() != StatutVente.BROUILLON) {
            throw new TransitionInvalideException(
                "Seule une vente BROUILLON peut etre validee (etat actuel : " + vente.getStatut() + ")");
        }
        User auteur = currentUser.requireUser();

        // 1. Taux de TVA en vigueur à la date de la vente, figé pour l'audit.
        BigDecimal taux = tauxTvaService.tauxALaDate(vente.getDateVente());
        vente.setTauxTvaApplique(taux);
        calculerTotaux(vente, taux);

        // 1 bis. Taux de change refigé au moment de la validation, à la date
        // de la vente. C'est la validation qui produit les écritures : un
        // brouillon vieux de plusieurs semaines ne doit pas être comptabilisé
        // au taux de sa saisie.
        BigDecimal tauxChange = conversionDevise.tauxALaDate(vente.getDateVente());
        if (vente.getDevise() != ConversionDeviseService.DEVISE_BASE
            && (tauxChange == null || tauxChange.signum() <= 0)) {
            throw new IllegalStateException(
                "Aucun taux de change applicable au " + vente.getDateVente()
                + " : impossible de comptabiliser une vente en " + vente.getDevise() + ".");
        }
        vente.setTauxJournalier(tauxChange);

        if (vente.getTotalTtc().signum() <= 0) {
            throw new IllegalArgumentException("Le montant total de la vente doit etre strictement positif.");
        }

        // 2. Sortie de stock des articles stockés : décrément au CMP + pièce
        //    STOCK (coût des ventes). La garde de stock insuffisant s'applique
        //    ici. Le critère est estStocke() et non le seul type MARCHANDISE :
        //    plats et boissons du module Restaurant doivent sortir du stock au
        //    même titre, faute de quoi la vente créditerait le produit sans
        //    jamais constater le coût (voir TypeArticle.estStocke).
        //    Un camion de minerais sort a SON cout d'acquisition (prix d'achat
        //    + frais accessoires incorpores) et non au CMP : des chargements de
        //    teneurs et de frais de route differents ne sont pas
        //    interchangeables — identification specifique, voir MineraiService.
        List<StockService.SortieVente> sorties = vente.getLignes().stream()
            .filter(l -> l.getArticle().getType().estStocke())
            .map(l -> new StockService.SortieVente(l.getArticle(), l.getQuantite(),
                l.getCamion() == null ? null : l.getCamion().getCoutAcquisition()))
            .toList();
        if (!sorties.isEmpty()) {
            MouvementStock mouvement = stockService.enregistrerSortieVenteInterne(
                vente.getDateVente(),
                "Sortie sur vente " + vente.getReference() + " - " + vente.designationClient(),
                vente.getEntrepot(), sorties, auteur);
            vente.setMouvement(mouvement);
        }

        // 2 bis. Minerais : chaque camion cédé passe à VENDU, ce qui empêche de
        //        le vendre deux fois et fige son coût d'acquisition (plus aucun
        //        frais accessoire ne peut lui être incorporé après coup).
        for (LigneVente ligne : vente.getLignes()) {
            if (ligne.getCamion() != null) {
                mineraiService.marquerVenduInterne(ligne.getCamion());
            }
        }

        // 2 ter. Consigne : chaque bouteille vendue revient en stock de vides
        //        (module Restaurant). Sans effet si la boisson n'a pas de
        //        conditionnement défini, et aucune écriture comptable.
        restaurantService.enregistrerVidesSurVenteInterne(vente, auteur);

        // 3. Pièce du journal VENTES.
        vente.setPiece(comptabilite.creerPieceInterne(
            JournalComptable.VENTES,
            "Vente " + vente.getReference() + " - " + vente.designationClient(),
            vente.getDateVente(),
            construireEcrituresVente(vente),
            auteur));

        vente.setStatut(StatutVente.VALIDEE);
        log.info("Vente validee [ref={}, TTC={}, TVA={}, mode={}]",
            vente.getReference(), vente.getTotalTtc(), vente.getTotalTva(), vente.getModeReglement());
        return VenteResponse.from(vente);
    }

    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public VenteResponse annuler(Long id) {
        Vente vente = charger(id);
        if (vente.getStatut() != StatutVente.VALIDEE) {
            throw new TransitionInvalideException(
                "Seule une vente VALIDEE peut etre annulee (etat actuel : " + vente.getStatut() + ")");
        }
        User auteur = currentUser.requireUser();

        if (vente.getPiece() != null) {
            comptabilite.annulerInterne(vente.getPiece().getId(), auteur);
        }
        stockService.annulerMouvementInterne(vente.getMouvement(), auteur);
        // Miroir de l'étape 2 bis de valider() : les camions cédés reviennent
        // en stock et redeviennent vendables.
        for (LigneVente ligne : vente.getLignes()) {
            if (ligne.getCamion() != null) {
                mineraiService.remettreEnStockInterne(ligne.getCamion());
            }
        }
        // Miroir de l'étape 2 ter de valider() : on reprend les bouteilles
        // vides que cette vente avait fait entrer.
        restaurantService.annulerVidesSurVenteInterne(vente, auteur);

        vente.setStatut(StatutVente.ANNULEE);
        log.info("Vente annulee [ref={}]", vente.getReference());
        return VenteResponse.from(vente);
    }

    /**
     * Encaisse la creance d'une vente CREDIT et la solde (audit du
     * 18/08/2026, A-04/A-05). Comme le paiement d'une note de frais, un seul
     * reglement solde la creance en totalite — pas de reglement partiel.
     *
     * <p>Le compte client (4111) est credite du montant exact qui y avait ete
     * debite a la vente, lu directement sur la piece d'origine plutot que
     * recalcule (aucune derive d'arrondi possible). La tresorerie est debitee
     * de la contre-valeur reellement encaissee, reconvertie au taux du jour
     * du reglement si la vente est en devise etrangere. La difference entre
     * les deux est un ecart de change realise, porte en 676/776 — le meme
     * principe que {@link EcartChangeService}, applique ici a une creance
     * plutot qu'a une charge.</p>
     */
    @PreAuthorize("hasAnyRole('CAISSIER', 'ADMIN')")
    @Transactional
    public VenteResponse reglerCreance(Long id, ReglerCreanceRequest req) {
        Vente vente = charger(id);
        if (vente.getStatut() != StatutVente.VALIDEE) {
            throw new TransitionInvalideException(
                "Seule une vente VALIDEE peut etre reglee (etat actuel : " + vente.getStatut() + ")");
        }
        if (vente.getModeReglement() != ModeReglement.CREDIT) {
            throw new IllegalArgumentException(
                "Cette vente n'est pas une vente a credit : aucune creance a regler.");
        }
        if (vente.getPieceReglement() != null) {
            throw new TransitionInvalideException(
                "Cette creance a deja ete reglee le " + vente.getDateReglement() + ".");
        }
        if (req.modeReglement() == ModeReglement.CREDIT) {
            throw new IllegalArgumentException("Le canal d'encaissement ne peut pas etre CREDIT.");
        }
        User auteur = currentUser.requireUser();
        LocalDate dateReglement = req.dateReglement() != null ? req.dateReglement() : LocalDate.now();

        CompteOHADA compteTresorerie = switch (req.modeReglement()) {
            case CAISSE -> compteParNumero(COMPTE_CAISSE);
            case BANQUE -> etablissements.requireEtablissement(req.etablissementId(), TypeEtablissement.BANQUE).getCompte();
            case MOBILE_MONEY -> etablissements.requireEtablissement(req.etablissementId(), TypeEtablissement.MOBILE_MONEY).getCompte();
            case CREDIT -> throw new IllegalStateException("unreachable");
        };

        // Le montant du inclut l'eventuel ecart de reevaluation latent
        // (478/479) pose par une cloture pendant que la creance etait encore
        // ouverte : cet ecart vit sur une piece distincte de celle de la
        // vente, soldePourPieceEtCompte seul ne le verrait jamais et
        // laisserait un residu bloque sur le compte client apres reglement.
        BigDecimal montantDu = ecritureRepository.soldePourPieceEtCompte(vente.getPiece().getId(), COMPTE_CLIENTS);
        if (montantDu == null) {
            montantDu = BigDecimal.ZERO;
        }
        montantDu = montantDu.add(vente.getEcartLatentCumule());
        if (montantDu.signum() <= 0) {
            throw new IllegalStateException(
                "Aucune creance exigible trouvee pour la vente " + vente.getReference() + ".");
        }

        BigDecimal tauxReglement = null;
        BigDecimal montantEncaisse;
        if (vente.getDevise() == ConversionDeviseService.DEVISE_BASE) {
            montantEncaisse = montantDu;
        } else {
            tauxReglement = conversionDevise.tauxALaDate(dateReglement);
            if (tauxReglement == null || tauxReglement.signum() <= 0) {
                throw new IllegalStateException(
                    "Aucun taux de change applicable au " + dateReglement
                    + " : impossible d'encaisser une creance en " + vente.getDevise() + ".");
            }
            montantEncaisse = conversionDevise.enDeviseBase(vente.getTotalTtc(), vente.getDevise(), tauxReglement).montantBase();
        }

        String libelle = "Reglement creance " + vente.getReference() + " - " + vente.designationClient();
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        lignes.add(ligneReglement(compteTresorerie, montantEncaisse, BigDecimal.ZERO, libelle, dateReglement));
        lignes.add(ligneReglement(compteParNumero(COMPTE_CLIENTS), BigDecimal.ZERO, montantDu, libelle, dateReglement));

        // Ecart de change realise : encaisse > du => gain (776), sinon perte (676).
        BigDecimal ecart = montantEncaisse.subtract(montantDu);
        if (ecart.signum() > 0) {
            lignes.add(ligneReglement(compteParNumero(COMPTE_GAIN_CHANGE), BigDecimal.ZERO, ecart, libelle, dateReglement));
        } else if (ecart.signum() < 0) {
            lignes.add(ligneReglement(compteParNumero(COMPTE_PERTE_CHANGE), ecart.abs(), BigDecimal.ZERO, libelle, dateReglement));
        }

        JournalComptable journal = switch (req.modeReglement()) {
            case CAISSE -> JournalComptable.CAISSE;
            case BANQUE -> JournalComptable.BANQUE;
            case MOBILE_MONEY -> JournalComptable.MOBILE_MONEY;
            case CREDIT -> throw new IllegalStateException("unreachable");
        };

        PieceComptable piece = comptabilite.creerPieceInterne(journal, libelle, dateReglement, lignes, auteur);
        vente.setPieceReglement(piece);
        vente.setDateReglement(dateReglement);
        vente.setTauxReglement(tauxReglement);
        // La creance est soldee : l'ecart latent qu'elle portait eventuellement
        // vient d'etre inclus dans montantDu et credite en totalite, il ne
        // reste plus rien a reprendre pour elle a la prochaine cloture.
        vente.setEcartLatentCumule(BigDecimal.ZERO);
        log.info("Creance reglee [vente={}, encaisse={} {}, piece={}]",
            vente.getReference(), montantEncaisse, ConversionDeviseService.DEVISE_BASE, piece.getReference());
        return VenteResponse.from(vente);
    }

    private EcritureGrandLivre ligneReglement(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                              String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    // ---------------------------------------------------------------------
    // Calculs
    // ---------------------------------------------------------------------

    /**
     * Calcule le HT et la TVA de chaque ligne puis les totaux de la vente.
     * Les totaux sont la somme des lignes (jamais recalcules a partir d'un
     * total global) : la piece est ainsi equilibree par construction, sans
     * ecart d'arrondi.
     */
    private void calculerTotaux(Vente vente, BigDecimal taux) {
        BigDecimal totalHt = BigDecimal.ZERO;
        BigDecimal totalTva = BigDecimal.ZERO;

        for (LigneVente ligne : vente.getLignes()) {
            BigDecimal ht = ligne.getPrixUnitaire()
                .multiply(ligne.getQuantite())
                .setScale(2, RoundingMode.HALF_UP);
            BigDecimal tva = ligne.isSoumisTva() && taux != null && taux.signum() > 0
                ? ht.multiply(taux).divide(CENT, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            ligne.setMontantHt(ht);
            ligne.setMontantTva(tva);
            totalHt = totalHt.add(ht);
            totalTva = totalTva.add(tva);
        }

        vente.setTotalHt(totalHt);
        vente.setTotalTva(totalTva);
        vente.setTotalTtc(totalHt.add(totalTva));
    }

    /**
     * Ecritures du journal VENTES : un debit unique de la contrepartie pour
     * le TTC, un credit par compte de produit (lignes regroupees) et un
     * credit de TVA collectee.
     *
     * <p>Le grand livre est tenu en devise de base : une vente saisie en USD
     * est convertie ici au taux fige a la saisie. Chaque credit est converti
     * individuellement et le debit vaut leur somme exacte — jamais une
     * conversion independante du TTC, dont l'ecart d'arrondi desequilibrerait
     * la piece.</p>
     */
    private List<EcritureGrandLivre> construireEcrituresVente(Vente vente) {
        String libelle = "Vente " + vente.getReference() + " - " + vente.designationClient();

        // Crédits des produits, regroupés par compte (une écriture par compte
        // plutôt qu'une par ligne : le journal reste lisible).
        Map<CompteOHADA, BigDecimal> produits = new LinkedHashMap<>();
        for (LigneVente ligne : vente.getLignes()) {
            CompteOHADA compte = ligne.getArticle().getCompteProduit();
            if (compte == null) {
                throw new IllegalArgumentException(
                    "Aucun compte de produit n'est defini pour l'article \""
                    + ligne.getArticle().getLibelle() + "\".");
            }
            produits.merge(compte, ligne.getMontantHt(), BigDecimal::add);
        }

        List<EcritureGrandLivre> credits = new ArrayList<>();
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (Map.Entry<CompteOHADA, BigDecimal> produit : produits.entrySet()) {
            BigDecimal montantBase = versDeviseBase(produit.getValue(), vente);
            totalCredit = totalCredit.add(montantBase);
            credits.add(ecriture(produit.getKey(), BigDecimal.ZERO, montantBase,
                produit.getValue(), libelle, vente));
        }

        // Crédit de la TVA collectée.
        if (vente.getTotalTva().signum() > 0) {
            BigDecimal tvaBase = versDeviseBase(vente.getTotalTva(), vente);
            totalCredit = totalCredit.add(tvaBase);
            credits.add(ecriture(compteParNumero(COMPTE_TVA_FACTUREE), BigDecimal.ZERO, tvaBase,
                vente.getTotalTva(), libelle, vente));
        }

        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        ecritures.add(ecriture(compteContrepartie(vente), totalCredit, BigDecimal.ZERO,
            vente.getTotalTtc(), libelle, vente));
        ecritures.addAll(credits);
        return ecritures;
    }

    /** Compte debite : client pour une vente a credit, tresorerie pour un comptant. */
    private CompteOHADA compteContrepartie(Vente vente) {
        return switch (vente.getModeReglement()) {
            case CREDIT -> compteParNumero(COMPTE_CLIENTS);
            case CAISSE -> compteParNumero(COMPTE_CAISSE);
            case BANQUE, MOBILE_MONEY -> {
                if (vente.getEtablissement() == null) {
                    throw new IllegalArgumentException(
                        "Aucun etablissement n'est rattache a cette vente au comptant.");
                }
                yield vente.getEtablissement().getCompte();
            }
        };
    }

    /**
     * @param montantOrigine montant de la ligne dans la devise de saisie de la
     *                       vente, conserve pour la piste d'audit
     */
    private EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                        BigDecimal montantOrigine, String libelle, Vente vente) {
        boolean convertie = vente.getDevise() != null
            && vente.getDevise() != ConversionDeviseService.DEVISE_BASE;
        var builder = EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .dateEcriture(vente.getDateVente())
            // Le taux est porte meme sur une vente en FC : c'est lui qui permet
            // de reafficher l'ecriture en USD au taux de l'operation.
            .tauxApplique(vente.getTauxJournalier());
        if (convertie) {
            builder.devise(vente.getDevise().name()).montantDevise(montantOrigine);
        }
        return builder.build();
    }

    /** Convertit un montant de la devise de la vente vers la devise de base. */
    private BigDecimal versDeviseBase(BigDecimal montant, Vente vente) {
        if (montant == null || vente.getDevise() == null
            || vente.getDevise() == ConversionDeviseService.DEVISE_BASE) {
            return montant;
        }
        BigDecimal taux = vente.getTauxJournalier() != null && vente.getTauxJournalier().signum() > 0
            ? vente.getTauxJournalier()
            : conversionDevise.tauxCourant();
        if (taux == null || taux.signum() <= 0) {
            throw new IllegalStateException(
                "Aucun taux de change n'est rattache a la vente " + vente.getReference()
                + " : impossible de la comptabiliser en " + ConversionDeviseService.DEVISE_BASE + ".");
        }
        // Delegue : le sens du taux depend de la devise de base, la regle est
        // centralisee dans ConversionDeviseService pour ne pas diverger.
        return conversionDevise.enDeviseBase(montant, vente.getDevise(), taux).montantBase();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Vente charger(Long id) {
        return venteRepository.findWithLignesById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("Vente", id));
    }

    private CompteOHADA compteParNumero(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new IllegalStateException(
                "Compte " + numero + " absent du plan comptable"));
    }

    private void exigerVendable(Article article) {
        if (!article.isActif()) {
            throw new IllegalArgumentException(
                "L'article \"" + article.getLibelle() + "\" est desactive.");
        }
        if (article.getCompteProduit() == null) {
            throw new IllegalArgumentException(
                "L'article \"" + article.getLibelle() + "\" n'a pas de compte de produit : "
                + "il ne peut pas etre vendu.");
        }
    }

    private TypeEtablissement typeEtablissement(ModeReglement mode) {
        return mode == ModeReglement.BANQUE ? TypeEtablissement.BANQUE : TypeEtablissement.MOBILE_MONEY;
    }

    private String libelleCanal(ModeReglement mode) {
        return mode == ModeReglement.BANQUE ? "banque" : "mobile money";
    }
}
