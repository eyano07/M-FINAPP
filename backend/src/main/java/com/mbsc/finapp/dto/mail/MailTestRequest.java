package com.mbsc.finapp.dto.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * POST /admin/mail/tester. {@code destinataire} absent = mail de notification de l'administrateur, à défaut son
 * identifiant de connexion s'il s'agit d'une adresse e-mail.
 */
public record MailTestRequest(@Email @Size(max = 150) String destinataire) {}
