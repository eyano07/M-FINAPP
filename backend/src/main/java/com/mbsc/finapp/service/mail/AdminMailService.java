package com.mbsc.finapp.service.mail;

import com.mbsc.finapp.domain.ParametresMail;
import com.mbsc.finapp.dto.mail.MailEnregistrerRequest;
import com.mbsc.finapp.dto.mail.MailEtatResponse;
import com.mbsc.finapp.dto.mail.MailTestRequest;
import com.mbsc.finapp.dto.mail.MailTestResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.MailNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Administration › Messagerie : paramètres SMTP et e-mail de test. Réservé à l'administrateur. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMailService {

    private final ParametresMailService parametres;
    private final MailNotificationService mails;
    private final CurrentUserProvider currentUser;

    public MailEtatResponse etat() {
        parametres.restaurerErreur();
        ConfigMail c = parametres.config();
        ParametresMail p = parametres.ligne();
        String fin = p != null ? p.getMotDePasseFin() : ParametresMailService.fin(c.motDePasse());
        return new MailEtatResponse(c.actif(), c.hote(), c.port(), c.securite() == null ? "STARTTLS" : c.securite(),
            c.utilisateur(), StringUtils.hasText(c.motDePasse()), fin, c.expediteur(), c.urlPublique(),
            parametres.viaEnvironnement(), c.pret(), parametres.derniereErreur(), parametres.derniereErreurLe());
    }

    public MailEtatResponse enregistrer(MailEnregistrerRequest req) {
        String hote = StringUtils.hasText(req.hote()) ? req.hote().strip() : null;
        if (req.actif() && hote == null) {
            throw new TransitionInvalideException("Renseignez le serveur SMTP avant d'activer l'envoi des e-mails.");
        }
        if (hote != null && !hote.matches("[A-Za-z0-9._\\-]+")) {
            throw new TransitionInvalideException("Nom de serveur invalide : saisissez seulement le nom (ex. smtp.gmail.com).");
        }
        String expediteur = StringUtils.hasText(req.expediteur()) ? req.expediteur().strip() : null;
        if (expediteur != null && !expediteur.matches("[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+")) {
            throw new TransitionInvalideException("Adresse d'expédition invalide.");
        }
        String url = StringUtils.hasText(req.urlPublique()) ? req.urlPublique().strip() : null;
        if (url != null && !url.matches("(?i)https?://[^\\s]+")) {
            throw new TransitionInvalideException("L'URL publique doit commencer par http:// ou https://.");
        }
        var auteur = currentUser.requireUser();
        String mdp = StringUtils.hasText(req.motDePasse()) ? req.motDePasse() : null;
        parametres.modifier(p -> {
            p.setActif(req.actif());
            p.setHote(hote);
            p.setPort(req.port() == null ? 587 : req.port());
            p.setSecurite(req.securite() == null ? "STARTTLS" : req.securite());
            p.setUtilisateur(StringUtils.hasText(req.utilisateur()) ? req.utilisateur().strip() : null);
            if (mdp != null) {
                p.setMotDePasseChiffre(parametres.chiffrer(mdp));
                p.setMotDePasseFin(ParametresMailService.fin(mdp));
            }
            p.setExpediteur(expediteur);
            p.setUrlPublique(url);
        }, auteur);
        return etat();
    }

    public MailEtatResponse retirerMotDePasse() {
        parametres.modifier(p -> {
            p.setMotDePasseChiffre(null);
            p.setMotDePasseFin(null);
        }, currentUser.requireUser());
        return etat();
    }

    public MailTestResponse tester(MailTestRequest req) {
        var admin = currentUser.requireUser();
        String dest = req != null && StringUtils.hasText(req.destinataire()) ? req.destinataire().strip()
            : StringUtils.hasText(admin.getEmailNotification()) ? admin.getEmailNotification() : admin.getEmail();
        try {
            mails.envoyerTest(dest, admin.getPrenom());
            parametres.noterEnvoi(null);
            return new MailTestResponse(true, "E-mail de test envoyé à " + dest + ". Vérifiez la boîte de réception (et les courriers indésirables).");
        } catch (Exception e) {
            String msg = ErreurMail.expliquer(e);
            parametres.noterEnvoi(msg);
            return new MailTestResponse(false, msg);
        }
    }
}
