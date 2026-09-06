package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.TransactionBancaire;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.TransactionMobileMoney;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.TransactionBancaireRepository;
import com.mbsc.finapp.repository.TransactionCaisseRepository;
import com.mbsc.finapp.repository.TransactionMobileMoneyRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Service comptable central : enregistre une transaction de caisse en partie
 * double dans le Grand Livre. Mutualise par la saisie directe (caisse web) et
 * par la synchronisation hors-ligne (poste caissier JavaFX).
 *
 * <p>Convention OHADA retenue :</p>
 * <ul>
 *   <li>Compte de tresorerie caisse : {@value #COMPTE_CAISSE} (571).</li>
 *   <li>DECAISSEMENT : debit du compte de charge, credit de la caisse.</li>
 *   <li>ENCAISSEMENT : debit de la caisse, credit du compte de produit.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EcritureComptableService {

    private static final Logger log = LoggerFactory.getLogger(EcritureComptableService.class);

    /** Compte OHADA "Caisse". */
    public static final String COMPTE_CAISSE = "571";

    /**
     * Prefixe des comptes bancaires : chaque banque possede son propre
     * sous-compte (5215, 5216...), ce prefixe agrege donc toutes les banques.
     */
    public static final String PREFIXE_COMPTES_BANQUE = "521";

    /**
     * Prefixe des comptes mobile money : chaque operateur possede son propre
     * sous-compte (5521, 5522...), ce prefixe agrege donc tous les operateurs.
     *
     * <p>Le referentiel SYSCOHADA reserve 552 « Monnaie electronique -
     * telephone portable » a ce type de compte. L'application utilisait
     * auparavant 582, qui designe en realite les accreditifs.</p>
     */
    public static final String PREFIXE_COMPTES_MOBILE_MONEY = "552";

    private final TransactionCaisseRepository transactionRepository;
    private final TransactionBancaireRepository transactionBancaireRepository;
    private final TransactionMobileMoneyRepository transactionMobileMoneyRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabiliteService;
    private final PeriodeComptableService periodeService;
    private final ConversionDeviseService conversionDevise;

    /**
     * Cree et persiste une transaction de caisse avec ses deux ecritures.
     * La transaction recue est supposee non encore presente (l'idempotence par
     * UUID est verifiee en amont par l'appelant).
     *
     * @param transaction        entete (uuid, reference, montant, sens, caissier, dates) deja renseignee,
     *                           montant exprime dans la devise de base (CDF)
     * @param compteContrepartie compte de charge (decaissement) ou de produit (encaissement)
     * @param libelle            libelle des ecritures
     * @return la transaction persistee avec ses ecritures
     */
    @Transactional
    public TransactionCaisse enregistrer(TransactionCaisse transaction,
                                         CompteOHADA compteContrepartie,
                                         String libelle) {
        return enregistrer(transaction, compteContrepartie, libelle, null);
    }

    /** Une part de la contrepartie d'un mouvement de caisse — voir {@link #enregistrerVentile}. */
    public record Ventilation(CompteOHADA compte, BigDecimal montant) {}

    /**
     * Comme {@link #enregistrer}, mais la contrepartie est ventilee sur
     * plusieurs comptes au lieu d'un seul. La caisse porte le total, chaque
     * part son propre compte.
     *
     * <p>Necessaire des qu'un mouvement de tresorerie n'a pas une contrepartie
     * unique : un achat soumis a TVA se decompose en <b>D 601x hors taxes +
     * D 4452 TVA recuperable / C 571 toutes taxes comprises</b>. La TVA doit
     * rester sur son compte propre — c'est une creance sur l'Etat, pas une
     * charge — sans quoi elle gonflerait le cout d'achat et le stock.</p>
     *
     * @param contreparties parts non nulles ; leur somme doit egaler le montant
     *                      de la transaction, verifie ici plutot que laisse a
     *                      l'appelant.
     */
    @Transactional
    public TransactionCaisse enregistrerVentile(TransactionCaisse transaction,
                                                List<Ventilation> contreparties,
                                                String libelle) {
        validerMontant(transaction.getMontant());
        if (contreparties == null || contreparties.isEmpty()) {
            throw new IllegalArgumentException("Aucune contrepartie fournie pour cette operation de caisse.");
        }
        BigDecimal somme = contreparties.stream()
            .map(Ventilation::montant)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (somme.compareTo(transaction.getMontant()) != 0) {
            throw new IllegalStateException(
                "Ventilation incoherente : total des contreparties " + somme
                + " pour un mouvement de " + transaction.getMontant() + ".");
        }

        CompteOHADA caisse = compteRepository.findByNumero(COMPTE_CAISSE)
            .orElseThrow(() -> new IllegalStateException(
                "Compte caisse " + COMPTE_CAISSE + " absent du plan comptable"));

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        BigDecimal total = transaction.getMontant();
        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();
        boolean decaissement = transaction.getSens() == SensTransaction.DECAISSEMENT;

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        if (decaissement) {
            for (Ventilation v : contreparties) {
                lignes.add(ecriture(v.compte(), v.montant(), BigDecimal.ZERO, texte, dateEcriture, null));
            }
            lignes.add(ecriture(caisse, BigDecimal.ZERO, total, texte, dateEcriture, null));
        } else {
            lignes.add(ecriture(caisse, total, BigDecimal.ZERO, texte, dateEcriture, null));
            for (Ventilation v : contreparties) {
                lignes.add(ecriture(v.compte(), BigDecimal.ZERO, v.montant(), texte, dateEcriture, null));
            }
        }

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionCaisse saved = transactionRepository.save(transaction);
        comptabiliteService.creerPieceCaisse(
            texte, dateEcriture, saved.getEcritures(), transaction.getCaissier());
        log.info("Ecriture de caisse ventilee [ref={}, sens={}, montant={}, parts={}]",
            saved.getReference(), saved.getSens(), total, contreparties.size());
        return saved;
    }

    /**
     * Variante avec information de conversion de devise : la devise d'origine,
     * le montant d'origine et le taux appliqué sont conservés sur chaque
     * écriture (piste d'audit multi-devises).
     */
    @Transactional
    public TransactionCaisse enregistrer(TransactionCaisse transaction,
                                         CompteOHADA compteContrepartie,
                                         String libelle,
                                         ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());

        CompteOHADA caisse = compteRepository.findByNumero(COMPTE_CAISSE)
            .orElseThrow(() -> new IllegalStateException(
                "Compte caisse " + COMPTE_CAISSE + " absent du plan comptable"));

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        BigDecimal montant = transaction.getMontant();
        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        if (transaction.getSens() == SensTransaction.DECAISSEMENT) {
            lignes.add(ecriture(compteContrepartie, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(caisse, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        } else {
            lignes.add(ecriture(caisse, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(compteContrepartie, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        }

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionCaisse saved = transactionRepository.save(transaction);
        comptabiliteService.creerPieceCaisse(
            texte, dateEcriture, saved.getEcritures(), transaction.getCaissier());
        log.info("Ecriture comptable enregistree [ref={}, sens={}, montant={}, contrepartie={}]",
            saved.getReference(), saved.getSens(), montant, compteContrepartie.getNumero());
        return saved;
    }

    /**
     * Une ligne de debit destinee a {@link #enregistrerDecaissementMultiLigne} :
     * un compte de charge/actif et le montant (en devise de base) qui lui est impute.
     */
    public record LigneDebit(CompteOHADA compte, BigDecimal montant) {}

    /**
     * Variante multi-lignes d'un decaissement : chaque ligne de depense d'une
     * note de frais (compte + montant propres) genere sa propre ecriture de
     * debit ; une unique ecriture de credit solde la caisse pour le montant
     * total. La somme des lignes de debit doit exactement egaler le montant
     * de la transaction (garanti par l'appelant, qui absorbe les ecarts
     * d'arrondi de conversion sur la derniere ligne).
     */
    @Transactional
    public TransactionCaisse enregistrerDecaissementMultiLigne(TransactionCaisse transaction,
                                                                List<LigneDebit> lignesDebit,
                                                                String libelle,
                                                                ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());
        if (lignesDebit == null || lignesDebit.isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne de debit est requise");
        }
        BigDecimal sommeLignes = lignesDebit.stream()
            .map(LigneDebit::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeLignes.compareTo(transaction.getMontant()) != 0) {
            throw new IllegalStateException("Somme des lignes de debit (" + sommeLignes
                + ") differente du montant de la transaction (" + transaction.getMontant() + ")");
        }

        CompteOHADA caisse = compteRepository.findByNumero(COMPTE_CAISSE)
            .orElseThrow(() -> new IllegalStateException(
                "Compte caisse " + COMPTE_CAISSE + " absent du plan comptable"));

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        for (LigneDebit ld : lignesDebit) {
            lignes.add(ecriture(ld.compte(), ld.montant(), BigDecimal.ZERO, texte, dateEcriture, conversion));
        }
        lignes.add(ecriture(caisse, BigDecimal.ZERO, transaction.getMontant(), texte, dateEcriture, conversion));

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionCaisse saved = transactionRepository.save(transaction);
        comptabiliteService.creerPieceCaisse(
            texte, dateEcriture, saved.getEcritures(), transaction.getCaissier());
        log.info("Ecriture comptable multi-ligne enregistree [ref={}, sens={}, montant={}, lignes={}]",
            saved.getReference(), saved.getSens(), transaction.getMontant(), lignesDebit.size());
        return saved;
    }

    /**
     * Une ligne de credit destinee a {@link #enregistrerEncaissementMultiLigne} :
     * un compte de produit/passif et le montant (en devise de base) qui lui est impute.
     */
    public record LigneCredit(CompteOHADA compte, BigDecimal montant) {}

    /**
     * Variante multi-lignes d'un encaissement : symetrique de
     * {@link #enregistrerDecaissementMultiLigne}. Chaque ligne de recette
     * d'une note de frais d'encaissement (compte + montant propres) genere
     * sa propre ecriture de credit ; une unique ecriture de debit alimente
     * la caisse pour le montant total. La somme des lignes de credit doit
     * exactement egaler le montant de la transaction (garanti par
     * l'appelant, qui absorbe les ecarts d'arrondi de conversion sur la
     * derniere ligne).
     */
    @Transactional
    public TransactionCaisse enregistrerEncaissementMultiLigne(TransactionCaisse transaction,
                                                                 List<LigneCredit> lignesCredit,
                                                                 String libelle,
                                                                 ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());
        if (lignesCredit == null || lignesCredit.isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne de credit est requise");
        }
        BigDecimal sommeLignes = lignesCredit.stream()
            .map(LigneCredit::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeLignes.compareTo(transaction.getMontant()) != 0) {
            throw new IllegalStateException("Somme des lignes de credit (" + sommeLignes
                + ") differente du montant de la transaction (" + transaction.getMontant() + ")");
        }

        CompteOHADA caisse = compteRepository.findByNumero(COMPTE_CAISSE)
            .orElseThrow(() -> new IllegalStateException(
                "Compte caisse " + COMPTE_CAISSE + " absent du plan comptable"));

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        lignes.add(ecriture(caisse, transaction.getMontant(), BigDecimal.ZERO, texte, dateEcriture, conversion));
        for (LigneCredit lc : lignesCredit) {
            lignes.add(ecriture(lc.compte(), BigDecimal.ZERO, lc.montant(), texte, dateEcriture, conversion));
        }

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionCaisse saved = transactionRepository.save(transaction);
        comptabiliteService.creerPieceCaisse(
            texte, dateEcriture, saved.getEcritures(), transaction.getCaissier());
        log.info("Ecriture comptable multi-ligne enregistree [ref={}, sens={}, montant={}, lignes={}]",
            saved.getReference(), saved.getSens(), transaction.getMontant(), lignesCredit.size());
        return saved;
    }

    // -------------------------------------------------------------------
    // Banque (memes conventions que la caisse, compte 5211, journal BANQUE)
    // -------------------------------------------------------------------

    /**
     * Saisie directe (encaissement/decaissement) sur un compte bancaire.
     *
     * @param banque compte de tresorerie de la banque choisie : chaque
     *               etablissement porte son propre solde.
     */
    @Transactional
    public TransactionBancaire enregistrerBancaire(TransactionBancaire transaction,
                                                    CompteOHADA banque,
                                                    CompteOHADA compteContrepartie,
                                                    String libelle,
                                                    ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        BigDecimal montant = transaction.getMontant();
        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        if (transaction.getSens() == SensTransaction.DECAISSEMENT) {
            lignes.add(ecriture(compteContrepartie, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(banque, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        } else {
            lignes.add(ecriture(banque, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(compteContrepartie, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        }

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionBancaire saved = transactionBancaireRepository.save(transaction);
        comptabiliteService.creerPieceInterne(
            JournalComptable.BANQUE, texte, dateEcriture, saved.getEcritures(), transaction.getOperateur());
        log.info("Ecriture bancaire enregistree [ref={}, sens={}, montant={}, contrepartie={}]",
            saved.getReference(), saved.getSens(), montant, compteContrepartie.getNumero());
        return saved;
    }

    /** Decaissement bancaire multi-lignes (paiement d'une note de frais). */
    @Transactional
    public TransactionBancaire enregistrerDecaissementMultiLigneBancaire(TransactionBancaire transaction,
                                                                          CompteOHADA banque,
                                                                          List<LigneDebit> lignesDebit,
                                                                          String libelle,
                                                                          ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());
        if (lignesDebit == null || lignesDebit.isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne de debit est requise");
        }
        BigDecimal sommeLignes = lignesDebit.stream()
            .map(LigneDebit::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeLignes.compareTo(transaction.getMontant()) != 0) {
            throw new IllegalStateException("Somme des lignes de debit (" + sommeLignes
                + ") differente du montant de la transaction (" + transaction.getMontant() + ")");
        }

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        for (LigneDebit ld : lignesDebit) {
            lignes.add(ecriture(ld.compte(), ld.montant(), BigDecimal.ZERO, texte, dateEcriture, conversion));
        }
        lignes.add(ecriture(banque, BigDecimal.ZERO, transaction.getMontant(), texte, dateEcriture, conversion));

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionBancaire saved = transactionBancaireRepository.save(transaction);
        comptabiliteService.creerPieceInterne(
            JournalComptable.BANQUE, texte, dateEcriture, saved.getEcritures(), transaction.getOperateur());
        log.info("Ecriture bancaire multi-ligne enregistree [ref={}, sens={}, montant={}, lignes={}]",
            saved.getReference(), saved.getSens(), transaction.getMontant(), lignesDebit.size());
        return saved;
    }

    // -------------------------------------------------------------------
    // Mobile Money (memes conventions, compte 582, journal MOBILE_MONEY)
    // -------------------------------------------------------------------

    /**
     * Saisie directe (encaissement/decaissement) sur un compte mobile money.
     *
     * @param mobileMoney compte de tresorerie de l'operateur choisi : chaque
     *                    operateur porte son propre solde.
     */
    @Transactional
    public TransactionMobileMoney enregistrerMobileMoney(TransactionMobileMoney transaction,
                                                          CompteOHADA mobileMoney,
                                                          CompteOHADA compteContrepartie,
                                                          String libelle,
                                                          ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        BigDecimal montant = transaction.getMontant();
        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        if (transaction.getSens() == SensTransaction.DECAISSEMENT) {
            lignes.add(ecriture(compteContrepartie, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(mobileMoney, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        } else {
            lignes.add(ecriture(mobileMoney, montant, BigDecimal.ZERO, texte, dateEcriture, conversion));
            lignes.add(ecriture(compteContrepartie, BigDecimal.ZERO, montant, texte, dateEcriture, conversion));
        }

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionMobileMoney saved = transactionMobileMoneyRepository.save(transaction);
        comptabiliteService.creerPieceInterne(
            JournalComptable.MOBILE_MONEY, texte, dateEcriture, saved.getEcritures(), transaction.getOperateur());
        log.info("Ecriture mobile money enregistree [ref={}, sens={}, montant={}, contrepartie={}]",
            saved.getReference(), saved.getSens(), montant, compteContrepartie.getNumero());
        return saved;
    }

    /** Decaissement mobile money multi-lignes (paiement d'une note de frais). */
    @Transactional
    public TransactionMobileMoney enregistrerDecaissementMultiLigneMobileMoney(TransactionMobileMoney transaction,
                                                                                CompteOHADA mobileMoney,
                                                                                List<LigneDebit> lignesDebit,
                                                                                String libelle,
                                                                                ConversionDeviseService.Conversion conversion) {
        validerMontant(transaction.getMontant());
        if (lignesDebit == null || lignesDebit.isEmpty()) {
            throw new IllegalArgumentException("Au moins une ligne de debit est requise");
        }
        BigDecimal sommeLignes = lignesDebit.stream()
            .map(LigneDebit::montant).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sommeLignes.compareTo(transaction.getMontant()) != 0) {
            throw new IllegalStateException("Somme des lignes de debit (" + sommeLignes
                + ") differente du montant de la transaction (" + transaction.getMontant() + ")");
        }

        LocalDate dateEcriture = (transaction.getDateOperation() == null
            ? Instant.now() : transaction.getDateOperation())
            .atZone(ZoneOffset.UTC).toLocalDate();
        periodeService.verifierDateOuverte(dateEcriture);

        String texte = StringUtils.hasText(libelle) ? libelle : transaction.getReference();

        List<EcritureGrandLivre> lignes = new ArrayList<>();
        for (LigneDebit ld : lignesDebit) {
            lignes.add(ecriture(ld.compte(), ld.montant(), BigDecimal.ZERO, texte, dateEcriture, conversion));
        }
        lignes.add(ecriture(mobileMoney, BigDecimal.ZERO, transaction.getMontant(), texte, dateEcriture, conversion));

        for (EcritureGrandLivre l : lignes) {
            transaction.addEcriture(l);
        }

        TransactionMobileMoney saved = transactionMobileMoneyRepository.save(transaction);
        comptabiliteService.creerPieceInterne(
            JournalComptable.MOBILE_MONEY, texte, dateEcriture, saved.getEcritures(), transaction.getOperateur());
        log.info("Ecriture mobile money multi-ligne enregistree [ref={}, sens={}, montant={}, lignes={}]",
            saved.getReference(), saved.getSens(), transaction.getMontant(), lignesDebit.size());
        return saved;
    }

    /** Resout un compte par son numero ou leve une 404 metier. */
    @Transactional(readOnly = true)
    public CompteOHADA compteParNumero(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> RessourceIntrouvableException.of("CompteOHADA", numero));
    }

    /**
     * Construit une ecriture, en portant la piste d'audit devise propre a
     * <em>cette</em> ligne.
     *
     * <p>Le montant en devise est recalcule a partir du montant en francs de
     * la ligne, et non repris de la conversion globale : sur une piece
     * multi-lignes, la conversion porte le montant TOTAL de l'operation, et le
     * recopier tel quel sur chaque ligne surevaluerait la piste d'audit d'un
     * facteur egal au nombre de lignes.</p>
     */
    private EcritureGrandLivre ecriture(CompteOHADA compte, BigDecimal debit, BigDecimal credit,
                                        String libelle, LocalDate date,
                                        ConversionDeviseService.Conversion conversion) {
        var builder = EcritureGrandLivre.builder()
            .compte(compte)
            .debit(debit)
            .credit(credit)
            .libelle(libelle)
            .dateEcriture(date);
        if (conversion != null && conversion.estConvertie()) {
            BigDecimal taux = conversion.tauxApplique();
            BigDecimal montantLigne = nz(debit).add(nz(credit));
            builder.devise(conversion.deviseOrigine().name())
                .montantDevise(taux != null && taux.signum() > 0
                    // Delegue : le sens du taux depend de la devise de base, la
                    // regle est centralisee dans ConversionDeviseService.
                    ? conversionDevise.depuisBase(montantLigne, conversion.deviseOrigine(), taux)
                    : conversion.montantOrigine())
                .tauxApplique(taux);
        }
        return builder.build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void validerMontant(BigDecimal montant) {
        if (montant == null || montant.signum() <= 0) {
            throw new IllegalArgumentException("Le montant doit etre strictement positif");
        }
    }

    /** Compte de charges diverses par defaut, utilise en fallback quand une ligne de note n'a pas de compte d'imputation. */
    @Transactional(readOnly = true)
    public CompteOHADA compteChargesDiversesParDefaut() {
        return compteRepository.findByNumero("6588")
            .orElseThrow(() -> new IllegalStateException(
                "Compte de charges par defaut 6588 absent du plan comptable"));
    }

    /**
     * Compte de produits divers par defaut, pendant de
     * {@link #compteChargesDiversesParDefaut()} pour une ligne de note
     * d'encaissement sans compte d'imputation.
     */
    @Transactional(readOnly = true)
    public CompteOHADA compteProduitsDiversParDefaut() {
        return compteRepository.findByNumero("758")
            .orElseThrow(() -> new IllegalStateException(
                "Compte de produits par defaut 758 absent du plan comptable"));
    }

    /** Expose le caissier pour les libelles d'audit. */
    public String descriptionCaissier(User caissier) {
        return caissier == null ? "?" : caissier.getEmail();
    }
}
