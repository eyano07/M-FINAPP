package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CamionMinerai;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.PieceJointe;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PrioriteNote;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.CreerNoteReglementCamionsRequest;
import com.mbsc.finapp.dto.notes.LigneCompteRequest;
import com.mbsc.finapp.dto.notes.LigneNoteFraisRequest;
import com.mbsc.finapp.dto.notes.ModifierComptesRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.dto.notes.NoteFraisResponse;
import com.mbsc.finapp.dto.notes.PrioriteRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CamionMineraiRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EmballageBoissonRepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestration du cycle de vie d'une note de frais.
 *
 * <p>Machine a etats (voir {@link StatutNote}) :</p>
 * <pre>
 *   BROUILLON --soumettre(Directeur/Caissier)--> SOUMISE
 *   SOUMISE   --verifier(DFIN)----------------->  VERIFIEE_DFIN
 *   VERIFIEE_DFIN --valider(DA)--------------->   VALIDEE_DA
 *   VERIFIEE_DFIN --rejeter(DA)--------------->   REJETEE_DA
 *   VALIDEE_DA    --transmettre(DFIN)--------->   TRANSMISE_CAISSE
 *   REJETEE_DA    --resoumettre(Createur)----->   SOUMISE
 *   TRANSMISE_CAISSE --payer(Caissier)-------->   PAYEE   (gere par CaisseService)
 *   (tout etat non terminal) --annuler-------->   ANNULEE
 * </pre>
 *
 * <p>Le controle des roles est applique par {@link PreAuthorize}. Chaque
 * transition trace une {@link ObservationNote} pour l'audit.</p>
 *
 * <p><b>Cas particulier RESP_RESTAURANT.</b> Le responsable restaurant ne
 * crée jamais de note de frais libre : il ne peut soumettre qu'une note
 * "spéciale" d'achat de boissons (une seule ligne, achat de marchandise,
 * article de type BOISSON), qui suit ensuite exactement le même circuit que
 * toute autre note de décaissement. Cela impose tout achat de boissons dans
 * le circuit d'approbation DFIN/DA et son règlement par la caisse — voir
 * {@link #validerNoteRespRestaurant} et {@code CaisseService.payerNote}
 * (échange de consigne appliqué au paiement).</p>
 */
@Service
@RequiredArgsConstructor
public class NoteFraisService {

    private static final Logger log = LoggerFactory.getLogger(NoteFraisService.class);

    /** Etats depuis lesquels une annulation reste possible. */
    private static final Set<StatutNote> ANNULABLES = EnumSet.of(
        StatutNote.BROUILLON, StatutNote.SOUMISE, StatutNote.VERIFIEE_DFIN,
        StatutNote.VALIDEE_DA, StatutNote.REJETEE_DA);

    /** Types MIME acceptes pour les pieces jointes (PDF ou image). */
    private static final Set<String> MIME_AUTORISES = Set.of(
        "application/pdf", "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");

    /** Taille maximale d'une piece jointe (alignee sur spring.servlet.multipart.max-file-size). */
    private static final long TAILLE_MAX_OCTETS = 20L * 1024 * 1024;

    private final NoteFraisRepository noteRepository;
    private final CompteOHADARepository compteRepository;
    private final ArticleRepository articleRepository;
    private final EntrepotRepository entrepotRepository;
    private final EmballageBoissonRepository emballageBoissonRepository;
    /** Camions rattaches a une note de reglement de minerais — voir creerReglementCamionsMinerai. */
    private final CamionMineraiRepository camionRepository;
    private final ReferenceGenerator referenceGenerator;
    private final CurrentUserProvider currentUser;
    private final StorageService storage;
    private final NotificationService notificationService;
    /** Fige le taux d'engagement des notes en devise lors de la transmission. */
    private final ConversionDeviseService conversionDevise;
    /** Fige le taux de TVA applicable a chaque ligne soumise a la TVA, au moment de sa saisie. */
    private final TauxTvaService tauxTvaService;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<NoteFraisResponse> lister() {
        return noteRepository.findAllByOrderByDateCreationDesc().stream()
            .filter(this::estVisiblePourUtilisateur)
            .map(NoteFraisResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<NoteFraisResponse> listerParStatut(StatutNote statut) {
        return noteRepository.findByStatut(statut).stream()
            .filter(this::estVisiblePourUtilisateur)
            .map(NoteFraisResponse::from)
            .toList();
    }

    /** Statuts visibles par un caissier "pur" : uniquement les notes deja validees par le DA. */
    private static final Set<StatutNote> STATUTS_VISIBLES_CAISSIER = EnumSet.of(
        StatutNote.VALIDEE_DA, StatutNote.TRANSMISE_CAISSE, StatutNote.PAYEE);

    /**
     * Regle de visibilite :
     * <ul>
     *   <li>le Directeur metier (DIRECTEUR, sans autre role plus large) ne
     *       voit que les notes qu'il a lui-meme creees : il n'intervient pas
     *       dans le circuit de validation (DFIN/DA/Caissier) et n'a donc pas
     *       a consulter les notes des autres createurs ;</li>
     *   <li>de meme, le responsable restaurant (RESP_RESTAURANT seul) ne voit
     *       que ses propres notes d'achat de boissons, et la logistique
     *       (LOGISTIQUE seul) que ses propres notes de reglement de camions
     *       minerais — ni l'un ni l'autre n'a a consulter les depenses des
     *       autres services, memes regles que le Directeur metier ;</li>
     *   <li>le caissier "pur" (sans role DFIN/DA/DG/ADMIN) voit les notes
     *       deja validees par le DA ({@link #STATUTS_VISIBLES_CAISSIER}),
     *       quel qu'en soit le createur, plus ses propres notes a n'importe
     *       quel stade (il reste un employe qui peut soumettre ses propres
     *       depenses et doit pouvoir en suivre l'avancement) ;</li>
     *   <li>le DA ne voit pas les notes encore non traitees par le DFIN
     *       (etats {@link StatutNote#BROUILLON} et {@link StatutNote#SOUMISE}) ;</li>
     *   <li>les autres roles (DFIN, ADMIN, DG) conservent une visibilite
     *       complete.</li>
     * </ul>
     */
    private boolean estVisiblePourUtilisateur(NoteFrais note) {
        var authorities = currentUser.requirePrincipal().getAuthorities();
        boolean estDA = authorities.stream()
            .anyMatch(a -> "ROLE_DA".equals(a.getAuthority()));
        boolean estCaissier = authorities.stream()
            .anyMatch(a -> "ROLE_CAISSIER".equals(a.getAuthority()));
        boolean estDfinOuAdmin = authorities.stream()
            .anyMatch(a -> "ROLE_DFIN".equals(a.getAuthority())
                        || "ROLE_ADMIN".equals(a.getAuthority()));
        boolean estDG = authorities.stream()
            .anyMatch(a -> "ROLE_DG".equals(a.getAuthority()));
        boolean estDirecteurSeul = authorities.stream()
            .anyMatch(a -> "ROLE_DIRECTEUR".equals(a.getAuthority()))
            && authorities.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                         || "ROLE_DG".equals(a.getAuthority())
                         || "ROLE_DA".equals(a.getAuthority())
                         || "ROLE_DFIN".equals(a.getAuthority())
                         || "ROLE_CAISSIER".equals(a.getAuthority()));
        boolean estCaissierSeul = estCaissier && !estDfinOuAdmin && !estDA && !estDG;
        // La logistique ne cree que des notes de reglement de camions
        // minerais : meme logique que le Directeur metier, elle ne consulte
        // pas les depenses des autres services.
        boolean estLogistiqueSeul = authorities.stream()
            .anyMatch(a -> "ROLE_LOGISTIQUE".equals(a.getAuthority()))
            && authorities.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                         || "ROLE_DG".equals(a.getAuthority())
                         || "ROLE_DA".equals(a.getAuthority())
                         || "ROLE_DFIN".equals(a.getAuthority())
                         || "ROLE_CAISSIER".equals(a.getAuthority()));

        if (estDirecteurSeul || estRespRestaurantSeul() || estLogistiqueSeul) {
            return note.getCreateur() != null
                && note.getCreateur().getId().equals(currentUser.requireUserId());
        }
        if (estCaissierSeul) {
            boolean estCreateur = note.getCreateur() != null
                && note.getCreateur().getId().equals(currentUser.requireUserId());
            return estCreateur || STATUTS_VISIBLES_CAISSIER.contains(note.getStatut());
        }
        if (estDA && !estDfinOuAdmin) {
            return note.getStatut() != StatutNote.BROUILLON
                && note.getStatut() != StatutNote.SOUMISE;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public NoteFraisDetailResponse consulter(Long id) {
        NoteFrais note = noteRepository.findWithDetailsById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", id));
        // Anti-IDOR : la meme regle de visibilite que pour la liste s'applique
        // a la consultation directe par identifiant.
        if (!estVisiblePourUtilisateur(note)) {
            throw RessourceIntrouvableException.of("NoteFrais", id);
        }
        return NoteFraisDetailResponse.from(note);
    }

    // ---------------------------------------------------------------------
    // Creation / modification (BROUILLON)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('COMPTABLE', 'CAISSIER', 'RESP_RESTAURANT')")
    @Transactional
    public NoteFraisDetailResponse creer(NoteFraisRequest req) {
        User auteur = currentUser.requireUser();
        SensTransaction sens = req.sens() == null ? SensTransaction.DECAISSEMENT : req.sens();
        // Une note d'encaissement est une recette de caisse executee
        // directement par le caissier (pas de circuit DFIN/DA) : le
        // Directeur, qui peut soumettre une demande de decaissement, n'a pas
        // vocation a emettre une recette.
        if (sens == SensTransaction.ENCAISSEMENT) {
            exigerRoleCaissierOuAdmin();
        }

        NoteFrais note = NoteFrais.builder()
            .reference(referenceGenerator.pourNoteFrais())
            .objet(req.objet())
            .beneficiaire(req.beneficiaire())
            .description(req.description())
            .montant(BigDecimal.ZERO)
            .devise(req.devise() == null ? Devise.CDF : req.devise())
            .statut(StatutNote.BROUILLON)
            .sens(sens)
            .createur(auteur)
            .build();

        construireLignes(note, req.lignes());
        note.recalculerMontant();
        validerNoteRespRestaurant(note);

        note.addObservation(observation(note, auteur, StatutNote.BROUILLON, "Creation de la note"));
        note = noteRepository.save(note);

        log.info("Note de frais creee [ref={}, montant={}, lignes={}, par={}]",
            note.getReference(), note.getMontant(), note.getLignes().size(), auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    @PreAuthorize("hasAnyRole('COMPTABLE', 'CAISSIER', 'RESP_RESTAURANT')")
    @Transactional
    public NoteFraisDetailResponse modifier(Long id, NoteFraisRequest req) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.BROUILLON, "modifier");
        exigerCreateur(note);

        note.setObjet(req.objet());
        note.setBeneficiaire(req.beneficiaire());
        note.setDescription(req.description());
        note.setDevise(req.devise() == null ? Devise.CDF : req.devise());

        List<LigneNoteFrais> nouvelles = creerLignes(req.lignes());
        note.remplacerLignes(nouvelles);
        note.recalculerMontant();
        validerNoteRespRestaurant(note);

        log.info("Note de frais modifiee [ref={}, montant={}]", note.getReference(), note.getMontant());
        return NoteFraisDetailResponse.from(note);
    }

    /**
     * Cree, pour la logistique, une note de reglement de la dette
     * fournisseur d'un ou plusieurs camions de minerais — une ligne par
     * camion, imputee au compte Fournisseurs (4011) pour son montant TTC
     * (prix hors taxes + TVA recuperable, reel si l'achat est deja valide,
     * sinon estime au taux du jour de reception — voir {@link
     * #detteEstimee}). Contrairement a {@link #creer}, la note est
     * immediatement soumise au DFIN (BROUILLON -> SOUMISE en un seul geste,
     * cote ecran) : la logistique ne compose pas une note comme un
     * comptable, elle demande le paiement d'une dette, constatee ou non.
     *
     * <p>Un camion encore A_VALIDER peut etre selectionne : c'est precisement
     * le paiement de cette note, en bout de circuit, qui constatera son
     * achat — voir {@code MineraiService.finaliserReglementNoteInterne}. La
     * validation et le reglement ne sont plus deux actions separees de la
     * caisse : ils n'en font plus qu'une, au bout du circuit logistique ->
     * DFIN -> DA -> DFIN -> tresorerie.</p>
     *
     * <p>Chaque camion selectionne est rattache a la note ({@code
     * CamionMinerai.noteFraisReglement}) pour empecher qu'il figure dans une
     * seconde demande tant que celle-ci est en cours — voir {@link
     * MineraiService#listerARegler}. Le lien est libere si la note est
     * annulee ({@link #annuler}).</p>
     */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse creerReglementCamionsMinerai(CreerNoteReglementCamionsRequest req) {
        User auteur = currentUser.requireUser();
        List<CamionMinerai> camions = camionRepository.findAllById(req.camionIds());
        if (camions.size() != req.camionIds().size()) {
            throw new RessourceIntrouvableException("Un ou plusieurs camions selectionnes sont introuvables.");
        }
        for (CamionMinerai camion : camions) {
            if (camion.isRegle()) {
                throw new IllegalArgumentException("Le camion " + camion.designation() + " est deja regle.");
            }
            if (camion.getNoteFraisReglement() != null) {
                throw new IllegalArgumentException(
                    "Le camion " + camion.designation() + " a deja une note de reglement en cours ("
                    + camion.getNoteFraisReglement().getReference() + ").");
            }
        }

        List<LigneNoteFraisRequest> lignesReq = camions.stream()
            .map(c -> new LigneNoteFraisRequest(
                detteEstimee(c), COMPTE_FOURNISSEURS,
                "Camion " + c.getPlaque() + " - " + c.getArticle().getLibelle(),
                false, null, null, null, null, false, null, null))
            .toList();
        String objet = camions.size() == 1
            ? "Règlement fournisseur minerais - camion " + camions.get(0).getPlaque()
            : "Règlement fournisseur minerais - " + camions.size() + " camions";

        NoteFrais note = NoteFrais.builder()
            .reference(referenceGenerator.pourNoteFrais())
            .objet(objet)
            .beneficiaire(req.beneficiaire())
            .description(req.description())
            .montant(BigDecimal.ZERO)
            // Les montants des camions (prix d'achat, dette fournisseur) sont
            // deja en devise de base (USD) : aucune conversion a faire.
            .devise(ConversionDeviseService.DEVISE_BASE)
            .statut(StatutNote.BROUILLON)
            .sens(SensTransaction.DECAISSEMENT)
            .createur(auteur)
            .build();
        construireLignes(note, lignesReq);
        note.recalculerMontant();
        note.addObservation(observation(note, auteur, StatutNote.BROUILLON, "Creation de la note"));
        note = noteRepository.save(note);

        for (CamionMinerai camion : camions) {
            camion.setNoteFraisReglement(note);
        }
        camionRepository.saveAll(camions);

        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.SOUMISE, null, "Soumission au DFIN");
        notificationService.notifierRole(RoleType.DFIN, TypeNotification.NOTE_SOUMISE,
            "Note à vérifier", note.getReference() + " — " + note.getObjet(),
            "/notes-frais/" + note.getId(), note);

        log.info("Note de reglement camions minerais creee et soumise [ref={}, montant={}, camions={}, par={}]",
            note.getReference(), note.getMontant(), camions.size(), auteur.getEmail());
        return reponse;
    }

    /** Compte Fournisseurs, meme convention que {@code MineraiService.COMPTE_FOURNISSEURS}. */
    private static final String COMPTE_FOURNISSEURS = "4011";

    /**
     * Dette fournisseur reelle (achat deja valide) ou, pour un camion encore
     * A_VALIDER, estimee TTC au taux de TVA du jour de reception — meme
     * calcul que {@code MineraiService.detteEstimee}, duplique ici plutot
     * qu'expose : ce service ne reagit qu'aux repositories des domaines
     * qu'il touche (voir {@code ArticleRepository}, {@code
     * EntrepotRepository}), jamais a leurs services.
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

    /**
     * Le responsable restaurant ne cree jamais de note de frais libre : sa
     * seule note "speciale" possible est un achat de boissons — une ligne
     * unique, achat de marchandise, article BOISSON, en decaissement. Toute
     * autre forme (plusieurs lignes, depense libre, article d'un autre type)
     * est refusee, sans quoi le role deviendrait une porte d'entree generale
     * vers les notes de frais.
     */
    private void validerNoteRespRestaurant(NoteFrais note) {
        if (!estRespRestaurantSeul()) {
            return;
        }
        if (note.getSens() != SensTransaction.DECAISSEMENT) {
            throw new IllegalArgumentException(
                "Le responsable restaurant ne peut créer qu'une note d'achat de boissons (décaissement)");
        }
        if (note.getLignes().size() != 1) {
            throw new IllegalArgumentException(
                "Une note d'achat de boissons ne comporte qu'une seule ligne");
        }
        LigneNoteFrais ligne = note.getLignes().get(0);
        if (!ligne.isAchatMarchandise() || ligne.getArticle() == null
            || ligne.getArticle().getType() != TypeArticle.BOISSON) {
            throw new IllegalArgumentException(
                "Le responsable restaurant ne peut créer qu'une note d'achat de boissons "
                    + "(achat de marchandise, article de type BOISSON)");
        }
        if (ligne.isEchangeConsigne()
            && !emballageBoissonRepository.existsByArticleBoissonId(ligne.getArticle().getId())) {
            throw new IllegalArgumentException(
                "La boisson \"" + ligne.getArticle().getLibelle() + "\" n'a pas de conditionnement défini : "
                    + "créez-le avant de demander l'échange de consigne");
        }
    }

    /** true si l'utilisateur courant n'a QUE le role RESP_RESTAURANT (pas COMPTABLE/CAISSIER/DFIN/DA/DG/ADMIN). */
    private boolean estRespRestaurantSeul() {
        var authorities = currentUser.requirePrincipal().getAuthorities();
        boolean estRespRestaurant = authorities.stream()
            .anyMatch(a -> "ROLE_RESP_RESTAURANT".equals(a.getAuthority()));
        return estRespRestaurant && authorities.stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                         || "ROLE_DG".equals(a.getAuthority())
                         || "ROLE_DA".equals(a.getAuthority())
                         || "ROLE_DFIN".equals(a.getAuthority())
                         || "ROLE_CAISSIER".equals(a.getAuthority())
                         || "ROLE_COMPTABLE".equals(a.getAuthority()));
    }

    /**
     * Le DFIN reaffecte le compte d'imputation d'une ou plusieurs lignes lors
     * de la verification d'une note soumise (SOUMISE), sans devoir en etre le
     * createur : il corrige ainsi une erreur d'imputation avant transmission
     * au DA, sans renvoyer la note a l'employe pour un nouveau cycle de
     * soumission. N'altere ni le montant des lignes ni l'etat du workflow.
     */
    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse modifierComptesLignes(Long id, ModifierComptesRequest req) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.SOUMISE, "modifier les comptes d'imputation");

        User auteur = currentUser.requireUser();
        StringBuilder trace = new StringBuilder("Comptes d'imputation modifies par le DFIN : ");
        for (int i = 0; i < req.lignes().size(); i++) {
            LigneCompteRequest ligneReq = req.lignes().get(i);
            LigneNoteFrais ligne = note.getLignes().stream()
                .filter(l -> l.getId().equals(ligneReq.ligneId()))
                .findFirst()
                .orElseThrow(() -> RessourceIntrouvableException.of("LigneNoteFrais", ligneReq.ligneId()));
            CompteOHADA nouveauCompte = resoudreCompte(ligneReq.compteImputation());
            String ancien = ligne.getCompteImputation() != null ? ligne.getCompteImputation().getNumero() : "(aucun)";
            String nouveau = nouveauCompte != null ? nouveauCompte.getNumero() : "(aucun)";
            ligne.setCompteImputation(nouveauCompte);
            if (i > 0) trace.append(" ; ");
            trace.append("ligne ").append(ligne.getId()).append(" : ").append(ancien).append(" -> ").append(nouveau);
        }
        note.addObservation(observation(note, auteur, note.getStatut(), trace.toString()));
        note = noteRepository.saveAndFlush(note);

        log.info("Note {} : comptes d'imputation modifies par le DFIN (par {})",
            note.getReference(), auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    /** Construit et rattache les lignes de depense a une note neuve. */
    private void construireLignes(NoteFrais note, List<LigneNoteFraisRequest> lignesReq) {
        for (LigneNoteFrais l : creerLignes(lignesReq)) {
            note.addLigne(l);
        }
    }

    /** Compte de TVA récupérable sur achats, retenu automatiquement pour tout achat de marchandise soumis à la TVA. */
    private static final String COMPTE_TVA_ACHATS = "4452";
    /** Compte de stock par défaut d'un article de marchandise créé à la volée depuis une note de frais. */
    private static final String COMPTE_STOCK_PAR_DEFAUT = "3111";

    private List<LigneNoteFrais> creerLignes(List<LigneNoteFraisRequest> lignesReq) {
        List<LigneNoteFrais> lignes = new ArrayList<>();
        int ordre = 1;
        for (LigneNoteFraisRequest l : lignesReq) {
            boolean soumisTva = Boolean.TRUE.equals(l.soumisTva());

            boolean achatMarchandise = Boolean.TRUE.equals(l.achatMarchandise());
            CompteOHADA compteImputation;
            Article article = null;
            Entrepot entrepot = null;
            if (achatMarchandise) {
                if ((l.articleId() == null && !StringUtils.hasText(l.articleNom()))
                    || l.quantiteMarchandise() == null || l.quantiteMarchandise().signum() <= 0) {
                    throw new IllegalArgumentException(
                        "Un achat de marchandise nécessite un article et une quantité positive.");
                }
                article = l.articleId() != null
                    ? articleRepository.findById(l.articleId())
                        .orElseThrow(() -> RessourceIntrouvableException.of("Article", l.articleId()))
                    : trouverOuCreerArticle(l.articleNom());
                if (article.getType() == TypeArticle.SERVICE) {
                    throw new IllegalArgumentException(
                        "L'article \"" + article.getLibelle() + "\" est un service : il n'a pas de stock.");
                }
                if (article.getCompteStock() == null || article.getCompteCharge() == null) {
                    throw new IllegalArgumentException(
                        "Le compte de stock et le compte de variation de stock doivent être définis pour l'article \""
                        + article.getLibelle() + "\".");
                }
                if (article.getCompteAchat() == null) {
                    throw new IllegalArgumentException(
                        "Aucun compte d'achat n'est défini pour l'article \"" + article.getLibelle() + "\".");
                }
                // L'entrepot n'est plus choisi par l'utilisateur : une seule
                // destination possible en pratique, retenue automatiquement.
                entrepot = l.entrepotId() != null
                    ? entrepotRepository.findById(l.entrepotId())
                        .orElseThrow(() -> RessourceIntrouvableException.of("Entrepot", l.entrepotId()))
                    : entrepotRepository.findFirstByActifTrueOrderByIdAsc()
                        .orElseThrow(() -> new IllegalStateException(
                            "Aucun entrepôt actif n'est configuré : impossible de réceptionner la marchandise."));
                // Le compte d'ACHAT (601x) est débité au paiement — jamais le
                // compte de stock : un achat au comptant s'impute en deux
                // écritures indissociables, conformes à l'inventaire permanent
                // du SYSCOHADA révisé (D 601x/C règlement ici, puis D 311x/C
                // 6031 à l'entrée en stock — voir StockService
                // .entreesDepuisNoteFraisInterne). Imputer directement le
                // compte de stock au règlement (comme une version antérieure
                // le faisait) produisait un bilan juste mais un compte de
                // résultat faux : ni l'achat (601) ni sa variation de stock
                // (6031) n'y apparaissaient jamais.
                compteImputation = article.getCompteAchat();
            } else {
                compteImputation = resoudreCompte(l.compteImputation());
            }

            lignes.add(LigneNoteFrais.builder()
                .montant(l.montant())
                .compteImputation(compteImputation)
                .description(l.description())
                .ordre(ordre++)
                .achatMarchandise(achatMarchandise)
                .quantiteMarchandise(achatMarchandise ? l.quantiteMarchandise() : null)
                .article(article)
                .entrepot(entrepot)
                .echangeConsigne(achatMarchandise && Boolean.TRUE.equals(l.echangeConsigne()))
                .soumisTva(soumisTva)
                // Le compte de TVA récupérable n'est plus choisi par
                // l'utilisateur : 4452 est le seul compte pertinent pour une
                // TVA récupérable sur achat de marchandise.
                .compteTva(soumisTva ? resoudreCompte(COMPTE_TVA_ACHATS) : null)
                // Figé à la saisie : le montant TTC annoncé sur la note ne
                // doit pas bouger si le taux légal change avant le paiement.
                .tauxTvaApplique(soumisTva ? tauxTvaService.tauxALaDate(java.time.LocalDate.now()) : null)
                .build());
        }
        return lignes;
    }

    // ---------------------------------------------------------------------
    // Pieces jointes (PDF / image)
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('DIRECTEUR', 'COMPTABLE', 'CAISSIER', 'DFIN', 'DA', 'RESP_RESTAURANT', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse ajouterPieceJointe(Long id, MultipartFile fichier) {
        NoteFrais note = chargerVisible(id);

        if (fichier == null || fichier.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide ou absent");
        }
        if (fichier.getSize() > TAILLE_MAX_OCTETS) {
            throw new IllegalArgumentException("Le fichier depasse la taille maximale autorisee (20 Mo)");
        }
        String type = fichier.getContentType();
        if (type == null || !MIME_AUTORISES.contains(type.toLowerCase())) {
            throw new IllegalArgumentException(
                "Type de fichier non autorise. Formats acceptes : PDF, JPEG, PNG, WEBP, GIF");
        }

        String cheminStockage = storage.enregistrer("notes-frais/" + note.getId(), fichier);
        User auteur = currentUser.requireUser();

        String nomFichier = StringUtils.hasText(fichier.getOriginalFilename())
            ? fichier.getOriginalFilename() : "fichier";
        PieceJointe pj = PieceJointe.builder()
            .nomFichier(nomFichier)
            .typeMime(type)
            .taille(fichier.getSize())
            .cheminStockage(cheminStockage)
            .ajoutePar(auteur)
            .build();
        note.addPieceJointe(pj);
        note.addObservation(observation(note, auteur, note.getStatut(), "Piece jointe ajoutee : " + nomFichier));
        note = noteRepository.saveAndFlush(note);

        log.info("Piece jointe ajoutee [note={}, fichier={}, par={}]",
            note.getReference(), nomFichier, auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    @PreAuthorize("hasAnyRole('DIRECTEUR', 'COMPTABLE', 'CAISSIER', 'DFIN', 'DA', 'RESP_RESTAURANT', 'ADMIN')")
    @Transactional(readOnly = true)
    public PieceJointeTelechargement telechargerPieceJointe(Long noteId, Long pieceId) {
        NoteFrais note = chargerVisible(noteId);
        PieceJointe pj = note.getPiecesJointes().stream()
            .filter(p -> p.getId().equals(pieceId))
            .findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("PieceJointe", pieceId));
        Resource ressource = storage.charger(pj.getCheminStockage());
        return new PieceJointeTelechargement(ressource, pj.getNomFichier(), pj.getTypeMime());
    }

    /** Enveloppe le contenu d'une piece jointe pour le controleur (flux + metadonnees HTTP). */
    public record PieceJointeTelechargement(Resource ressource, String nomFichier, String typeMime) {}

    @PreAuthorize("hasAnyRole('DIRECTEUR', 'COMPTABLE', 'CAISSIER', 'DFIN', 'DA', 'RESP_RESTAURANT', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse supprimerPieceJointe(Long noteId, Long pieceId) {
        NoteFrais note = charger(noteId);
        PieceJointe pj = note.getPiecesJointes().stream()
            .filter(p -> p.getId().equals(pieceId))
            .findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("PieceJointe", pieceId));

        // Seul le createur de la note, l'auteur de l'ajout ou un ADMIN peut supprimer.
        Long courant = currentUser.requireUserId();
        var authorities = currentUser.requirePrincipal().getAuthorities();
        boolean admin = authorities.stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        boolean createurNote = note.getCreateur() != null && note.getCreateur().getId().equals(courant);
        boolean auteurAjout = pj.getAjoutePar() != null && pj.getAjoutePar().getId().equals(courant);
        if (!admin && !createurNote && !auteurAjout) {
            throw new TransitionInvalideException(
                "Seul le createur de la note, l'auteur de l'ajout ou un administrateur peut supprimer cette piece jointe");
        }

        note.getPiecesJointes().remove(pj);
        storage.supprimer(pj.getCheminStockage());

        log.info("Piece jointe supprimee [note={}, fichier={}]", note.getReference(), pj.getNomFichier());
        return NoteFraisDetailResponse.from(note);
    }

    /** Charge la note et verifie sa visibilite pour l'utilisateur courant (anti-IDOR). */
    private NoteFrais chargerVisible(Long id) {
        NoteFrais note = charger(id);
        if (!estVisiblePourUtilisateur(note)) {
            throw RessourceIntrouvableException.of("NoteFrais", id);
        }
        return note;
    }

    // ---------------------------------------------------------------------
    // Transitions de workflow
    // ---------------------------------------------------------------------

    /** Le createur soumet sa note au DFIN (BROUILLON|REJETEE_DA -> SOUMISE). */
    @PreAuthorize("hasAnyRole('COMPTABLE', 'CAISSIER', 'RESP_RESTAURANT')")
    @Transactional
    public NoteFraisDetailResponse soumettre(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        if (note.getSens() == SensTransaction.ENCAISSEMENT) {
            throw new TransitionInvalideException(
                "Une note d'encaissement ne suit pas le circuit DFIN/DA : utilisez l'action "
                    + "\"encaisser\" pour l'executer directement");
        }
        if (note.getStatut() != StatutNote.BROUILLON && note.getStatut() != StatutNote.REJETEE_DA) {
            throw new TransitionInvalideException(
                "Seule une note en BROUILLON ou REJETEE_DA peut etre soumise (etat actuel : "
                    + note.getStatut() + ")");
        }
        exigerCreateur(note);
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.SOUMISE, action, "Soumission au DFIN");
        notificationService.notifierRole(RoleType.DFIN, TypeNotification.NOTE_SOUMISE,
            "Note à vérifier", note.getReference() + " — " + note.getObjet(),
            "/notes-frais/" + note.getId(), note);
        return reponse;
    }

    /** Le DFIN verifie la conformite (SOUMISE -> VERIFIEE_DFIN). */
    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse verifier(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.SOUMISE, "verifier");
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.VERIFIEE_DFIN, action, "Verifiee par le DFIN");
        notificationService.notifierRole(RoleType.DA, TypeNotification.NOTE_VERIFIEE,
            "Note à valider", note.getReference() + " — " + note.getObjet(),
            "/notes-frais/" + note.getId(), note);
        return reponse;
    }

    /** Le DA valide la note (VERIFIEE_DFIN -> VALIDEE_DA). */
    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse valider(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.VERIFIEE_DFIN, "valider");
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.VALIDEE_DA, action, "Validee par le DA");
        notificationService.notifierRole(RoleType.DFIN, TypeNotification.NOTE_VALIDEE,
            "Note validée par le DA", note.getReference() + " — " + note.getObjet() + " (prête à transmettre)",
            "/notes-frais/" + note.getId(), note);
        return reponse;
    }

    /** Le DA rejette la note ; un motif est obligatoire (VERIFIEE_DFIN -> REJETEE_DA). */
    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse rejeter(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.VERIFIEE_DFIN, "rejeter");
        if (!StringUtils.hasText(action.commentaire())) {
            throw new IllegalArgumentException("Un motif de rejet est obligatoire");
        }
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.REJETEE_DA, action, "Rejetee par le DA");
        notificationService.notifierUtilisateur(note.getCreateur(), TypeNotification.NOTE_REJETEE,
            "Note rejetée", note.getReference() + " — " + action.commentaire(),
            "/notes-frais/" + note.getId(), note);
        return reponse;
    }

    /** Le DFIN transmet la note validee a la caisse (VALIDEE_DA -> TRANSMISE_CAISSE). */
    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse transmettre(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.VALIDEE_DA, "transmettre a la caisse");
        // Gel du taux d'engagement : la note part en tresorerie a la valeur du
        // jour ou l'entreprise s'engage. L'ecart avec le taux du reglement sera
        // constate en 676/776 par EcartChangeService, au lieu d'etre absorbe
        // silencieusement par le compte de charge.
        if (note.getDevise() != null && note.getDevise() != ConversionDeviseService.DEVISE_BASE
            && note.getTauxEngagement() == null) {
            note.setTauxEngagement(conversionDevise.tauxCourant());
        }
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.TRANSMISE_CAISSE, action, "Transmise a la caisse");
        notificationService.notifierRole(RoleType.CAISSIER, TypeNotification.NOTE_TRANSMISE,
            "Note à payer", note.getReference() + " — " + note.getObjet(),
            "/notes-frais/" + note.getId(), note);
        return reponse;
    }

    /**
     * Le DA definit (ou ajuste) la priorite de paiement sur une note validee
     * ou deja transmise a la caisse (reajustement en cours de traitement).
     * N'altere pas l'etat du workflow.
     */
    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse definirPriorite(Long id, PrioriteRequest req) {
        NoteFrais note = charger(id);
        if (note.getStatut() != StatutNote.VALIDEE_DA
                && note.getStatut() != StatutNote.TRANSMISE_CAISSE) {
            throw new TransitionInvalideException(
                "La priorite ne peut etre definie que sur une note VALIDEE_DA ou TRANSMISE_CAISSE (etat actuel : "
                    + note.getStatut() + ")");
        }

        PrioriteNote priorite = req.priorite();
        note.setPriorite(priorite);

        User auteur = currentUser.requireUser();
        String libelle = "Priorite definie : " + priorite;
        String trace = StringUtils.hasText(req.commentaire())
            ? libelle + " - " + req.commentaire() : libelle;
        note.addObservation(observation(note, auteur, note.getStatut(), trace));

        log.info("Note {} : priorite definie a {} (par {})",
            note.getReference(), priorite, auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    /**
     * Le DA ajoute une observation libre sur une note validee, sans changer d'etat.
     * Permet de completer le dossier apres avoir defini la priorite.
     */
    @PreAuthorize("hasAnyRole('DA', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse ajouterObservation(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        exigerEtat(note, StatutNote.VALIDEE_DA, "ajouter une observation");
        if (action == null || !StringUtils.hasText(action.commentaire())) {
            throw new IllegalArgumentException("L'observation ne peut pas etre vide");
        }
        User auteur = currentUser.requireUser();
        note.addObservation(observation(note, auteur, note.getStatut(), action.commentaire()));

        log.info("Note {} : observation ajoutee (par {})",
            note.getReference(), auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    /** Annulation par le createur ou le DFIN (etats non terminaux -> ANNULEE). */
    @PreAuthorize("hasAnyRole('DIRECTEUR', 'COMPTABLE', 'CAISSIER', 'DFIN', 'RESP_RESTAURANT', 'LOGISTIQUE', 'ADMIN')")
    @Transactional
    public NoteFraisDetailResponse annuler(Long id, ActionWorkflowRequest action) {
        NoteFrais note = charger(id);
        if (!ANNULABLES.contains(note.getStatut())) {
            throw new TransitionInvalideException(
                "Une note " + note.getStatut() + " ne peut plus etre annulee");
        }
        // DIRECTEUR/CAISSIER/RESP_RESTAURANT : seulement leurs propres notes.
        // DFIN/ADMIN : toutes les notes.
        var authorities = currentUser.requirePrincipal().getAuthorities();
        boolean estDfinOuAdmin = authorities.stream()
            .anyMatch(a -> "ROLE_DFIN".equals(a.getAuthority())
                        || "ROLE_ADMIN".equals(a.getAuthority()));
        if (!estDfinOuAdmin) {
            exigerCreateur(note);
        }
        NoteFraisDetailResponse reponse = appliquer(note, StatutNote.ANNULEE, action, "Annulation de la note");
        // Note de reglement de camions minerais : les camions rattaches
        // redeviennent disponibles pour une nouvelle demande — voir
        // MineraiService.listerARegler, qui les exclurait sinon indefiniment.
        List<CamionMinerai> camionsLies = camionRepository.findByNoteFraisReglementId(note.getId());
        if (!camionsLies.isEmpty()) {
            camionsLies.forEach(c -> c.setNoteFraisReglement(null));
            camionRepository.saveAll(camionsLies);
        }
        // Pas d'auto-notification : si le createur annule lui-meme sa note,
        // il n'a pas besoin d'etre informe de sa propre action.
        boolean annuleeParAutrui = note.getCreateur() != null
            && !note.getCreateur().getId().equals(currentUser.requireUserId());
        if (annuleeParAutrui) {
            notificationService.notifierUtilisateur(note.getCreateur(), TypeNotification.NOTE_ANNULEE,
                "Note annulée", note.getReference() + " — " + note.getObjet(),
                "/notes-frais/" + note.getId(), note);
        }
        return reponse;
    }

    // ---------------------------------------------------------------------
    // Helpers internes
    // ---------------------------------------------------------------------

    private NoteFraisDetailResponse appliquer(NoteFrais note, StatutNote cible,
                                              ActionWorkflowRequest action, String libelle) {
        User auteur = currentUser.requireUser();
        StatutNote precedent = note.getStatut();
        note.setStatut(cible);

        String commentaire = action == null ? null : action.commentaire();
        String trace = StringUtils.hasText(commentaire) ? libelle + " : " + commentaire : libelle;
        note.addObservation(observation(note, auteur, cible, trace));
        note = noteRepository.saveAndFlush(note);

        log.info("Note {} : {} -> {} (par {})",
            note.getReference(), precedent, cible, auteur.getEmail());
        return NoteFraisDetailResponse.from(note);
    }

    private NoteFrais charger(Long id) {
        return noteRepository.findWithDetailsById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", id));
    }

    private CompteOHADA resoudreCompte(String numero) {
        if (!StringUtils.hasText(numero)) {
            return null;
        }
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    /**
     * Retrouve un article de marchandise existant par son nom, ou le crée à
     * la volée : l'achat d'une marchandise ne nécessite plus de passer par
     * l'écran Logistique au préalable. Le compte de stock par défaut
     * ({@value #COMPTE_STOCK_PAR_DEFAUT}) reste modifiable ensuite depuis
     * la fiche article, au même titre qu'un article créé normalement.
     */
    private Article trouverOuCreerArticle(String nom) {
        String libelle = nom.trim();
        return articleRepository.findFirstByLibelleIgnoreCaseAndType(libelle, TypeArticle.MARCHANDISE)
            .orElseGet(() -> articleRepository.save(Article.builder()
                .code(genererCodeArticle(libelle))
                .libelle(libelle)
                .type(TypeArticle.MARCHANDISE)
                .compteStock(resoudreCompte(COMPTE_STOCK_PAR_DEFAUT))
                .soumisTva(true)
                .stockMin(java.math.BigDecimal.ZERO)
                .actif(true)
                .build()));
    }

    private String genererCodeArticle(String libelle) {
        String base = libelle.toUpperCase()
            .replaceAll("[^A-Z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "ART";
        }
        if (base.length() > 30) {
            base = base.substring(0, 30);
        }
        String code = base;
        int suffixe = 2;
        while (articleRepository.existsByCode(code)) {
            code = base + "-" + suffixe++;
        }
        return code;
    }

    private void exigerEtat(NoteFrais note, StatutNote attendu, String operation) {
        if (note.getStatut() != attendu) {
            throw new TransitionInvalideException(
                "Impossible de " + operation + " : la note doit etre " + attendu
                    + " (etat actuel : " + note.getStatut() + ")");
        }
    }

    private void exigerCreateur(NoteFrais note) {
        Long courant = currentUser.requireUserId();
        if (note.getCreateur() == null || !note.getCreateur().getId().equals(courant)) {
            throw new TransitionInvalideException(
                "Seul le createur de la note peut effectuer cette action");
        }
    }

    private void exigerRoleCaissierOuAdmin() {
        boolean autorise = currentUser.requirePrincipal().getAuthorities().stream()
            .anyMatch(a -> "ROLE_CAISSIER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
        if (!autorise) {
            throw new TransitionInvalideException(
                "Seul un caissier peut emettre une note d'encaissement");
        }
    }

    private ObservationNote observation(NoteFrais note, User auteur, StatutNote statut, String texte) {
        return ObservationNote.builder()
            .noteFrais(note)
            .auteur(auteur)
            .statutAuMoment(statut)
            .commentaire(texte)
            .build();
    }
}
