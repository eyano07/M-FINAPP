package com.mbsc.finapp.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /** Reference d'une piece comptable : PC-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourPiece() {
        return format("PC", "seq_piece_comptable");
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

    /** Reference d'une provision : PROV-AAAA-NNNNNN. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String pourProvision() {
        return format("PROV", "seq_provision");
    }

    private String format(String prefixe, String sequence) {
        Number valeur = (Number) em
            .createNativeQuery("SELECT nextval('" + sequence + "')")
            .getSingleResult();
        return "%s-%d-%06d".formatted(prefixe, Year.now().getValue(), valeur.longValue());
    }
}
