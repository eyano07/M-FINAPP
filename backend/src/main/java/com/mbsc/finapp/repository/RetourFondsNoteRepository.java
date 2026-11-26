package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.RetourFondsNote;
import com.mbsc.finapp.domain.enums.StatutRetourFonds;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RetourFondsNoteRepository extends JpaRepository<RetourFondsNote, Long> {

    /** La demande en attente d'une note (au plus une : index unique partiel de V127). */
    Optional<RetourFondsNote> findFirstByNoteIdAndStatut(Long noteId, StatutRetourFonds statut);

    /** Demandes d'un statut, de la plus ancienne a la plus recente (file d'attente de la caisse). */
    List<RetourFondsNote> findByStatutOrderByDateDemandeAscIdAsc(StatutRetourFonds statut);

    /**
     * La demande, verrouillee jusqu'a la fin de la transaction : deux caissiers ne valident pas la meme demande, et une
     * annulation ne croise pas une validation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RetourFondsNote r where r.id = :id")
    Optional<RetourFondsNote> findPourMiseAJour(@Param("id") Long id);
}
