package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.dto.comptabilite.DeclarationTvaResponse;
import com.mbsc.finapp.dto.comptabilite.LigneTvaResponse;
import com.mbsc.finapp.dto.comptabilite.TvaSituationResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gestion transparente de la TVA : rapproche la TVA collectée sur les
 * ventes (comptes 443x, créditée à la facturation) et la TVA récupérable
 * sur les achats (comptes 445x, débitée — notamment depuis les notes de
 * frais marquées « soumises à la TVA », voir {@link RegleTresorerieService}).
 *
 * <p>« Transparente » se traduit ici par une piste d'audit systématique :
 * chaque montant agrégé est adossé à la liste des écritures qui le
 * composent, pas seulement à un total.</p>
 */
@Service
@RequiredArgsConstructor
public class TvaService {

    private static final Logger log = LoggerFactory.getLogger(TvaService.class);

    private static final String PREFIXE_COLLECTEE = "443";
    private static final String PREFIXE_RECUPERABLE = "445";
    /** Solde crediteur : TVA nette a reverser a l'Etat. */
    private static final String COMPTE_TVA_DUE = "4441";
    /** Solde debiteur : credit de TVA imputable sur les periodes suivantes. */
    private static final String COMPTE_CREDIT_TVA = "4449";

    private final EcritureGrandLivreRepository ecritureRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabilite;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public TvaSituationResponse situation(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<EcritureGrandLivre> collectees = ecritureRepository.grandLivreParCompte(PREFIXE_COLLECTEE, debut, fin);
        List<EcritureGrandLivre> recuperables = ecritureRepository.grandLivreParCompte(PREFIXE_RECUPERABLE, debut, fin);

        BigDecimal totalCollectee = collectees.stream()
            .map(e -> nz(e.getCredit()).subtract(nz(e.getDebit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRecuperable = recuperables.stream()
            .map(e -> nz(e.getDebit()).subtract(nz(e.getCredit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LigneTvaResponse> lignes = new ArrayList<>();
        for (EcritureGrandLivre e : collectees) {
            if (nz(e.getCredit()).subtract(nz(e.getDebit())).signum() != 0) {
                lignes.add(LigneTvaResponse.collectee(e));
            }
        }
        for (EcritureGrandLivre e : recuperables) {
            if (nz(e.getDebit()).subtract(nz(e.getCredit())).signum() != 0) {
                lignes.add(LigneTvaResponse.recuperable(e));
            }
        }
        lignes.sort(Comparator.comparing(LigneTvaResponse::date));

        return new TvaSituationResponse(debut, fin, totalCollectee, totalRecuperable,
            totalCollectee.subtract(totalRecuperable), lignes);
    }

    // ---------------------------------------------------------------------
    // Arrete de TVA (declaration periodique)
    // ---------------------------------------------------------------------

    /**
     * Arrete la TVA d'une periode : solde la TVA collectee (443x, crediteur)
     * contre la TVA recuperable (445x, debiteur) et porte le net en
     * {@value #COMPTE_TVA_DUE} (TVA a reverser) ou {@value #COMPTE_CREDIT_TVA}
     * (credit reportable).
     *
     * <p>Sans cet arrete, les comptes 443x et 445x ne sont jamais apures :
     * ils cumulent indefiniment et gonflent le bilan des deux cotes, alors
     * que la dette reelle envers l'Etat est le seul solde net. C'est le
     * pendant mensuel de la cloture annuelle, qui solde les comptes de
     * gestion.</p>
     *
     * <p>La piece est generee au statut BROUILLON : une declaration se relit
     * avant d'etre posee, comme la cloture de paie. Le DFIN la comptabilise
     * depuis l'ecran Pieces comptables.</p>
     *
     * <p><b>Idempotence.</b> L'arrete se fonde sur le solde reel des comptes a
     * la date d'arrete, brouillons exclus. Relancer l'operation apres avoir
     * comptabilise le premier arrete trouve donc un solde nul et ne repasse
     * rien. En revanche, deux arretes BROUILLON simultanes sur la meme
     * periode sont possibles : le garde-fou anti-doublon est la revue
     * humaine, comme pour toute piece brouillon.</p>
     */
    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional
    public DeclarationTvaResponse declarer(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();
        if (fin.isBefore(debut)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La fin de periode (" + fin + ") ne peut pas preceder son debut (" + debut + ").");
        }
        User auteur = currentUser.requireUser();

        // Soldes par compte, et non totaux agreges : l'ecriture doit solder
        // chaque sous-compte (4431, 4432, 4452, 4453...) individuellement,
        // sinon les sous-comptes resteraient mouvementes apres l'arrete.
        List<EcritureGrandLivre> collectees = ecritureRepository.grandLivreParCompte(PREFIXE_COLLECTEE, debut, fin);
        List<EcritureGrandLivre> recuperables = ecritureRepository.grandLivreParCompte(PREFIXE_RECUPERABLE, debut, fin);

        java.util.Map<String, BigDecimal> soldeParCompte = new java.util.LinkedHashMap<>();
        java.util.Map<String, CompteOHADA> compteParNumero = new java.util.LinkedHashMap<>();
        for (EcritureGrandLivre e : collectees) {
            cumuler(soldeParCompte, compteParNumero, e, nz(e.getCredit()).subtract(nz(e.getDebit())));
        }
        for (EcritureGrandLivre e : recuperables) {
            cumuler(soldeParCompte, compteParNumero, e, nz(e.getDebit()).subtract(nz(e.getCredit())).negate());
        }

        BigDecimal totalCollectee = collectees.stream()
            .map(e -> nz(e.getCredit()).subtract(nz(e.getDebit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRecuperable = recuperables.stream()
            .map(e -> nz(e.getDebit()).subtract(nz(e.getCredit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal soldeNet = totalCollectee.subtract(totalRecuperable);

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        String libelle = "Arrêté de TVA du " + debut + " au " + fin;
        for (var entree : soldeParCompte.entrySet()) {
            BigDecimal solde = entree.getValue();
            if (solde.signum() == 0) {
                continue;
            }
            // Un solde positif est crediteur (TVA collectee) : on le solde au
            // debit, et inversement. Le signe porte le sens, ce qui traite
            // sans cas particulier un compte de TVA exceptionnellement
            // inverse apres avoir ou extourne.
            CompteOHADA compte = compteParNumero.get(entree.getKey());
            lignes.add(solde.signum() > 0
                ? ligne(compte, solde, BigDecimal.ZERO, libelle, fin)
                : ligne(compte, BigDecimal.ZERO, solde.abs(), libelle, fin));
        }

        if (lignes.isEmpty()) {
            return new DeclarationTvaResponse(debut, fin, totalCollectee, totalRecuperable, soldeNet,
                null, null, "Aucun solde de TVA à arrêter sur cette période.");
        }

        String compteDeSolde = soldeNet.signum() >= 0 ? COMPTE_TVA_DUE : COMPTE_CREDIT_TVA;
        if (soldeNet.signum() > 0) {
            lignes.add(ligne(exigerCompte(COMPTE_TVA_DUE), BigDecimal.ZERO, soldeNet, libelle, fin));
        } else if (soldeNet.signum() < 0) {
            lignes.add(ligne(exigerCompte(COMPTE_CREDIT_TVA), soldeNet.abs(), BigDecimal.ZERO, libelle, fin));
        }

        PieceComptable piece = comptabilite.creerPieceInterneBrouillon(
            JournalComptable.OPERATIONS_DIVERSES, libelle, fin, lignes, auteur);

        log.info("Arrêté de TVA [periode={} au {}, collectee={}, recuperable={}, net={}, piece={}]",
            debut, fin, totalCollectee, totalRecuperable, soldeNet, piece.getReference());

        return new DeclarationTvaResponse(debut, fin, totalCollectee, totalRecuperable, soldeNet,
            compteDeSolde, piece.getReference(),
            soldeNet.signum() >= 0
                ? "TVA due à l'État portée en " + COMPTE_TVA_DUE + ". Pièce à comptabiliser."
                : "Crédit de TVA reporté en " + COMPTE_CREDIT_TVA + ". Pièce à comptabiliser.");
    }

    private void cumuler(java.util.Map<String, BigDecimal> soldes,
                         java.util.Map<String, CompteOHADA> comptes,
                         EcritureGrandLivre e, BigDecimal montantSigne) {
        String numero = e.getCompte().getNumero();
        comptes.putIfAbsent(numero, e.getCompte());
        soldes.merge(numero, montantSigne, BigDecimal::add);
    }

    private CompteOHADA exigerCompte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Compte OHADA " + numero + " absent du plan comptable : arrêté de TVA impossible."));
    }

    private EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                     String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
