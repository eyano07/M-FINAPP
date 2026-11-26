package com.mbsc.finapp.dto.mail;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PUT /admin/mail. {@code motDePasse} absent ou vide = inchange. */
public record MailEnregistrerRequest(
    boolean actif,
    @Size(max = 200) String hote,
    @Min(1) @Max(65535) Integer port,
    @Pattern(regexp = "STARTTLS|SSL|AUCUNE", message = "Securite invalide") String securite,
    @Size(max = 200) String utilisateur,
    @Size(max = 200) String motDePasse,
    @Size(max = 200) String expediteur,
    @Size(max = 300) String urlPublique
) {}
