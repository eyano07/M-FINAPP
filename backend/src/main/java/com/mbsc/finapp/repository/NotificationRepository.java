package com.mbsc.finapp.repository;

import com.mbsc.finapp.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByDestinataireIdOrderByDateCreationDesc(Long destinataireId);

    long countByDestinataireIdAndLueFalse(Long destinataireId);

    @Modifying
    @Query("UPDATE Notification n SET n.lue = true WHERE n.id = :id AND n.destinataire.id = :destinataireId")
    int marquerLue(@Param("id") Long id, @Param("destinataireId") Long destinataireId);

    @Modifying
    @Query("UPDATE Notification n SET n.lue = true WHERE n.destinataire.id = :destinataireId AND n.lue = false")
    int marquerToutesLues(@Param("destinataireId") Long destinataireId);
}
