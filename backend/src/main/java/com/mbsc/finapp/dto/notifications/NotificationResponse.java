package com.mbsc.finapp.dto.notifications;

import com.mbsc.finapp.domain.Notification;
import com.mbsc.finapp.domain.enums.TypeNotification;

import java.time.Instant;

public record NotificationResponse(
    Long id,
    TypeNotification type,
    String titre,
    String message,
    String lien,
    boolean lue,
    Instant dateCreation
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
            n.getId(),
            n.getType(),
            n.getTitre(),
            n.getMessage(),
            n.getLien(),
            n.isLue(),
            n.getDateCreation()
        );
    }
}
