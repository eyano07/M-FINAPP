package com.mbsc.finapp.dto.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** POST /admin/mail/tester. {@code destinataire} absent = mail de notification (ou e-mail) de l'administrateur. */
public record MailTestRequest(@Email @Size(max = 150) String destinataire) {}
