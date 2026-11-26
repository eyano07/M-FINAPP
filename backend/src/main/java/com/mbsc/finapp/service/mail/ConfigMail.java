package com.mbsc.finapp.service.mail;

import org.springframework.util.StringUtils;

/** Configuration SMTP effective (base d'abord, variables d'environnement en secours). */
public record ConfigMail(boolean actif, String hote, int port, String securite, String utilisateur,
                         String motDePasse, String expediteur, String urlPublique) {

    /** L'envoi est possible : activé et serveur renseigné. */
    public boolean pret() {
        return actif && StringUtils.hasText(hote);
    }

    /** Adresse d'expédition : celle saisie, à défaut l'identifiant s'il ressemble à une adresse. */
    public String expediteurEffectif() {
        if (StringUtils.hasText(expediteur)) return expediteur.strip();
        return StringUtils.hasText(utilisateur) && utilisateur.contains("@") ? utilisateur.strip() : null;
    }
}
