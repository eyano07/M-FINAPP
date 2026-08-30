package com.mbsc.desktop.service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.desktop.api.SyncDtos;
import com.mbsc.desktop.local.OutboxDao;
import com.mbsc.desktop.local.TransactionDao;
import com.mbsc.desktop.model.LocalEcriture;
import com.mbsc.desktop.model.LocalTransaction;
import com.mbsc.desktop.model.SensTransaction;
import com.mbsc.desktop.model.SyncOutbox;

/**
 * Service de caisse : enregistre un paiement hors-ligne en H2 et alimente
 * l'Outbox de synchronisation. La double ecriture comptable (Grand Livre)
 * est generee automatiquement.
 *
 * Convention comptable (decaissement note de frais) :
 *   - Debit  : compte de charge impute (ex: 6xxx)
 *   - Credit : compte Caisse 571
 */
public class CaisseService {

    private static final Logger log = LoggerFactory.getLogger(CaisseService.class);

    private static final String COMPTE_CAISSE = "571";

    private final TransactionDao transactionDao = new TransactionDao();
    private final OutboxDao outboxDao = new OutboxDao();
    private final ObjectMapper mapper;

    // Compteur simple de references (par session). En production : sequence serveur.
    private final AtomicInteger seq = new AtomicInteger(1);

    public CaisseService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Execute un paiement : cree la transaction + ecritures, persiste en H2,
     * depose l'entree d'Outbox. Retourne la transaction enregistree.
     */
    public LocalTransaction enregistrerPaiement(
        String noteReference,
        String objet,
        BigDecimal montant,
        String compteCharge
    ) {
        // Validations defensives (frontiere du systeme)
        if (objet == null || objet.isBlank()) {
            throw new IllegalArgumentException("L'objet du paiement est obligatoire");
        }
        if (montant == null || montant.signum() <= 0) {
            throw new IllegalArgumentException("Le montant doit etre strictement positif");
        }
        if (compteCharge == null || compteCharge.isBlank()) {
            throw new IllegalArgumentException("Le compte de charge est obligatoire");
        }

        LocalTransaction tx = new LocalTransaction();
        tx.setReference(genererReference("TRX"));
        tx.setNoteReference(noteReference);
        tx.setMontant(montant);
        tx.setSens(SensTransaction.DECAISSEMENT);
        tx.setNumeroRecu(genererReference("RECU"));
        tx.setCaissierEmail(SessionContext.email());

        // Double ecriture comptable
        tx.addEcriture(new LocalEcriture(
            compteCharge, objet, montant, BigDecimal.ZERO));
        tx.addEcriture(new LocalEcriture(
            COMPTE_CAISSE, objet, BigDecimal.ZERO, montant));

        LocalTransaction saved = transactionDao.save(tx);
        enqueueSync(saved);
        log.info("Paiement enregistre hors-ligne : ref={}, montant={}, uuid={}",
            saved.getReference(), saved.getMontant(), saved.getUuid());
        return saved;
    }

    /**
     * Enregistre un encaissement hors-ligne (credit caisse 571, debit compte produit).
     * Alimente l'Outbox pour synchronisation ulterieure.
     */
    public LocalTransaction enregistrerEncaissement(
        String libelle,
        BigDecimal montant,
        String compteContrepartie
    ) {
        if (libelle == null || libelle.isBlank()) {
            throw new IllegalArgumentException("Le libelle de l'encaissement est obligatoire");
        }
        if (montant == null || montant.signum() <= 0) {
            throw new IllegalArgumentException("Le montant doit etre strictement positif");
        }
        if (compteContrepartie == null || compteContrepartie.isBlank()) {
            throw new IllegalArgumentException("Le compte contrepartie est obligatoire");
        }

        LocalTransaction tx = new LocalTransaction();
        tx.setReference(genererReference("TRX"));
        tx.setNoteReference(null);
        tx.setMontant(montant);
        tx.setSens(SensTransaction.ENCAISSEMENT);
        tx.setNumeroRecu(genererReference("RECU"));
        tx.setCaissierEmail(SessionContext.email());

        // Double ecriture : Debit caisse 571 / Credit compte produit
        tx.addEcriture(new LocalEcriture(COMPTE_CAISSE, libelle, montant, BigDecimal.ZERO));
        tx.addEcriture(new LocalEcriture(compteContrepartie, libelle, BigDecimal.ZERO, montant));

        LocalTransaction saved = transactionDao.save(tx);
        enqueueSync(saved);
        log.info("Encaissement enregistre hors-ligne : ref={}, montant={}, uuid={}",
            saved.getReference(), saved.getMontant(), saved.getUuid());
        return saved;
    }

    /** Serialise la transaction et la place dans l'Outbox (PENDING). */
    private void enqueueSync(LocalTransaction tx) {
        try {
            List<SyncDtos.EcritureDto> ecritures = tx.getEcritures().stream()
                .map(e -> new SyncDtos.EcritureDto(
                    e.getNumeroCompte(),
                    e.getLibelle(),
                    e.getDebit(),
                    e.getCredit(),
                    e.getDateEcriture().toString()))
                .toList();

            SyncDtos.TransactionSyncDto dto = new SyncDtos.TransactionSyncDto(
                tx.getUuid(),
                tx.getReference(),
                tx.getNoteReference(),
                tx.getMontant(),
                tx.getSens().name(),
                tx.getNumeroRecu(),
                tx.getCaissierEmail(),
                DateTimeFormatter.ISO_INSTANT.format(tx.getDateOperation()),
                ecritures
            );

            String payload = mapper.writeValueAsString(dto);
            outboxDao.enqueue(new SyncOutbox(
                tx.getUuid(), "TRANSACTION", "CREATE", payload));
        } catch (Exception e) {
            throw new RuntimeException("Echec de mise en file de synchronisation", e);
        }
    }

    public List<LocalTransaction> historique() {
        return transactionDao.findAll();
    }

    /** Ecritures du grand livre (caisse hors-ligne), pour affichage. */
    public List<LocalEcriture> grandLivre() {
        return transactionDao.findAllEcritures();
    }

    public long enAttenteSync() {
        return outboxDao.countPending();
    }

    /**
     * Solde courant de la caisse (somme encaissements - somme decaissements).
     * Calcul base sur les donnees locales H2.
     */
    public BigDecimal solde() {
        BigDecimal s = BigDecimal.ZERO;
        for (LocalTransaction t : transactionDao.findAll()) {
            if (t.getSens() == SensTransaction.ENCAISSEMENT) {
                s = s.add(t.getMontant());
            } else {
                s = s.subtract(t.getMontant());
            }
        }
        return s;
    }

    /** Nombre de paiements (decaissements) enregistres aujourd'hui. */
    public long notesPayeesAujourdhui() {
        return transactionDao.countDecaissementAujourdhui();
    }

    private String genererReference(String prefix) {
        return prefix + "-" + java.time.Year.now().getValue()
            + "-" + String.format("%06d", seq.getAndIncrement())
            + "-" + Long.toString(System.nanoTime(), 36).substring(0, 4).toUpperCase();
    }
}
