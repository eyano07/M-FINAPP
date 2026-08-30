package com.mbsc.finapp.dto.notifications;

import java.util.List;

/** Liste des notifications recentes du destinataire courant, avec le compteur de non lues (badge). */
public record NotificationsPageResponse(
    List<NotificationResponse> notifications,
    long nonLues
) {}
