package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.comptabilite.ClotureExerciceResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.repository.VenteRepository;
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
import java.util.List;

/**
 * Clôture annuelle formelle de l'exercice (audit OHADA du 18/08/2026,
 * constats A-01 et A-02).
 *
 * <p><b>Ce que {@link PeriodeComptableService} fait déjà.</b> Verrouiller une
 * date contre toute saisie rétroactive — c'est nécessaire mais ce n'est pas
 * une clôture au sens de l'Acte uniforme : les comptes de charges et de
 * produits (classes 6 et 7) n'y sont jamais soldés, le résultat net affiché
 * au bilan est recalculé à la volée à chaque consultation, jamais posé dans
 * le Grand Livre.</p>
 *
 * <p><b>Ce que ce service ajoute, dans l'ordre :</b></p>
 * <ol>
 *   <li>reverse toute réévaluation devise encore ouverte, issue de la
 *       clôture précédente — une réévaluation est par nature latente, elle
 *       ne doit jamais s'accumuler d'un exercice à l'autre ;</li>
 *   <li>réévalue au taux du jour de clôture les créances clients encore
 *       ouvertes et libellées en devise étrangère, et porte l'écart latent
 *       en 478/479 (le seul cas, dans cette application, où un solde de
 *       tiers reste exposé au change après son enregistrement — voir
 *       {@link VenteRepository#creancesOuvertes()}) ;</li>
 *   <li>solde l'intégralité des comptes de gestion — classes 6, 7 et 8
 *       (H.A.O.), voir {@link #CLASSES_DE_GESTION} — vers le compte 131
 *       (bénéfice) ou 139 (perte) ;</li>
 *   <li>verrouille la date via {@link PeriodeComptableService}.</li>
 * </ol>
 *
 * <p>Les quatre étapes sont une seule transaction : une clôture partielle
 * serait pire qu'aucune clôture.</p>
 */
@Service
@RequiredArgsConstructor
public class ClotureAnnuelleService {

    private static final Logger log = LoggerFactory.getLogger(ClotureAnnuelleService.class);

    private static final String COMPTE_RESULTAT_BENEFICE = "131";
    private static final String COMPTE_RESULTAT_PERTE = "139";
    /**
     * Classes soldees a la cloture. La classe 8 (Hors Activites Ordinaires)
     * en fait partie au meme titre que les classes 6 et 7 : le SYSCOHADA
     * revise l'integre au resultat net de l'exercice.
     *
     * <p>Son omission etait un defaut reel : {@code PatrimoineService.sortir}
     * impute toute cession/mise au rebut d'immobilisation en 812 (VNC) et 822
     * (produit de cession), et {@code EtatsFinanciersExcelService} compte deja
     * la classe 8 dans le resultat. Sans elle ici, le resultat porte en 131/139
     * differait de celui des etats financiers, et les soldes H.A.O. se
     * reportaient indefiniment d'un exercice sur l'autre.</p>
     */
    private static final java.util.Set<Integer> CLASSES_DE_GESTION = java.util.Set.of(6, 7, 8);
    /** "Diminution des créances" (perte latente) / "Augmentation des créances" (gain latent). */
    private static final String COMPTE_ECART_CONVERSION_ACTIF = "4781";
    private static final String COMPTE_ECART_CONVERSION_PASSIF = "4791";

    private final PeriodeComptableService periodeService;
    private final ComptabiliteService comptabilite;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final PieceComptableRepository pieceRepository;
    private final VenteRepository venteRepository;
    private final CompteOHADARepository compteRepository;
    private final ConversionDeviseService conversionDevise;
    private final CurrentUserProvider currentUser;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ClotureExerciceResponse cloturerExercice(LocalDate dateCloture) {
        LocalDate precedente = periodeService.dateCloture();
        if (precedente != null && !dateCloture.isAfter(precedente)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La date de clôture (" + dateCloture + ") doit être postérieure à la précédente ("
                + precedente + ").");
        }
        LocalDate limite = LocalDate.now().plusDays(1);
        if (dateCloture.isAfter(limite)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La date de clôture ne peut pas être postérieure à aujourd'hui.");
        }

        User auteur = currentUser.requireUser();

        int reversees = reverserReevaluationsOuvertes(auteur);
        ReevaluationResultat reevaluation = reevaluerCreancesDevise(dateCloture, auteur);
        BigDecimal resultatNet = solderComptesDeGestion(dateCloture, auteur);
        periodeService.definirDateCloture(dateCloture);

        log.info("Exercice clôturé [date={}, resultatNet={} {}, creances reevaluees={}, reevaluations reversees={}]",
            dateCloture, resultatNet, ConversionDeviseService.DEVISE_BASE, reevaluation.nbCreances(), reversees);

        return new ClotureExerciceResponse(
            dateCloture, resultatNet,
            null, // renseigne par l'appelant si besoin (piece de cloture non retournee individuellement ici)
            reevaluation.pieceReference(),
            reversees,
            reevaluation.nbCreances());
    }

