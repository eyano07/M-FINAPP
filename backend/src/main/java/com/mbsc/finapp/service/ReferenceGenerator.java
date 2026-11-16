package com.mbsc.finapp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Year;

/**
 * Genere les references metier lisibles (ex. {@code NF-2026-000123}).
 *
 * <p>S'appuie sur des sequences PostgreSQL dediees (voir migration V2) afin de
 * garantir l'unicite meme sous forte concurrence et sur plusieurs instances de
 * l'application. La sequence avance dans une transaction propre
 * ({@link Propagation#REQUIRES_NEW}) : un rollback metier ulterieur ne libere
 * pas le numero (comportement standard et acceptable pour une reference).</p>
 */
@Service
@RequiredArgsConstructor
public class ReferenceGenerator {

    private static final String PREFIXE_PIECE = "PC";
    private static final String PREFIXE_BROUILLON = "BR-";

    @PersistenceContext
    private EntityManager em;

    /** Reference d'une note de frais : NF-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourNoteFrais() {
        return format("NF", "seq_note_frais");
    }

    /** Reference d'une transaction de caisse : TRX-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourTransaction() {
        return format("TRX", "seq_transaction_caisse");
    }

    /** Numero de recu de caisse : REC-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourRecu() {
        return format("REC", "seq_recu_caisse");
    }

    /** Reference d'un budget : BUD-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourBudget() {
        return format("BUD", "seq_budget");
    }

    /**
     * Numero definitif d'une piece comptable : PC-AAAA-NNNNNN, ou AAAA est l'annee de la DATE DE LA PIECE (son
     * exercice) et NNNNNN le rang de sa validation dans cet exercice (audit SYSCOHADA du 10/11/2026, C-05).
     *
     * <p>Contrairement aux autres references, le compteur avance dans la transaction de l'appelant
     * ({@link Propagation#MANDATORY}) : si la comptabilisation echoue, le numero n'est pas consomme, et la suite des
     * numeros d'un exercice reste continue, sans blanc. La ligne du compteur est verrouillee jusqu'a la fin de la
     * transaction : deux validations simultanees se suivent.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String pourPieceDefinitive(LocalDate datePiece) {
        int annee = datePiece.getYear();
        Number valeur = (Number) em.createNativeQuery(
                "insert into compteurs_pieces (annee, dernier) values (?1, 1) "
                + "on conflict (annee) do update set dernier = compteurs_pieces.dernier + 1 returning dernier")
            .setParameter(1, annee)
            .getSingleResult();
        return "%s-%d-%06d".formatted(PREFIXE_PIECE, annee, valeur.longValue());
    }

    /**
     * Numero provisoire d'un brouillon : BR-NNNNNN. Un brouillon supprime ou abandonne ne laisse ainsi aucun blanc
     * dans la numerotation definitive, attribuee a la validation ({@link #pourPieceDefinitive}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourBrouillon() {
        Number valeur = (Number) em.createNativeQuery("SELECT nextval('seq_piece_brouillon')").getSingleResult();
        return "%s%06d".formatted(PREFIXE_BROUILLON, valeur.longValue());
    }

    /** true si la reference est un numero provisoire de brouillon. */
    public static boolean estProvisoire(String reference) {
        return reference != null && reference.startsWith(PREFIXE_BROUILLON);
    }

    /** Reference d'un mouvement de stock : MS-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourMouvementStock() {
        return format("MS", "seq_mouvement_stock");
    }

    /** Reference d'un bien immobilise : IM-2026-000001. */
    public String pourImmobilisation() {
        return format("IM", "seq_immobilisation");
    }

    /** Reference d'un trajet : TRJ-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourTrajet() {
        return format("TRJ", "seq_trajet");
    }

    /** Reference d'une depense vehicule : DEP-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourDepense() {
        return format("DEP", "seq_depense_vehicule");
    }

    /** Reference d'une transaction bancaire : TRB-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourTransactionBancaire() {
        return format("TRB", "seq_transaction_bancaire");
    }

    /** Numero de recu bancaire : RECB-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourRecuBanque() {
        return format("RECB", "seq_recu_banque");
    }

    /** Reference d'une transaction mobile money : TRM-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourTransactionMobileMoney() {
        return format("TRM", "seq_transaction_mobile_money");
    }

    /** Numero de recu mobile money : RECM-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourRecuMobileMoney() {
        return format("RECM", "seq_recu_mobile_money");
    }

    /** Reference d'une vente : VTE-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourVente() {
        return format("VTE", "seq_vente");
    }

    /** Reference d'un retour de vente rembourse : RV-AAAA-NNNNNN (V134). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourRetourVente() {
        return format("RV", "seq_retour_vente");
    }

    /** Reference d'une provision : PROV-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourProvision() {
        return format("PROV", "seq_provision");
    }

    /** Reference d'une production de plats : PRD-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourProduction() {
        return format("PRD", "seq_production");
    }

    private String format(String prefixe, String sequence) {
        Number valeur = (Number) em
            .createNativeQuery("SELECT nextval('" + sequence + "')")
            .getSingleResult();
        return "%s-%d-%06d".formatted(prefixe, Year.now().getValue(), valeur.longValue());
    }
}
