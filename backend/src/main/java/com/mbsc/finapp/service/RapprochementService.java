package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.LigneReleve;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.PointageRapprochement;
import com.mbsc.finapp.domain.ReleveBancaire;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.StatutReleve;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.rapprochement.LigneReleveRequest;
import com.mbsc.finapp.dto.rapprochement.PointageRequest;
import com.mbsc.finapp.dto.rapprochement.RapprochementsResponse;
import com.mbsc.finapp.dto.rapprochement.RegularisationRequest;
import com.mbsc.finapp.dto.rapprochement.ReleveDetailResponse;
import com.mbsc.finapp.dto.rapprochement.ReleveRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EtablissementTresorerieRepository;
import com.mbsc.finapp.repository.PointageRapprochementRepository;
import com.mbsc.finapp.repository.ReleveBancaireRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rapprochement bancaire : chaque relevé mensuel d'un établissement (banque 521x, mobile money 552x) est
 * pointé contre les écritures de son compte au grand livre, dans la devise du compte (USD ou CDF).
 *
 * <p><b>État de rapprochement</b> : solde du relevé − opérations du relevé non pointées = solde comptable
 * − écritures non pointées. L'écart doit être nul pour valider. Les écritures non pointées (chèques émis
 * pas encore débités, remises pas encore créditées) restent en suspens et reviennent sur le relevé du mois
 * suivant. Les écritures antérieures au premier relevé de l'établissement sont réputées couvertes par son
 * solde d'ouverture.</p>
 *
 * <p>Un relevé validé verrouille le compte jusqu'à la fin de son mois : voir
 * {@code ComptabiliteService.verifierNonRapproche}, appelé avant toute écriture.</p>
 */
@Service
@RequiredArgsConstructor
public class RapprochementService {

    private static final Logger log = LoggerFactory.getLogger(RapprochementService.class);

    static final String LECTURE = "hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')";
    static final String ECRITURE = "hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'ADMIN')";
    /** Écart de dates toléré par le pointage automatique (jours). */
    static final int TOLERANCE_JOURS = 5;

    private final ReleveBancaireRepository releveRepository;
    private final PointageRapprochementRepository pointageRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final EtablissementTresorerieRepository etablissementRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final ConversionDeviseService conversion;
    private final CurrentUserProvider currentUser;