    // ---------------------------------------------------------------------
    // 1. Reversement des reevaluations devise encore ouvertes
    // ---------------------------------------------------------------------

    /**
     * Ne reverse que les reevaluations dont la creance est encore ouverte.
     *
     * <p>Une reevaluation reglee entre-temps (la vente a ete encaissee avant
     * cette cloture) ne doit plus jamais etre touchee : son ecart latent a
     * deja ete consomme et solde exactement par {@link VenteService#reglerCreance},
     * et le compte client de cette vente est deja a zero. La reverser quand
     * meme rouvrirait un solde fantome sur une vente pourtant deja payee —
     * c'est pour permettre cette distinction, impossible a faire au niveau
     * d'une piece partagee par plusieurs creances, que la reevaluation est
     * portee individuellement par chaque vente (voir {@link Vente#getPieceReevaluation()}).</p>
     */
    private int reverserReevaluationsOuvertes(User auteur) {
        List<Vente> aReverser = venteRepository.creancesOuvertes().stream()
            .filter(v -> v.getPieceReevaluation() != null)
            .toList();
        for (Vente v : aReverser) {
            comptabilite.annulerInterne(v.getPieceReevaluation().getId(), auteur);
            v.setPieceReevaluation(null);
            v.setEcartLatentCumule(BigDecimal.ZERO);
            venteRepository.save(v);
        }
        return aReverser.size();
    }

    // ---------------------------------------------------------------------
    // 2. Reevaluation des creances clients en devise etrangere
    // ---------------------------------------------------------------------

    private record ReevaluationResultat(String pieceReference, int nbCreances) {}

    private ReevaluationResultat reevaluerCreancesDevise(LocalDate dateCloture, User auteur) {
        List<Vente> creances = venteRepository.creancesOuvertes().stream()
            .filter(v -> v.getDevise() != ConversionDeviseService.DEVISE_BASE)
            .toList();
        if (creances.isEmpty()) {
            return new ReevaluationResultat(null, 0);
        }

        BigDecimal tauxCloture = conversionDevise.tauxALaDate(dateCloture);
        if (tauxCloture == null || tauxCloture.signum() <= 0) {
            log.warn("Reevaluation devise ignoree : aucun taux de change applicable au {}", dateCloture);
            return new ReevaluationResultat(null, 0);
        }

        String libelle = "Réévaluation devise à la clôture du " + dateCloture;
        String derniereReference = null;
        int nbReevaluees = 0;

        // Une piece par vente (et non une piece partagee par toutes les
        // creances revaluees) : c'est ce qui permet a la prochaine cloture
        // de ne reverser que celles dont la creance est encore ouverte (voir
        // reverserReevaluationsOuvertes) sans avoir a demeler, a l'interieur
        // d'une piece commune, les lignes d'une vente entre-temps reglee de
        // celles d'une vente encore en cours.
        for (Vente v : creances) {
            BigDecimal montantBooked = ecritureRepository.soldePourPieceEtCompte(
                v.getPiece().getId(), VenteService.COMPTE_CLIENTS);
            if (montantBooked == null || montantBooked.signum() == 0) {
                continue;
            }
            BigDecimal montantCloture = conversionDevise
                .enDeviseBase(v.getTotalTtc(), v.getDevise(), tauxCloture).montantBase();
            BigDecimal ecart = montantCloture.subtract(montantBooked);
            if (ecart.signum() == 0) {
                continue;
            }
            String libelleLigne = libelle + " (" + v.getReference() + ")";
            CompteOHADA client = compteParNumero(VenteService.COMPTE_CLIENTS);
            List<EcritureGrandLivre> lignes = new ArrayList<>();
            if (ecart.signum() > 0) {
                // La creance vaut desormais plus cher en devise de base : gain latent.
                lignes.add(ligne(client, ecart, BigDecimal.ZERO, libelleLigne, dateCloture));
                lignes.add(ligne(compteParNumero(COMPTE_ECART_CONVERSION_PASSIF), BigDecimal.ZERO, ecart, libelleLigne, dateCloture));
            } else {
                BigDecimal perte = ecart.abs();
                lignes.add(ligne(client, BigDecimal.ZERO, perte, libelleLigne, dateCloture));
                lignes.add(ligne(compteParNumero(COMPTE_ECART_CONVERSION_ACTIF), perte, BigDecimal.ZERO, libelleLigne, dateCloture));
            }

            PieceComptable piece = comptabilite.creerPieceInterne(
                JournalComptable.OPERATIONS_DIVERSES, libelleLigne, dateCloture, lignes, auteur);
            piece.setReevaluationDevise(true);
            pieceRepository.save(piece);
            derniereReference = piece.getReference();

            // Sans ce cumul, le reglement ulterieur de cette creance (piece
            // distincte de celle-ci) ne credifierait le compte client que du
            // montant d'origine, laissant l'ecart latent bloque sur 4111.
            v.setEcartLatentCumule(v.getEcartLatentCumule().add(ecart));
            v.setPieceReevaluation(piece);
            venteRepository.save(v);
            nbReevaluees++;
        }

        return new ReevaluationResultat(derniereReference, nbReevaluees);
    }

