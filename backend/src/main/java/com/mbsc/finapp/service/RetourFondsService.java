package com.mbsc.finapp.service;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.LigneRetourFonds;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.RetourFondsNote;
import com.mbsc.finapp.domain.StockNiveau;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieNote;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.StatutRetourFonds;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.notes.PreparationRetourFondsResponse;
import com.mbsc.finapp.dto.notes.RetourFondsRequest;
import com.mbsc.finapp.dto.notes.RetourFondsResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CamionMineraiRepository;
import com.mbsc.finapp.repository.ChargeCamionMineraiRepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.RetourFondsNoteRepository;
import com.mbsc.finapp.repository.StockNiveauRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retour de fonds sur une note de frais deja payee : l'emetteur reduit sa note (montant d'une ligne, ou quantite d'un
 * achat de marchandise : bouteilles, casiers, kg, litres...), le caissier valide quand il recoit l'argent en especes —
 * quel que soit le canal du paiement d'origine (caisse, banque, mobile money) — ou refuse avec un motif.
 *
 * <p>La validation fait, dans une seule transaction, le miroir du paiement de la note
 * ({@code CaisseService.executerPaiementNote}) pour la seule part rendue :</p>
 * <ol>
 *   <li>encaissement en caisse (D 571 / C compte de chaque ligne, HT, et C compte de TVA), au taux du paiement d'origine,
 *       rattache a la note par {@code TransactionCaisse.noteRetour} ;</li>
 *   <li>sortie du stock des marchandises rendues, au cout moyen (D 603x / C 311x, miroir de l'entree D 311x / C 603x) ;</li>
 *   <li>vides rendus par le fournisseur pour une boisson achetee avec echange de consigne ({@code RETOUR_ACHAT}) ;</li>
 *   <li>lignes et montant de la note mis aux nouvelles valeurs (la demande garde l'ancien et le nouveau) ;</li>
 *   <li>notification du DFIN, du comptable, du DA, de l'administrateur, du responsable bar ou cuisine selon les articles,
 *       et de l'emetteur — jamais du caissier qui valide.</li>
 * </ol>
 *
 * <p>Les caissiers sont prevenus des la demande (l'argent est attendu a la caisse) et si l'emetteur la retire ; chaque
 * action depose son detail metier (note, montant, lignes, recu, motif) dans le journal d'audit.</p>
 *
 * <p>Separation des taches : seul le CAISSIER recoit l'argent et valide, jamais l'ADMIN (voir SeparationDesTachesTest).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetourFondsService {

    /** Ceux qui peuvent emettre une note (memes roles que {@code NoteFraisService.creer}) ; seul l'emetteur reduit la sienne. */
    static final String EMETTEURS = "hasAnyRole('COMPTABLE', 'CAISSIER', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'ADMIN')";
    /** Suivi des retours de fonds (file d'attente de la caisse et historique). */
    static final String LECTURE = "hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'ADMIN')";
    /** Recevoir l'argent : le caissier seul. */
    static final String CAISSIER = "hasRole('CAISSIER')";

    /** Toujours notifies a la validation (demande de l'utilisateur), en plus du responsable bar ou cuisine et de l'emetteur. */
    static final List<RoleType> NOTIFIES = List.of(RoleType.DFIN, RoleType.COMPTABLE, RoleType.DA, RoleType.ADMIN);
    /** Liens des notifications du responsable bar (stock des boissons) et du responsable cuisine (provisions). */
    static final String LIEN_STOCK_BAR = "/restaurant/stock";
    static final String LIEN_STOCK_CUISINE = "/restaurant/provisions";
    /** Lien des notifications des caissiers : la page Caisse, ou la demande attend dans « Retours de fonds a recevoir ». */
    static final String LIEN_CAISSE = "/caisse";

    private final NoteFraisRepository noteRepository;
    private final RetourFondsNoteRepository retourRepository;
    private final CamionMineraiRepository camionRepository;
    private final ChargeCamionMineraiRepository chargeCamionRepository;
    private final StockNiveauRepository stockNiveauRepository;
    private final EmballageBoissonRepository emballageRepository;
    private final CurrentUserProvider currentUser;
    private final ConversionDeviseService conversionDevise;
    private final EcritureComptableService comptabilite;
    private final ReferenceGenerator referenceGenerator;
    private final StockService stockService;
    private final RestaurantService restaurantService;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------------
    // Emetteur : preparation, demande, retrait
    // ---------------------------------------------------------------------

    /** Ce qu'il faut a la boite de dialogue : note reductible ou non, demande en attente, lignes reductibles. */
    @PreAuthorize(EMETTEURS)
    @Transactional(readOnly = true)
    public PreparationRetourFondsResponse preparer(Long noteId) {
        User emetteur = currentUser.requireUser();
        NoteFrais note = charger(noteId);
        exigerEmetteur(note, emetteur);
        String raison = raisonNonReductible(note);
        RetourFondsResponse enAttente = retourRepository.findFirstByNoteIdAndStatut(note.getId(), StatutRetourFonds.EN_ATTENTE)
            .map(RetourFondsResponse::from).orElse(null);
        if (raison == null && enAttente != null) {
            raison = "Une demande de retour de fonds attend déjà la caisse pour cette note.";
        }
        List<PreparationRetourFondsResponse.Ligne> lignes = note.getLignes().stream().map(this::lignePreparation).toList();
        return new PreparationRetourFondsResponse(raison == null, raison, enAttente, note.getDevise(), note.getMontant(), lignes);
    }

    /** L'emetteur reduit sa note payee : la demande attend l'argent a la caisse. */
    @PreAuthorize(EMETTEURS)
    @Transactional
    public RetourFondsResponse demander(Long noteId, RetourFondsRequest req) {
        User emetteur = currentUser.requireUser();
        NoteFrais note = charger(noteId);
        exigerEmetteur(note, emetteur);
        String raison = raisonNonReductible(note);
        if (raison != null) {
            throw new TransitionInvalideException(raison);
        }
        if (retourRepository.findFirstByNoteIdAndStatut(note.getId(), StatutRetourFonds.EN_ATTENTE).isPresent()) {
            throw new TransitionInvalideException("Une demande de retour de fonds attend déjà la caisse pour cette note : "
                + "annulez-la avant d'en faire une autre.");
        }
        List<RetourFondsCalcul.Demande> demandes = req.lignes().stream()
            .map(l -> new RetourFondsCalcul.Demande(l.ligneId(), l.nouvelleQuantite(), l.nouveauMontant()))
            .toList();
        List<RetourFondsCalcul.Reduction> reductions = RetourFondsCalcul.reductions(vues(note), demandes);
        exigerStockSuffisant(note, reductions);

        Map<Long, LigneNoteFrais> parId = lignesParId(note);
        RetourFondsNote retour = RetourFondsNote.builder()
            .note(note)
            .motif(req.motif().trim())
            .montant(RetourFondsCalcul.total(reductions))
            .demandeur(emetteur)
            .build();
        for (RetourFondsCalcul.Reduction r : reductions) {
            retour.addLigne(LigneRetourFonds.builder()
                .ligneNote(parId.get(r.ligneId()))
                .ancienMontant(r.ancienMontant())
                .nouveauMontant(r.nouveauMontant())
                .ancienneQuantite(r.ancienneQuantite())
                .nouvelleQuantite(r.nouvelleQuantite())
                .montantRetour(r.montantRetour())
                .build());
        }
        retour = retourRepository.save(retour);
        note.addObservation(observation(note, emetteur, "Retour de fonds demandé : " + montant(retour) + " à rendre à la caisse. "
            + "Motif : " + retour.getMotif()));
        // La caisse est prevenue : l'argent va lui etre remis (lien vers la file « Retours de fonds a recevoir »).
        notificationService.notifierNote(List.of(), null, emetteur, TypeNotification.RETOUR_FONDS_A_RECEVOIR,
            "Retour de fonds à recevoir",
            note.getReference() + " — " + note.getObjet() + " : " + montant(retour) + " à recevoir en espèces de "
                + nom(emetteur) + " (" + detailLignes(retour) + "). Motif : " + retour.getMotif(),
            note, Map.of(RoleType.CAISSIER, LIEN_CAISSE));
        AuditService.detailler(note.getReference() + " — retour de " + montant(retour) + " demandé (" + detailLignes(retour)
            + "). Motif : " + retour.getMotif());
        log.info("Retour de fonds demandé [note={}, montant={} {}, par={}]",
            note.getReference(), retour.getMontant(), note.getDevise(), emetteur.getEmail());
        return RetourFondsResponse.from(retour);
    }

    /** L'emetteur retire sa demande tant que le caissier ne l'a pas traitee. */
    @PreAuthorize(EMETTEURS)
    @Transactional
    public RetourFondsResponse annuler(Long retourId) {
        User emetteur = currentUser.requireUser();
        RetourFondsNote retour = chargerPourMiseAJour(retourId);
        exigerEnAttente(retour);
        if (retour.getDemandeur() == null || !retour.getDemandeur().getId().equals(emetteur.getId())) {
            throw new TransitionInvalideException("Seul l'émetteur de la demande peut la retirer.");
        }
        retour.setStatut(StatutRetourFonds.ANNULE);
        retour.setDateDecision(Instant.now());
        NoteFrais note = retour.getNote();
        note.addObservation(observation(note, emetteur, "Demande de retour de fonds retirée (" + montant(retour) + ")."));
        // Les caissiers avaient ete prevenus de la demande : ils savent ainsi qu'il n'y a plus rien a recevoir.
        notificationService.notifierNote(List.of(), null, emetteur, TypeNotification.RETOUR_FONDS_RETIRE,
            "Retour de fonds retiré",
            note.getReference() + " — " + note.getObjet() + " : la demande de " + montant(retour)
                + " a été retirée par l'émetteur ; rien à recevoir.",
            note, Map.of(RoleType.CAISSIER, LIEN_CAISSE));
        AuditService.detailler(note.getReference() + " — demande de retour de " + montant(retour) + " retirée par l'émetteur");
        return RetourFondsResponse.from(retour);
    }

    // ---------------------------------------------------------------------
    // Caisse : file d'attente, reception, refus
    // ---------------------------------------------------------------------

    /** Demandes d'un statut (par defaut : en attente), de la plus ancienne a la plus recente. */
    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<RetourFondsResponse> lister(StatutRetourFonds statut) {
        return retourRepository.findByStatutOrderByDateDemandeAscIdAsc(statut == null ? StatutRetourFonds.EN_ATTENTE : statut)
            .stream().map(RetourFondsResponse::from).toList();
    }

    /** Le caissier a recu l'argent en especes : retour en caisse, stock, vides, note et notifications, en une transaction. */
    @PreAuthorize(CAISSIER)
    @Transactional
    public RetourFondsResponse valider(Long retourId) {
        User caissier = currentUser.requireUser();
        RetourFondsNote retour = chargerPourMiseAJour(retourId);
        exigerEnAttente(retour);
        NoteFrais note = charger(retour.getNote().getId());
        String raison = raisonNonReductible(note);
        if (raison != null) {
            throw new TransitionInvalideException(raison);
        }

        // Les lignes de la note n'ont pas pu bouger depuis la demande (une seule demande en attente, une note payee ne se
        // modifie plus autrement) ; on le verifie quand meme : rendre sur des valeurs perimees fausserait tout.
        Map<Long, LigneNoteFrais> parId = lignesParId(note);
        List<RetourFondsCalcul.Reduction> reductions = new ArrayList<>();
        for (LigneRetourFonds lr : retour.getLignes()) {
            LigneNoteFrais l = parId.get(lr.getLigneNote().getId());
            if (l == null || l.getMontant().compareTo(lr.getAncienMontant()) != 0
                    || (lr.getAncienneQuantite() != null && !egal(l.getQuantiteMarchandise(), lr.getAncienneQuantite()))) {
                throw new TransitionInvalideException("La note a changé depuis la demande : l'émetteur doit la retirer et la refaire.");
            }
            reductions.add(new RetourFondsCalcul.Reduction(l.getId(), lr.getAncienMontant(), lr.getNouveauMontant(),
                lr.getAncienneQuantite(), lr.getNouvelleQuantite(), lr.getMontantRetour()));
        }
        exigerStockSuffisant(note, reductions);

        // 1. Encaissement en caisse, au taux du paiement d'origine : miroir exact des debits du paiement.
        BigDecimal taux = tauxDuPaiement(note);
        ConversionDeviseService.Conversion conversion = conversionDevise.enDeviseBase(retour.getMontant(), note.getDevise(), taux);
        List<RetourFondsCalcul.PartCredit> parts = RetourFondsCalcul.ventiler(reductions, vuesParId(note), conversion.montantBase(),
            m -> conversionDevise.enDeviseBase(m, note.getDevise(), conversion.tauxApplique()).montantBase());
        List<EcritureComptableService.LigneCredit> credits = new ArrayList<>();
        for (RetourFondsCalcul.PartCredit p : parts) {
            LigneNoteFrais l = parId.get(p.ligneId());
            CompteOHADA compte = l.getCompteImputation() != null ? l.getCompteImputation() : comptabilite.compteChargesDiversesParDefaut();
            if (p.ht().signum() != 0) {
                credits.add(new EcritureComptableService.LigneCredit(compte, p.ht()));
            }
            if (p.tva().signum() != 0) {
                credits.add(new EcritureComptableService.LigneCredit(l.getCompteTva(), p.tva()));
            }
        }
        String libelle = tronquer("Retour de fonds " + note.getReference() + " – " + note.getObjet()
            + (conversion.estConvertie()
                ? " (" + conversion.montantOrigine().toPlainString() + " " + conversion.deviseOrigine()
                    // Echelle de la table des taux (4), comme le libelle du paiement : le taux relu sur la transaction en a 6.
                    + " @ " + conversion.tauxApplique().setScale(4, RoundingMode.HALF_UP).toPlainString() + ")"
                : ""), 255);
        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(UUID.randomUUID())
            .reference(referenceGenerator.pourTransaction())
            .noteRetour(note)
            .montant(conversion.montantBase())
            .sens(SensTransaction.ENCAISSEMENT)
            .libelle(libelle)
            .caissier(caissier)
            .numeroRecu(referenceGenerator.pourRecu())
            .dateOperation(Instant.now())
            .tauxJournalier(taux)
            .build();
        TransactionCaisse encaissement = comptabilite.enregistrerEncaissementMultiLigne(transaction, credits, libelle, conversion);
        LocalDate date = encaissement.getDateOperation().atZone(ZoneOffset.UTC).toLocalDate();
        String motifStock = "Retour d'achat — " + note.getReference();

        // 2. Stock : les marchandises rendues sortent au cout moyen (D 603x / C 311x), entrepot par entrepot, articles tries
        //    (meme ordre de verrouillage que les autres sorties).
        Map<Long, List<StockService.SortieVente>> sortiesParEntrepot = new LinkedHashMap<>();
        Map<Long, Entrepot> entrepots = new LinkedHashMap<>();
        reductions.stream()
            .map(r -> Map.entry(parId.get(r.ligneId()), r.quantiteRendue()))
            .filter(e -> e.getKey().isAchatMarchandise() && e.getKey().getArticle() != null && e.getKey().getEntrepot() != null
                && e.getValue().signum() > 0)
            .sorted(Comparator.comparing(e -> e.getKey().getArticle().getId()))
            .forEach(e -> {
                Entrepot entrepot = e.getKey().getEntrepot();
                entrepots.putIfAbsent(entrepot.getId(), entrepot);
                // La sortie désigne la ligne de note rendue : c'est le lot de CETTE livraison qui diminue, pas le plus ancien
                // de l'article (FIFO) — sinon la page de stock montre inchangé l'achat retourné.
                sortiesParEntrepot.computeIfAbsent(entrepot.getId(), k -> new ArrayList<>())
                    .add(new StockService.SortieVente(e.getKey().getArticle(), e.getValue()).rendueSurLaLigne(e.getKey().getId()));
            });
        sortiesParEntrepot.forEach((entrepotId, sorties) ->
            stockService.enregistrerSortieVenteInterne(date, motifStock, entrepots.get(entrepotId), sorties, caissier));

        // 3. Vides : le fournisseur rend ceux des bouteilles achetees avec echange de consigne puis rendues.
        for (RetourFondsCalcul.Reduction r : reductions) {
            LigneNoteFrais l = parId.get(r.ligneId());
            if (l.isAchatMarchandise() && l.isEchangeConsigne() && l.getArticle() != null
                    && l.getArticle().getType() == TypeArticle.BOISSON) {
                int bouteilles = RestaurantService.bouteilles(r.quantiteRendue());
                if (bouteilles > 0) {
                    restaurantService.enregistrerRetourVidesAchatInterne(l.getArticle(), bouteilles, date, motifStock, caissier);
                }
            }
        }

        // 4. La note prend ses nouvelles valeurs ; la demande garde l'ancien et le nouveau.
        BigDecimal ancienMontantNote = note.getMontant();
        for (LigneRetourFonds lr : retour.getLignes()) {
            LigneNoteFrais l = parId.get(lr.getLigneNote().getId());
            l.setMontant(lr.getNouveauMontant());
            if (lr.getNouvelleQuantite() != null) {
                l.setQuantiteMarchandise(lr.getNouvelleQuantite());
            }
        }
        note.recalculerMontant();
        retour.setStatut(StatutRetourFonds.VALIDE);
        retour.setCaissier(caissier);
        retour.setDateDecision(Instant.now());
        retour.setTransaction(encaissement);
        note.addObservation(observation(note, caissier, "Retour de fonds reçu en caisse : " + montant(retour)
            + " (reçu " + encaissement.getNumeroRecu() + ") ; montant de la note : " + BudgetPdfService.montant(ancienMontantNote)
            + " → " + BudgetPdfService.montant(note.getMontant()) + " " + note.getDevise() + "."));

        // 5. Notifications. Le responsable bar ou cuisine ne voit que ses propres notes : sa notification mene a son stock.
        Map<RoleType, String> responsables = new LinkedHashMap<>();
        for (RoleType role : responsablesConcernes(reductions, parId)) {
            responsables.put(role, role == RoleType.RESP_BAR ? LIEN_STOCK_BAR : LIEN_STOCK_CUISINE);
        }
        notificationService.notifierNote(NOTIFIES, retour.getDemandeur(), caissier, TypeNotification.RETOUR_FONDS_VALIDE,
            "Retour de fonds reçu",
            note.getReference() + " — " + note.getObjet() + " : " + montant(retour) + " rendus en caisse (reçu "
                + encaissement.getNumeroRecu() + ") ; la note passe de " + BudgetPdfService.montant(ancienMontantNote) + " à "
                + BudgetPdfService.montant(note.getMontant()) + " " + note.getDevise() + ". Motif : " + retour.getMotif(),
            note, responsables);
        AuditService.detailler(note.getReference() + " — " + montant(retour) + " reçus en espèces, reçu "
            + encaissement.getNumeroRecu() + " (" + encaissement.getReference() + ") ; note "
            + BudgetPdfService.montant(ancienMontantNote) + " → " + BudgetPdfService.montant(note.getMontant()) + " "
            + note.getDevise() + " (" + detailLignes(retour) + ")");
        log.info("Retour de fonds reçu [note={}, montant={} {}, transaction={}, caissier={}]", note.getReference(),
            retour.getMontant(), note.getDevise(), encaissement.getReference(), caissier.getEmail());
        return RetourFondsResponse.from(retour);
    }

    /** Le caissier n'a pas recu l'argent : la note reste telle quelle, l'emetteur est prevenu avec le motif. */
    @PreAuthorize(CAISSIER)
    @Transactional
    public RetourFondsResponse refuser(Long retourId, String motif) {
        User caissier = currentUser.requireUser();
        RetourFondsNote retour = chargerPourMiseAJour(retourId);
        exigerEnAttente(retour);
        String m = motif == null ? "" : motif.trim();
        if (m.isEmpty()) {
            throw new IllegalArgumentException("Indiquez le motif du refus : il est transmis à l'émetteur.");
        }
        retour.setStatut(StatutRetourFonds.REFUSE);
        retour.setCaissier(caissier);
        retour.setDateDecision(Instant.now());
        retour.setMotifRefus(m);
        NoteFrais note = retour.getNote();
        note.addObservation(observation(note, caissier, "Retour de fonds refusé par la caisse (" + montant(retour) + ") : " + m));
        notificationService.notifierNote(List.of(), retour.getDemandeur(), caissier, TypeNotification.RETOUR_FONDS_REFUSE,
            "Retour de fonds refusé",
            note.getReference() + " — " + note.getObjet() + " : " + montant(retour) + " non reçus à la caisse. Motif : " + m,
            note);
        AuditService.detailler(note.getReference() + " — " + montant(retour) + " non reçus, retour refusé. Motif : " + m);
        return RetourFondsResponse.from(retour);
    }

    // ---------------------------------------------------------------------
    // Regles
    // ---------------------------------------------------------------------

    /** Raison pour laquelle la note ne se reduit pas apres paiement, ou {@code null} si elle le peut. */
    String raisonNonReductible(NoteFrais note) {
        if (note.getSens() != SensTransaction.DECAISSEMENT) {
            return "Seule une note de dépense se réduit après paiement.";
        }
        if (note.getStatut() != StatutNote.PAYEE) {
            return "Seule une note payée se réduit ainsi (état actuel : " + note.getStatut() + ").";
        }
        if (!note.estDejaReglee()) {
            return "Cette note n'a aucun paiement enregistré.";
        }
        if (note.getCategorie() != null && note.getCategorie() != CategorieNote.STANDARD) {
            return "Une note de paie ne se réduit pas après paiement.";
        }
        if (!camionRepository.findByNoteFraisReglementId(note.getId()).isEmpty()
            || !chargeCamionRepository.findByNoteFraisReglementId(note.getId()).isEmpty()) {
            return "Un règlement de camions de minerais ou de leurs frais ne se réduit pas après paiement.";
        }
        return null;
    }

    /**
     * On ne rend pas une marchandise deja vendue ou consommee : la quantite rendue de chaque article doit etre encore en
     * stock dans l'entrepot de la ligne (plusieurs lignes du meme article s'additionnent).
     */
    void exigerStockSuffisant(NoteFrais note, List<RetourFondsCalcul.Reduction> reductions) {
        Map<Long, LigneNoteFrais> parId = lignesParId(note);
        Map<String, BigDecimal> demande = new LinkedHashMap<>();
        Map<String, LigneNoteFrais> exemple = new LinkedHashMap<>();
        for (RetourFondsCalcul.Reduction r : reductions) {
            LigneNoteFrais l = parId.get(r.ligneId());
            if (!l.isAchatMarchandise() || l.getArticle() == null || l.getEntrepot() == null || r.quantiteRendue().signum() <= 0) {
                continue;
            }
            String cle = l.getArticle().getId() + "|" + l.getEntrepot().getId();
            demande.merge(cle, r.quantiteRendue(), BigDecimal::add);
            exemple.putIfAbsent(cle, l);
        }
        for (Map.Entry<String, BigDecimal> e : demande.entrySet()) {
            LigneNoteFrais l = exemple.get(e.getKey());
            BigDecimal dispo = stockNiveauRepository.findByArticleAndEntrepot(l.getArticle(), l.getEntrepot())
                .map(StockNiveau::getQuantite).orElse(BigDecimal.ZERO);
            if (dispo.compareTo(e.getValue()) < 0) {
                String unite = l.getArticle().getUniteMesure() == null ? "" : " " + l.getArticle().getUniteMesure();
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock insuffisant pour " + l.getArticle().getLibelle()
                    + " (" + l.getEntrepot().getNom() + ") : il en reste " + quantite(dispo) + unite + ", la réduction en retire "
                    + quantite(e.getValue()) + unite + ". Ce qui a déjà été vendu ou consommé ne se rend plus.");
            }
        }
    }

    /** Responsable bar si une boisson est rendue, responsable cuisine si c'est une provision ou un plat. */
    static Set<RoleType> responsablesConcernes(List<RetourFondsCalcul.Reduction> reductions, Map<Long, LigneNoteFrais> parId) {
        Set<RoleType> roles = new LinkedHashSet<>();
        for (RetourFondsCalcul.Reduction r : reductions) {
            Article a = parId.get(r.ligneId()).getArticle();
            if (a == null || a.getType() == null) {
                continue;
            }
            if (a.getType() == TypeArticle.BOISSON) {
                roles.add(RoleType.RESP_BAR);
            } else if (a.getType() == TypeArticle.PROVISION || a.getType() == TypeArticle.PLAT) {
                roles.add(RoleType.RESP_CUISINE);
            }
        }
        return roles;
    }

    /** Taux (FC pour 1 USD) du paiement d'origine, quel qu'en soit le canal ; le taux du jour s'il n'a pas ete trace. */
    private BigDecimal tauxDuPaiement(NoteFrais note) {
        BigDecimal taux = null;
        if (note.getTransaction() != null) {
            taux = note.getTransaction().getTauxJournalier();
        } else if (note.getTransactionBancaire() != null) {
            taux = note.getTransactionBancaire().getTauxJournalier();
        } else if (note.getTransactionMobileMoney() != null) {
            taux = note.getTransactionMobileMoney().getTauxJournalier();
        }
        return taux != null ? taux : conversionDevise.tauxCourant();
    }

    // ---------------------------------------------------------------------
    // Aides
    // ---------------------------------------------------------------------

    private NoteFrais charger(Long id) {
        return noteRepository.findWithDetailsById(id).orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", id));
    }

    private RetourFondsNote chargerPourMiseAJour(Long id) {
        return retourRepository.findPourMiseAJour(id).orElseThrow(() -> RessourceIntrouvableException.of("RetourFondsNote", id));
    }

    private static void exigerEmetteur(NoteFrais note, User utilisateur) {
        if (note.getCreateur() == null || !note.getCreateur().getId().equals(utilisateur.getId())) {
            throw new TransitionInvalideException("Seul l'émetteur de la note peut la réduire.");
        }
    }

    private static void exigerEnAttente(RetourFondsNote retour) {
        if (retour.getStatut() != StatutRetourFonds.EN_ATTENTE) {
            throw new TransitionInvalideException("Cette demande de retour de fonds n'est plus en attente (état : "
                + retour.getStatut() + ").");
        }
    }

    private static Map<Long, LigneNoteFrais> lignesParId(NoteFrais note) {
        Map<Long, LigneNoteFrais> parId = new LinkedHashMap<>();
        for (LigneNoteFrais l : note.getLignes()) {
            parId.put(l.getId(), l);
        }
        return parId;
    }

    static RetourFondsCalcul.LigneNote vue(LigneNoteFrais l) {
        return new RetourFondsCalcul.LigneNote(l.getId(), l.getMontant(), l.getQuantiteMarchandise(), l.isAchatMarchandise(),
            l.isFraisApproche(), l.isSoumisTva(), l.getTauxTvaApplique(), l.isSoumisTva() && l.getCompteTva() != null);
    }

    private static List<RetourFondsCalcul.LigneNote> vues(NoteFrais note) {
        return note.getLignes().stream().map(RetourFondsService::vue).toList();
    }

    private static Map<Long, RetourFondsCalcul.LigneNote> vuesParId(NoteFrais note) {
        Map<Long, RetourFondsCalcul.LigneNote> parId = new LinkedHashMap<>();
        for (LigneNoteFrais l : note.getLignes()) {
            parId.put(l.getId(), vue(l));
        }
        return parId;
    }

    private PreparationRetourFondsResponse.Ligne lignePreparation(LigneNoteFrais l) {
        Article a = l.getArticle();
        Integer contenance = a != null && a.getType() == TypeArticle.BOISSON
            ? emballageRepository.findByArticleBoissonId(a.getId()).map(e -> e.getContenanceCasier()).orElse(null)
            : null;
        BigDecimal stock = l.isAchatMarchandise() && a != null && l.getEntrepot() != null
            ? stockNiveauRepository.findByArticleAndEntrepot(a, l.getEntrepot()).map(StockNiveau::getQuantite).orElse(BigDecimal.ZERO)
            : null;
        var compte = l.getCompteImputation();
        return new PreparationRetourFondsResponse.Ligne(
            l.getId(), l.getDescription(),
            compte == null ? null : compte.getNumero(), compte == null ? null : compte.getLibelle(),
            l.isAchatMarchandise(), l.isFraisApproche(), !l.isFraisApproche(),
            a == null ? null : a.getId(), a == null ? null : a.getCode(), a == null ? null : a.getLibelle(),
            a == null ? null : a.getType(), a == null ? null : a.getUniteMesure(),
            l.getEntrepot() == null ? null : l.getEntrepot().getNom(),
            l.getMontant(), l.getQuantiteMarchandise(), l.montantTtc(), l.isSoumisTva(), l.getTauxTvaApplique(),
            l.isEchangeConsigne(), contenance, stock);
    }

    private static ObservationNote observation(NoteFrais note, User auteur, String texte) {
        return ObservationNote.builder()
            .noteFrais(note)
            .auteur(auteur)
            .statutAuMoment(note.getStatut())
            .commentaire(tronquer(texte, 1000))
            .build();
    }

    private static String montant(RetourFondsNote retour) {
        return BudgetPdfService.montant(retour.getMontant()) + " " + retour.getNote().getDevise();
    }

    /**
     * Lignes reduites, avant → apres, pour les notifications et le journal d'audit : « Nkoyi Black 50 Cl 60 → 20 ;
     * Rames de papier 100,00 → 60,00 » (quantite pour un achat de marchandise, montant HT sinon).
     */
    static String detailLignes(RetourFondsNote retour) {
        return retour.getLignes().stream().map(lr -> {
            LigneNoteFrais l = lr.getLigneNote();
            String nom = l.getArticle() != null ? l.getArticle().getLibelle()
                : l.getDescription() != null && !l.getDescription().isBlank() ? l.getDescription()
                : l.getCompteImputation() != null ? l.getCompteImputation().getNumero() : "ligne " + l.getId();
            return lr.getAncienneQuantite() != null && lr.getNouvelleQuantite() != null
                ? nom + " " + quantite(lr.getAncienneQuantite()) + " → " + quantite(lr.getNouvelleQuantite())
                : nom + " " + BudgetPdfService.montant(lr.getAncienMontant()) + " → " + BudgetPdfService.montant(lr.getNouveauMontant());
        }).collect(Collectors.joining(" ; "));
    }

    /** Nom affiche d'un utilisateur (prenom et nom, sinon son identifiant). */
    private static String nom(User u) {
        if (u == null) {
            return "l'émetteur";
        }
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static String quantite(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static boolean egal(BigDecimal a, BigDecimal b) {
        return Objects.equals(a == null ? null : a.stripTrailingZeros(), b == null ? null : b.stripTrailingZeros());
    }

    private static String tronquer(String texte, int max) {
        return texte.length() <= max ? texte : texte.substring(0, max - 1) + "…";
    }
}