    // =====================================================================
    // Consultation
    // =====================================================================

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public RapprochementsResponse lister(int annee) {
        List<ReleveDetailResponse.Etablissement> etablissements = new ArrayList<>();
        etablissementRepository.findAllAvecCompte().stream()
            .filter(e -> e.getType() == TypeEtablissement.BANQUE || e.getType() == TypeEtablissement.MOBILE_MONEY)
            .sorted(Comparator.comparing((EtablissementTresorerie e) -> e.getType()).thenComparing(EtablissementTresorerie::getNom))
            .forEach(e -> etablissements.add(etablissement(e)));
        List<RapprochementsResponse.Resume> releves = releveRepository.findByAnnee(annee).stream()
            .map(r -> new RapprochementsResponse.Resume(r.getId(), r.getEtablissement().getId(), r.getMois(), r.getStatut(),
                r.getSoldeCloture(), r.getLignes().size(), (int) r.getLignes().stream().filter(l -> l.getPointage() == null).count()))
            .toList();
        return new RapprochementsResponse(annee, etablissements, releves);
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public ReleveDetailResponse consulter(Long id) {
        return detail(charger(id));
    }

    // =====================================================================
    // Création et modification du relevé
    // =====================================================================

    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse creer(ReleveRequest req) {
        EtablissementTresorerie etab = etablissementRepository.findById(req.etablissementId())
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", req.etablissementId()));
        if (etab.getType() != TypeEtablissement.BANQUE && etab.getType() != TypeEtablissement.MOBILE_MONEY) {
            throw new IllegalArgumentException("Seuls les comptes de banque et de mobile money se rapprochent d'un relevé.");
        }
        releveRepository.findByEtablissementIdAndAnneeAndMois(etab.getId(), req.annee(), req.mois()).ifPresent(r -> {
            throw new TransitionInvalideException("Un relevé existe déjà pour " + etab.getNom() + " en "
                + periode(req.mois(), req.annee()) + " : complétez-le ou supprimez-le.");
        });
        ReleveBancaire r = ReleveBancaire.builder()
            .etablissement(etab)
            .mois(req.mois())
            .annee(req.annee())
            .devise(etab.getDevise() == null ? Devise.USD : etab.getDevise())
            .soldeOuverture(req.soldeOuverture())
            .soldeCloture(req.soldeCloture())
            .source(StringUtils.hasText(req.source()) ? req.source().trim() : "Saisie manuelle")
            .creePar(currentUser.requireUser())
            .build();
        ajouterLignes(r, req.lignes());
        r = releveRepository.save(r);
        log.info("Releve cree [etablissement={}, periode={}, lignes={}]", etab.getNom(), periode(r.getMois(), r.getAnnee()),
            r.getLignes().size());
        return detail(r);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse modifierSoldes(Long id, BigDecimal ouverture, BigDecimal cloture) {
        ReleveBancaire r = chargerEnCours(id);
        if (ouverture != null) r.setSoldeOuverture(ouverture);
        if (cloture != null) r.setSoldeCloture(cloture);
        return detail(r);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse ajouterLigne(Long id, LigneReleveRequest ligne) {
        ReleveBancaire r = chargerEnCours(id);
        ajouterLignes(r, List.of(ligne));
        return detail(releveRepository.saveAndFlush(r));
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse supprimerLigne(Long id, Long ligneId) {
        ReleveBancaire r = chargerEnCours(id);
        LigneReleve l = ligne(r, ligneId);
        if (l.getPointage() != null) {
            throw new TransitionInvalideException("Dépointez d'abord cette ligne avant de la supprimer.");
        }
        r.getLignes().remove(l);
        return detail(r);
    }

    /** Supprime un relevé non validé ; ses pointages sont défaits, les pièces de régularisation restent. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public void supprimer(Long id) {
        ReleveBancaire r = chargerEnCours(id);
        for (LigneReleve l : r.getLignes()) {
            if (l.getPointage() != null) depointerInterne(r, l.getPointage());
        }
        releveRepository.delete(r);
        log.info("Releve supprime [id={}]", id);
    }

    // =====================================================================
    // Pointage
    // =====================================================================

    /**
     * Pointage automatique : chaque ligne non pointée est rapprochée de l'écriture non pointée de même montant
     * et de même sens, à {@value #TOLERANCE_JOURS} jours près. Entre plusieurs candidates, la référence du
     * relevé retrouvée dans le libellé de l'écriture, puis la date la plus proche, départagent ; une égalité
     * reste à pointer à la main.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse pointerAutomatiquement(Long id) {
        ReleveBancaire r = chargerEnCours(id);
        Devise devise = r.getDevise();
        List<EcritureGrandLivre> libres = new ArrayList<>(candidates(r).stream().filter(e -> e.getPointage() == null).toList());
        int pointees = 0;
        List<LigneReleve> lignes = r.getLignes().stream().filter(l -> l.getPointage() == null)
            .sorted(Comparator.comparing(LigneReleve::getDateOperation)).toList();
        for (LigneReleve l : lignes) {
            BigDecimal net = l.net();
            List<EcritureGrandLivre> candidates = libres.stream()
                .filter(e -> montantCompte(e, devise).compareTo(net) == 0)
                .filter(e -> Math.abs(ChronoUnit.DAYS.between(e.getDateEcriture(), l.getDateOperation())) <= TOLERANCE_JOURS)
                .toList();
            EcritureGrandLivre choisie = choisir(l, candidates);
            if (choisie == null) continue;
            PointageRapprochement p = pointageRepository.save(PointageRapprochement.builder().releve(r).automatique(true).build());
            l.setPointage(p);
            choisie.setPointage(p);
            libres.remove(choisie);
            pointees++;
        }
        log.info("Pointage automatique [releve={}, lignes pointees={}]", id, pointees);
        return detail(r);
    }

    static EcritureGrandLivre choisir(LigneReleve l, List<EcritureGrandLivre> candidates) {
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        if (StringUtils.hasText(l.getReference())) {
            String ref = l.getReference().trim().toLowerCase(Locale.ROOT);
            List<EcritureGrandLivre> parReference = candidates.stream()
                .filter(e -> contient(e.getLibelle(), ref) || (e.getPiece() != null && contient(e.getPiece().getReference(), ref)))
                .toList();
            if (parReference.size() == 1) return parReference.get(0);
        }
        long meilleur = candidates.stream()
            .mapToLong(e -> Math.abs(ChronoUnit.DAYS.between(e.getDateEcriture(), l.getDateOperation()))).min().orElse(0);
        List<EcritureGrandLivre> proches = candidates.stream()
            .filter(e -> Math.abs(ChronoUnit.DAYS.between(e.getDateEcriture(), l.getDateOperation())) == meilleur).toList();
        return proches.size() == 1 ? proches.get(0) : null;
    }

    /** Pointage manuel : les lignes et les écritures choisies doivent avoir le même total net. */
    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse pointer(Long id, PointageRequest req) {
        ReleveBancaire r = chargerEnCours(id);
        List<LigneReleve> lignes = new ArrayList<>();
        for (Long ligneId : new HashSet<>(req.ligneIds())) {
            LigneReleve l = ligne(r, ligneId);
            if (l.getPointage() != null) {
                throw new TransitionInvalideException("La ligne du " + l.getDateOperation() + " « " + l.getLibelle() + " » est déjà pointée.");
            }
            lignes.add(l);
        }
        Map<Long, EcritureGrandLivre> parId = new HashMap<>();
        candidates(r).forEach(e -> parId.put(e.getId(), e));
        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        for (Long ecritureId : new HashSet<>(req.ecritureIds())) {
            EcritureGrandLivre e = parId.get(ecritureId);
            if (e == null) {
                throw new TransitionInvalideException("L'écriture #" + ecritureId + " n'est pas une écriture à rapprocher de ce compte.");
            }
            if (e.getPointage() != null) {
                throw new TransitionInvalideException("L'écriture « " + e.getLibelle() + " » est déjà pointée.");
            }
            ecritures.add(e);
        }
        BigDecimal totalLignes = lignes.stream().map(LigneReleve::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEcritures = ecritures.stream().map(e -> montantCompte(e, r.getDevise())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalLignes.compareTo(totalEcritures) != 0) {
            throw new TransitionInvalideException("Les montants ne correspondent pas : relevé " + totalLignes.toPlainString()
                + " " + r.getDevise() + ", écritures " + totalEcritures.toPlainString() + " " + r.getDevise() + ".");
        }
        PointageRapprochement p = pointageRepository.save(PointageRapprochement.builder().releve(r).automatique(false).build());
        lignes.forEach(l -> l.setPointage(p));
        ecritures.forEach(e -> e.setPointage(p));
        return detail(r);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse depointer(Long id, Long pointageId) {
        ReleveBancaire r = chargerEnCours(id);
        PointageRapprochement p = pointageRepository.findById(pointageId)
            .filter(x -> x.getReleve().getId().equals(r.getId()))
            .orElseThrow(() -> RessourceIntrouvableException.of("PointageRapprochement", pointageId));
        depointerInterne(r, p);
        return detail(r);
    }

    private void depointerInterne(ReleveBancaire r, PointageRapprochement p) {
        r.getLignes().stream().filter(l -> l.getPointage() != null && l.getPointage().getId().equals(p.getId()))
            .forEach(l -> l.setPointage(null));
        ecritureRepository.findByPointageId(p.getId()).forEach(e -> e.setPointage(null));
        pointageRepository.delete(p);
    }

    // =====================================================================
    // Régularisation
    // =====================================================================

    /**
     * Crée l'écriture d'une opération du relevé absente de la comptabilité (frais, commissions, agios,
     * intérêts, virement reçu à identifier) et la pointe aussitôt. Entrée : débit du compte de trésorerie,
     * crédit de la contrepartie ; sortie : l'inverse. Journal Banque ou Mobile money, à la date de
     * l'opération. Pour un compte en CDF, la pièce est en USD au taux du jour de l'opération, le montant en
     * francs restant tracé sur chaque ligne.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public ReleveDetailResponse regulariser(Long id, Long ligneId, RegularisationRequest req) {
        ReleveBancaire r = chargerEnCours(id);
        LigneReleve l = ligne(r, ligneId);
        if (l.getPointage() != null) {
            throw new TransitionInvalideException("Cette ligne est déjà pointée.");
        }
        CompteOHADA tresorerie = r.getEtablissement().getCompte();
        CompteOHADA contrepartie = compteRepository.findByNumero(req.compteContrepartie().trim())
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", req.compteContrepartie()));
        if (contrepartie.getNumero().startsWith("5")) {
            throw new IllegalArgumentException("La contrepartie ne peut pas être un compte de trésorerie : un virement "
                + "entre comptes se passe depuis la banque ou la caisse, puis se pointe.");
        }
        boolean entree = l.getEntree().signum() > 0;
        BigDecimal montantCompte = entree ? l.getEntree() : l.getSortie();
        ConversionDeviseService.Conversion c = r.getDevise() == ConversionDeviseService.DEVISE_BASE
            ? new ConversionDeviseService.Conversion(montantCompte, r.getDevise(), montantCompte, null)
            : conversion.enDeviseBase(montantCompte, r.getDevise(), conversion.tauxALaDate(l.getDateOperation()));
        String libelle = StringUtils.hasText(req.libelle()) ? req.libelle().trim()
            : "Relevé " + r.getEtablissement().getNom() + " — " + l.getLibelle();
        libelle = libelle.length() > 255 ? libelle.substring(0, 255) : libelle;
        EcritureGrandLivre ligneTresorerie = ligneEcriture(tresorerie, entree ? c.montantBase() : BigDecimal.ZERO,
            entree ? BigDecimal.ZERO : c.montantBase(), libelle, c, montantCompte);
        EcritureGrandLivre ligneContrepartie = ligneEcriture(contrepartie, entree ? BigDecimal.ZERO : c.montantBase(),
            entree ? c.montantBase() : BigDecimal.ZERO, libelle, c, montantCompte);
        JournalComptable journal = r.getEtablissement().getType() == TypeEtablissement.MOBILE_MONEY
            ? JournalComptable.MOBILE_MONEY : JournalComptable.BANQUE;
        User auteur = currentUser.requireUser();
        PieceComptable piece = comptabilite.creerPieceInterne(journal, libelle, l.getDateOperation(),
            List.of(ligneTresorerie, ligneContrepartie), auteur);
        PointageRapprochement p = pointageRepository.save(
            PointageRapprochement.builder().releve(r).automatique(false).pieceRegularisation(piece).build());
        l.setPointage(p);
        ligneTresorerie.setPointage(p);
        log.info("Regularisation de releve [releve={}, ligne={}, piece={}, contrepartie={}]",
            id, ligneId, piece.getReference(), contrepartie.getNumero());
        return detail(r);
    }

    private static EcritureGrandLivre ligneEcriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit, String libelle,
                                                    ConversionDeviseService.Conversion c, BigDecimal montantDevise) {
        EcritureGrandLivre.EcritureGrandLivreBuilder b = EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle)
            .devise(c.deviseOrigine().name());
        if (c.estConvertie()) {
            b.montantDevise(montantDevise).tauxApplique(c.tauxApplique());
        }
        return b.build();
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public ReleveDetailResponse valider(Long id) {
        ReleveBancaire r = chargerEnCours(id);
        ReleveDetailResponse d = detail(r);
        if (!d.etat().validable()) {
            throw new TransitionInvalideException("Rapprochement incomplet : " + raisonNonValidable(d.etat(), r.getDevise()));
        }
        r.setStatut(StatutReleve.VALIDE);
        r.setValidePar(currentUser.requireUser());
        r.setDateValidation(Instant.now());
        log.info("Rapprochement valide [releve={}, etablissement={}, periode={}]", id, r.getEtablissement().getNom(),
            periode(r.getMois(), r.getAnnee()));
        return detail(r);
    }

    /** Rouvre un relevé validé (administrateur, motif obligatoire, gardé sur le relevé). */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ReleveDetailResponse devalider(Long id, String motif) {
        ReleveBancaire r = charger(id);
        if (r.getStatut() != StatutReleve.VALIDE) {
            throw new TransitionInvalideException("Ce relevé n'est pas validé.");
        }
        if (!StringUtils.hasText(motif)) {
            throw new IllegalArgumentException("Indiquez le motif de la dé-validation.");
        }
        boolean suivantValide = releveRepository.validesDuCompte(r.getEtablissement().getCompte().getId()).stream()
            .anyMatch(x -> x.getAnnee() * 12 + x.getMois() > r.getAnnee() * 12 + r.getMois());
        if (suivantValide) {
            throw new TransitionInvalideException("Un relevé plus récent de ce compte est validé : dé-validez-le d'abord.");
        }
        r.setStatut(StatutReleve.EN_COURS);
        r.setMotifDevalidation(motif.trim() + " (" + currentUser.requireUser().getEmail() + ", " + LocalDate.now() + ")");
        r.setValidePar(null);
        r.setDateValidation(null);
        log.warn("Rapprochement de-valide [releve={}, motif={}]", id, motif);
        return detail(r);
    }

    // =====================================================================
    // Calculs
    // =====================================================================

    /** Montant signé d'une écriture dans la devise du compte : entrée (débit) positive, sortie (crédit) négative. */
    BigDecimal montantCompte(EcritureGrandLivre e, Devise devise) {
        BigDecimal net = nz(e.getDebit()).subtract(nz(e.getCredit()));
        if (devise == null || devise == ConversionDeviseService.DEVISE_BASE) {
            return net;
        }
        if (devise.name().equals(e.getDevise()) && e.getMontantDevise() != null) {
            return net.signum() < 0 ? e.getMontantDevise().abs().negate() : e.getMontantDevise().abs();
        }
        BigDecimal taux = e.getTauxApplique() != null ? e.getTauxApplique() : conversion.tauxALaDate(e.getDateEcriture());
        BigDecimal enDevise = conversion.depuisBase(net.abs(), devise, taux);
        return net.signum() < 0 ? enDevise.negate() : enDevise;
    }

    /** Écritures à rapprocher : celles du compte jusqu'à la fin du mois, depuis le premier relevé de l'établissement. */
    private List<EcritureGrandLivre> candidates(ReleveBancaire r) {
        LocalDate debut = debutSuivi(r);
        return ecritureRepository.pourRapprochement(r.getEtablissement().getCompte().getId(), finDuMois(r), r.getId()).stream()
            .filter(e -> !e.getDateEcriture().isBefore(debut) || e.getPointage() != null)
            .toList();
    }

    /** Premier jour du premier relevé de l'établissement : les écritures antérieures sont couvertes par son solde d'ouverture. */
    private LocalDate debutSuivi(ReleveBancaire r) {
        ReleveBancaire premier = releveRepository.precedents(r.getEtablissement().getId(), r.getAnnee(), r.getMois()).stream()
            .reduce((a, b) -> b).orElse(r);
        return LocalDate.of(premier.getAnnee(), premier.getMois(), 1);
    }

    private ReleveDetailResponse detail(ReleveBancaire r) {
        Devise devise = r.getDevise();
        LocalDate debut = debutSuivi(r);
        List<EcritureGrandLivre> ecritures = candidates(r);

        List<ReleveDetailResponse.Ligne> lignes = r.getLignes().stream()
            .map(l -> new ReleveDetailResponse.Ligne(l.getId(), l.getOrdre(), l.getDateOperation(), l.getLibelle(),
                l.getReference(), l.getEntree(), l.getSortie(),
                l.getPointage() == null ? null : l.getPointage().getId(),
                l.getPointage() != null && l.getPointage().isAutomatique(),
                l.getPointage() != null && l.getPointage().getPieceRegularisation() != null
                    ? l.getPointage().getPieceRegularisation().getReference() : null))
            .toList();
        List<ReleveDetailResponse.Ecriture> ecrituresDto = ecritures.stream()
            .map(e -> {
                BigDecimal m = montantCompte(e, devise);
                return new ReleveDetailResponse.Ecriture(e.getId(), e.getDateEcriture(), e.getLibelle(),
                    e.getPiece() == null ? null : e.getPiece().getReference(),
                    e.getPiece() == null || e.getPiece().getJournal() == null ? null : e.getPiece().getJournal().name(),
                    m.signum() > 0 ? m : BigDecimal.ZERO, m.signum() < 0 ? m.negate() : BigDecimal.ZERO,
                    e.getPointage() == null ? null : e.getPointage().getId(),
                    e.getDateEcriture().isBefore(LocalDate.of(r.getAnnee(), r.getMois(), 1)));
            })
            .toList();

        // --- État de rapprochement ------------------------------------------------------------------
        BigDecimal lignesEntrees = BigDecimal.ZERO, lignesSorties = BigDecimal.ZERO;
        int lignesNonPointees = 0;
        BigDecimal mouvementsReleve = BigDecimal.ZERO;
        for (LigneReleve l : r.getLignes()) {
            mouvementsReleve = mouvementsReleve.add(l.net());
            if (l.getPointage() == null) {
                lignesEntrees = lignesEntrees.add(l.getEntree());
                lignesSorties = lignesSorties.add(l.getSortie());
                lignesNonPointees++;
            }
        }
        BigDecimal ecrEntrees = BigDecimal.ZERO, ecrSorties = BigDecimal.ZERO;
        int ecrituresNonPointees = 0;
        for (ReleveDetailResponse.Ecriture e : ecrituresDto) {
            if (e.pointageId() == null) {
                ecrEntrees = ecrEntrees.add(e.entree());
                ecrSorties = ecrSorties.add(e.sortie());
                ecrituresNonPointees++;
            }
        }
        BigDecimal soldeComptable = ecritureRepository.duCompteJusquAu(r.getEtablissement().getCompte().getId(), finDuMois(r))
            .stream().map(e -> montantCompte(e, devise)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cote_releve = r.getSoldeCloture().subtract(lignesEntrees).add(lignesSorties);
        BigDecimal cote_compta = soldeComptable.subtract(ecrEntrees).add(ecrSorties);
        BigDecimal ecart = cote_releve.subtract(cote_compta);
        BigDecimal ecartReleve = r.getSoldeOuverture().add(mouvementsReleve).subtract(r.getSoldeCloture());
        boolean validable = r.getStatut() == StatutReleve.EN_COURS && lignesNonPointees == 0
            && ecart.signum() == 0 && ecartReleve.signum() == 0;
        ReleveDetailResponse.Etat etat = new ReleveDetailResponse.Etat(r.getSoldeCloture(), lignesEntrees, lignesSorties,
            lignesNonPointees, soldeComptable, ecrEntrees, ecrSorties, ecrituresNonPointees, ecart, ecartReleve, validable);

        List<String> avertissements = new ArrayList<>();
        if (ecartReleve.signum() != 0) {
            avertissements.add("Le relevé est incohérent : ouverture + opérations − clôture = " + ecartReleve.toPlainString()
                + " " + devise + ". Une opération manque ou un solde est mal saisi.");
        }
        releveRepository.precedents(r.getEtablissement().getId(), r.getAnnee(), r.getMois()).stream().findFirst()
            .filter(p -> p.getSoldeCloture().compareTo(r.getSoldeOuverture()) != 0)
            .ifPresent(p -> avertissements.add("Le solde d'ouverture (" + r.getSoldeOuverture().toPlainString()
                + ") diffère du solde de clôture du relevé précédent (" + p.getSoldeCloture().toPlainString() + ", "
                + periode(p.getMois(), p.getAnnee()) + ")."));
        if (debut.equals(LocalDate.of(r.getAnnee(), r.getMois(), 1))) {
            BigDecimal soldeAvant = ecritureRepository.duCompteJusquAu(r.getEtablissement().getCompte().getId(), debut.minusDays(1))
                .stream().map(e -> montantCompte(e, devise)).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (soldeAvant.compareTo(r.getSoldeOuverture()) != 0) {
                avertissements.add("Premier relevé de ce compte : le solde comptable au " + debut.minusDays(1) + " ("
                    + soldeAvant.toPlainString() + " " + devise + ") diffère du solde d'ouverture du relevé ("
                    + r.getSoldeOuverture().toPlainString() + ") — l'écart de rapprochement en tiendra compte.");
            }
        }
        return new ReleveDetailResponse(r.getId(), etablissement(r.getEtablissement()), r.getMois(), r.getAnnee(),
            devise.name(), r.getSoldeOuverture(), r.getSoldeCloture(), r.getStatut(), r.getSource(),
            nom(r.getCreePar()), nom(r.getValidePar()), r.getDateValidation(), r.getMotifDevalidation(),
            lignes, ecrituresDto, etat, avertissements);
    }

    private static String raisonNonValidable(ReleveDetailResponse.Etat e, Devise devise) {
        List<String> raisons = new ArrayList<>();
        if (e.nombreLignesNonPointees() > 0) {
            raisons.add(e.nombreLignesNonPointees() + " opération(s) du relevé restent à pointer ou à régulariser");
        }
        if (e.ecartReleve().signum() != 0) {
            raisons.add("le relevé lui-même est incohérent (" + e.ecartReleve().toPlainString() + " " + devise + ")");
        }
        if (e.ecart().signum() != 0) {
            raisons.add("écart de rapprochement de " + e.ecart().toPlainString() + " " + devise);
        }
        return String.join(", ", raisons) + ".";
    }

    // =====================================================================

    private void ajouterLignes(ReleveBancaire r, List<LigneReleveRequest> lignes) {
        LocalDate debut = LocalDate.of(r.getAnnee(), r.getMois(), 1);
        LocalDate fin = YearMonth.of(r.getAnnee(), r.getMois()).atEndOfMonth();
        int ordre = r.getLignes().stream().mapToInt(LigneReleve::getOrdre).max().orElse(0);
        for (LigneReleveRequest l : lignes) {
            boolean entree = nz(l.entree()).signum() > 0;
            boolean sortie = nz(l.sortie()).signum() > 0;
            if (entree == sortie) {
                throw new IllegalArgumentException("Ligne du " + l.dateOperation() + " « " + l.libelle()
                    + " » : indiquez soit une entrée, soit une sortie.");
            }
            if (l.dateOperation().isBefore(debut.minusDays(7)) || l.dateOperation().isAfter(fin.plusDays(7))) {
                throw new IllegalArgumentException("Ligne du " + l.dateOperation() + " « " + l.libelle()
                    + " » : hors de la période du relevé (" + periode(r.getMois(), r.getAnnee()) + ").");
            }
            r.getLignes().add(LigneReleve.builder()
                .releve(r)
                .ordre(++ordre)
                .dateOperation(l.dateOperation())
                .libelle(l.libelle().trim())
                .reference(StringUtils.hasText(l.reference()) ? l.reference().trim() : null)
                .entree(nz(l.entree()))
                .sortie(nz(l.sortie()))
                .build());
        }
    }

    private ReleveBancaire charger(Long id) {
        return releveRepository.findById(id).orElseThrow(() -> RessourceIntrouvableException.of("ReleveBancaire", id));
    }

    private ReleveBancaire chargerEnCours(Long id) {
        ReleveBancaire r = charger(id);
        if (r.getStatut() != StatutReleve.EN_COURS) {
            throw new TransitionInvalideException("Ce rapprochement est validé : il ne peut plus être modifié "
                + "(un administrateur peut le dé-valider).");
        }
        return r;
    }

    private static LigneReleve ligne(ReleveBancaire r, Long ligneId) {
        return r.getLignes().stream().filter(l -> l.getId().equals(ligneId)).findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("LigneReleve", ligneId));
    }

    private static ReleveDetailResponse.Etablissement etablissement(EtablissementTresorerie e) {
        return new ReleveDetailResponse.Etablissement(e.getId(), e.getNom(), e.getType().name(),
            e.getCompte().getNumero(), e.getCompte().getLibelle(), (e.getDevise() == null ? Devise.USD : e.getDevise()).name());
    }

    private static LocalDate finDuMois(ReleveBancaire r) {
        return YearMonth.of(r.getAnnee(), r.getMois()).atEndOfMonth();
    }

    private static boolean contient(String texte, String fragment) {
        return texte != null && texte.toLowerCase(Locale.ROOT).contains(fragment);
    }

    private static String nom(User u) {
        if (u == null) return null;
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static String periode(int mois, int annee) {
        return String.format("%02d/%d", mois, annee);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