    // ---------------------------------------------------------------------
    // 3. Soldage des comptes de gestion (classes 6 et 7)
    // ---------------------------------------------------------------------

    private BigDecimal solderComptesDeGestion(LocalDate dateCloture, User auteur) {
        List<Object[]> cumul = ecritureRepository.cumulParCompte(dateCloture);
        List<EcritureGrandLivre> lignes = new ArrayList<>();
        BigDecimal totalCharges = BigDecimal.ZERO;
        BigDecimal totalProduits = BigDecimal.ZERO;
        String libelle = "Clôture de l'exercice au " + dateCloture;

        for (Object[] row : cumul) {
            String numero = (String) row[0];
            TypeCompte type = (TypeCompte) row[2];
            Integer classe = (Integer) row[3];
            BigDecimal debit = nz((BigDecimal) row[4]);
            BigDecimal credit = nz((BigDecimal) row[5]);
            if (classe == null || !CLASSES_DE_GESTION.contains(classe) || type == null) {
                continue;
            }
            // Le sens se lit sur le TYPE du compte, pas sur sa classe : la
            // classe 8 (H.A.O.) porte a la fois des charges (81x, 83x) et des
            // produits (82x, 84x), contrairement aux classes 6 et 7 qui sont
            // homogenes. Un solde nul (compte mouvemente puis contre-passe)
            // n'a rien a solder.
            if (type == TypeCompte.CHARGE) {
                BigDecimal solde = debit.subtract(credit);
                if (solde.signum() != 0) {
                    // Contre-passation : un solde debiteur se solde au credit,
                    // et l'inverse pour un solde crediteur (compte de charge
                    // exceptionnellement crediteur apres extourne).
                    lignes.add(ligneSolde(compteParNumero(numero), solde.negate(), libelle, dateCloture));
                    totalCharges = totalCharges.add(solde);
                }
            } else if (type == TypeCompte.PRODUIT) {
                BigDecimal solde = credit.subtract(debit);
                if (solde.signum() != 0) {
                    lignes.add(ligneSolde(compteParNumero(numero), solde, libelle, dateCloture));
                    totalProduits = totalProduits.add(solde);
                }
            }
        }

        BigDecimal resultatNet = totalProduits.subtract(totalCharges);
        if (lignes.isEmpty()) {
            log.info("Cloture de l'exercice au {} : aucun mouvement de gestion, aucune piece a passer", dateCloture);
            return resultatNet;
        }
        if (resultatNet.signum() > 0) {
            lignes.add(ligne(compteParNumero(COMPTE_RESULTAT_BENEFICE), BigDecimal.ZERO, resultatNet, libelle, dateCloture));
        } else if (resultatNet.signum() < 0) {
            lignes.add(ligne(compteParNumero(COMPTE_RESULTAT_PERTE), resultatNet.abs(), BigDecimal.ZERO, libelle, dateCloture));
        }

        comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES, libelle, dateCloture, lignes, auteur);
        return resultatNet;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private CompteOHADA compteParNumero(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Compte OHADA " + numero + " absent du plan comptable : clôture impossible."));
    }

    private EcritureGrandLivre ligne(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                     String libelle, LocalDate date) {
        return EcritureGrandLivre.builder()
            .compte(compte).debit(debit).credit(credit).libelle(libelle).dateEcriture(date).build();
    }

    /**
     * Ligne dont le sens decoule du signe : montant positif au debit, negatif
     * au credit. Evite d'avoir a distinguer a chaque appel le cas nominal
     * (charge debitrice, produit crediteur) du cas inverse — un compte de
     * gestion peut se retrouver dans le sens oppose apres une extourne, et il
     * doit alors etre soldé dans l'autre sens.
     */
    private EcritureGrandLivre ligneSolde(CompteOHADA compte, BigDecimal montantSigne,
                                          String libelle, LocalDate date) {
        return montantSigne.signum() >= 0
            ? ligne(compte, montantSigne, BigDecimal.ZERO, libelle, date)
            : ligne(compte, BigDecimal.ZERO, montantSigne.abs(), libelle, date);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
