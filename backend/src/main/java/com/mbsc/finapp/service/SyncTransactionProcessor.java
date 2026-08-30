package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.ObservationNote;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.sync.EcritureSyncDto;
import com.mbsc.finapp.dto.sync.TransactionSyncDto;
import com.mbsc.finapp.exception.ConflitSyncException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.TransactionCaisseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Persistance d'UNE transaction synchronisee, dans sa propre transaction
 * ({@link Propagation#REQUIRES_NEW}). Sépare du {@link SyncService} pour que le
 * proxy transactionnel Spring s'applique reellement (pas d'auto-invocation) et
 * pour isoler chaque element du lot.
 */
@Service
@RequiredArgsConstructor
public class SyncTransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(SyncTransactionProcessor.class);

    private final TransactionCaisseRepository transactionRepository;
    private final NoteFraisRepository noteRepository;
    private final CompteOHADARepository compteRepository;
    private final ComptabiliteService comptabiliteService;
    private final PeriodeComptableService periodeService;
    /** Resout le taux de change a la date d'operation de la transaction synchronisee. */
    private final ConversionDeviseService conversionDevise;

    /**
     * @return {@code true} si une nouvelle transaction a ete creee,
     *         {@code false} si elle existait deja (idempotent).
     * @throws ConflitSyncException en cas de conflit metier (ne pas reessayer).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean traiter(TransactionSyncDto dto, User caissierCourant) {
        UUID uuid = parseUuid(dto.uuid());

        if (transactionRepository.existsByUuid(uuid)) {
            log.debug("Transaction {} deja presente, ignoree (idempotent)", uuid);
            return false;
        }

        // Sécurité : le caissier est TOUJOURS l'utilisateur authentifié.
        // Le champ caissierEmail du payload client n'est jamais pris pour argent
        // comptant (risque d'usurpation d'identité).
        User caissier = caissierCourant;
        if (StringUtils.hasText(dto.caissierEmail())
            && !dto.caissierEmail().equalsIgnoreCase(caissier.getEmail())) {
            log.warn("Sync : caissierEmail '{}' ignore, transaction attribuee a l'utilisateur authentifie '{}'",
                dto.caissierEmail(), caissier.getEmail());
        }
        SensTransaction sens = parseSens(dto.sens());

        if (dto.montant() == null || dto.montant().signum() <= 0) {
            throw new ConflitSyncException(
                "Transaction " + uuid + " : montant invalide (" + dto.montant() + ")");
        }

        Instant dateOperation = parseInstant(dto.dateOperation());
        LocalDate dateEcriture = dateOperation.atZone(ZoneOffset.UTC).toLocalDate();

        TransactionCaisse transaction = TransactionCaisse.builder()
            .uuid(uuid)
            .reference(referenceDeRepli(dto.reference(), uuid))
            .montant(dto.montant())
            .sens(sens)
            .caissier(caissier)
            .numeroRecu(dto.numeroRecu())
            .dateOperation(dateOperation)
            // Taux de la date d'operation, et non du jour de la synchronisation :
            // une transaction saisie hors ligne peut remonter plusieurs jours
            // plus tard. Sans cela le taux restait NULL, et toutes les vues qui
            // affichent une contre-valeur en USD retombaient sur le taux courant,
            // faussant retroactivement l'historique de ces operations.
            .tauxJournalier(conversionDevise.tauxALaDate(dateEcriture))
            .build();
        try {
            periodeService.verifierDateOuverte(dateEcriture);
        } catch (RuntimeException ex) {
            throw new ConflitSyncException("Transaction " + uuid + " : " + ex.getMessage());
        }

        NoteFrais note = rattacherNote(dto.noteReference(), transaction);
        ajouterEcritures(dto, transaction);
        verifierEquilibre(dto, transaction);

        TransactionCaisse saved = transactionRepository.save(transaction);

        // Chaque transaction synchronisée est adossée à une pièce comptable
        // équilibrée, comme les saisies directes (aucune écriture orpheline).
        String libellePiece = StringUtils.hasText(dto.noteReference())
            ? "Sync paiement note " + dto.noteReference()
            : "Sync caisse " + saved.getReference();
        comptabiliteService.creerPieceCaisse(libellePiece, dateEcriture, saved.getEcritures(), caissier);

        if (note != null) {
            marquerNotePayee(note, caissier, saved);
        }

        log.info("Transaction synchronisee [uuid={}, ref={}, montant={}, caissier={}]",
            uuid, transaction.getReference(), dto.montant(), caissier.getEmail());
        return true;
    }

    /** Refuse toute transaction dont les écritures ne respectent pas la partie double. */
    private void verifierEquilibre(TransactionSyncDto dto, TransactionCaisse transaction) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (EcritureGrandLivre e : transaction.getEcritures()) {
            BigDecimal d = e.getDebit() == null ? BigDecimal.ZERO : e.getDebit();
            BigDecimal c = e.getCredit() == null ? BigDecimal.ZERO : e.getCredit();
            if (d.signum() < 0 || c.signum() < 0) {
                throw new ConflitSyncException(
                    "Transaction " + dto.uuid() + " : debit/credit negatif interdit");
            }
            if (d.signum() > 0 && c.signum() > 0) {
                throw new ConflitSyncException(
                    "Transaction " + dto.uuid() + " : une ecriture ne peut porter a la fois un debit et un credit");
            }
            if (d.signum() == 0 && c.signum() == 0) {
                throw new ConflitSyncException(
                    "Transaction " + dto.uuid() + " : ecriture sans montant");
            }
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
        }
        if (transaction.getEcritures().size() < 2 || totalDebit.compareTo(totalCredit) != 0) {
            throw new ConflitSyncException(
                "Transaction " + dto.uuid() + " : ecritures non equilibrees (debit="
                + totalDebit + ", credit=" + totalCredit + ")");
        }
        // Des ecritures equilibrees entre elles peuvent malgre tout etre sans
        // rapport avec la transaction : equilibrer 50 000 000 au debit et au
        // credit satisfait le controle precedent alors que la transaction
        // declare 100. On exige donc que la contre-valeur des ecritures soit
        // exactement le montant encaisse/decaisse, comme le verifie
        // EcritureComptableService sur les saisies en ligne.
        if (totalDebit.compareTo(transaction.getMontant()) != 0) {
            throw new ConflitSyncException(
                "Transaction " + dto.uuid() + " : les ecritures (" + totalDebit
                + ") ne correspondent pas au montant de la transaction ("
                + transaction.getMontant() + ")");
        }
    }

    /**
     * Rattache la note reglee par la transaction synchronisee.
     *
     * <p>Applique exactement les memes garde-fous que le paiement en ligne
     * ({@code CaisseService.payerNote}) : une transaction arrivant du poste
     * hors ligne ne doit pas pouvoir court-circuiter le circuit de validation
     * DFIN/DA, ni regler deux fois la meme note. Le controle du deja-regle
     * porte sur {@link NoteFrais#estDejaReglee()} — et non sur la seule
     * transaction de caisse — car une note peut avoir ete payee entre-temps
     * par banque ou par mobile money pendant que le poste etait hors ligne.</p>
     */
    private NoteFrais rattacherNote(String noteReference, TransactionCaisse transaction) {
        if (!StringUtils.hasText(noteReference)) {
            return null;
        }
        NoteFrais note = noteRepository.findByReference(noteReference).orElse(null);
        if (note == null) {
            log.warn("Note {} introuvable pour la transaction synchronisee (ignoree)", noteReference);
            return null;
        }
        if (note.estDejaReglee()) {
            throw new ConflitSyncException(
                "Note " + noteReference + " deja reglee par une transaction de tresorerie"
                + " (caisse, banque ou mobile money)");
        }
        if (note.getStatut() != StatutNote.TRANSMISE_CAISSE) {
            throw new ConflitSyncException(
                "Note " + noteReference + " : seule une note TRANSMISE_CAISSE peut etre payee"
                + " (etat actuel : " + note.getStatut() + ")");
        }
        transaction.setNoteFrais(note);
        return note;
    }

    private void ajouterEcritures(TransactionSyncDto dto, TransactionCaisse transaction) {
        List<EcritureSyncDto> ecritures = dto.ecritures() == null ? List.of() : dto.ecritures();
        if (ecritures.isEmpty()) {
            throw new IllegalStateException("Transaction " + dto.uuid() + " sans ecriture comptable");
        }
        LocalDate defaut = transaction.getDateOperation().atZone(ZoneOffset.UTC).toLocalDate();

        for (EcritureSyncDto e : ecritures) {
            CompteOHADA compte = compteRepository.findByNumero(e.numeroCompte())
                .orElseThrow(() -> new IllegalStateException(
                    "Compte " + e.numeroCompte() + " absent du plan comptable"));
            transaction.addEcriture(EcritureGrandLivre.builder()
                .compte(compte)
                .debit(e.debit() == null ? BigDecimal.ZERO : e.debit())
                .credit(e.credit() == null ? BigDecimal.ZERO : e.credit())
                .libelle(e.libelle())
                .dateEcriture(parseDate(e.dateEcriture(), defaut))
                .build());
        }
    }

    private void marquerNotePayee(NoteFrais note, User caissier, TransactionCaisse transaction) {
        note.setStatut(StatutNote.PAYEE);
        note.addObservation(ObservationNote.builder()
            .noteFrais(note)
            .auteur(caissier)
            .statutAuMoment(StatutNote.PAYEE)
            .commentaire("Paiement synchronise depuis la caisse (recu "
                + transaction.getNumeroRecu() + ")")
            .build());
    }

    /**
     * Reference de la transaction synchronisee.
     *
     * <p>Le repli etait {@code "TRX-SYNC-" + uuid}, soit 45 caracteres pour une
     * colonne {@code VARCHAR(30)} : toute transaction poussee sans reference
     * echouait donc systematiquement a l'insertion. L'echec etant traite comme
     * une erreur transitoire, elle n'apparaissait ni en acceptee ni en conflit
     * et disparaissait sans signal cote caissier. On ne conserve desormais que
     * les 8 premiers caracteres de l'uuid, largement suffisants pour tracer une
     * ligne dont l'uuid complet reste stocke a part.</p>
     */
    private String referenceDeRepli(String reference, UUID uuid) {
        if (StringUtils.hasText(reference)) {
            return reference.length() > 30 ? reference.substring(0, 30) : reference;
        }
        return "TRX-SYNC-" + uuid.toString().substring(0, 8);
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("UUID de transaction invalide : " + value);
        }
    }

    private SensTransaction parseSens(String value) {
        try {
            return SensTransaction.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("Sens de transaction invalide : " + value);
        }
    }

    private Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            log.warn("Date d'operation illisible '{}', horodatage serveur utilise", value);
            return Instant.now();
        }
    }

    private LocalDate parseDate(String value, LocalDate defaut) {
        if (!StringUtils.hasText(value)) {
            return defaut;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return defaut;
        }
    }
}
