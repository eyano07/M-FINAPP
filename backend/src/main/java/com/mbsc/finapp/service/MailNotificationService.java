package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Notification;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copie e-mail des notifications : si l'utilisateur a renseigne un « mail de notification », chaque notification
 * qui lui est adressee lui est aussi envoyee par courriel, en HTML, avec un bouton vers le lien de la notification.
 *
 * <p>L'envoi n'a lieu qu'apres validation de la transaction metier et sur un fil dedie : une panne SMTP ne bloque
 * ni n'annule jamais l'action de l'utilisateur, elle est seulement journalisee. Sans serveur SMTP configure
 * ({@code spring.mail.host}) ou si {@code app.mail.enabled=false}, rien n'est envoye.</p>
 */
@Service
public class MailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(MailNotificationService.class);

    private final ObjectProvider<JavaMailSender> expediteur;
    private final ParametresEntrepriseRepository entreprise;
    private final boolean actif;
    private final String from;
    private final String urlPublique;

    private final ExecutorService executeur = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notifications-mail");
        t.setDaemon(true);
        return t;
    });

    public MailNotificationService(ObjectProvider<JavaMailSender> expediteur,
                                   ParametresEntrepriseRepository entreprise,
                                   @Value("${app.mail.enabled:false}") boolean actif,
                                   @Value("${spring.mail.host:}") String hoteSmtp,
                                   @Value("${app.mail.from:}") String from,
                                   @Value("${app.public-url:}") String urlPublique) {
        this.expediteur = expediteur;
        this.entreprise = entreprise;
        this.actif = actif && StringUtils.hasText(hoteSmtp);
        this.from = from;
        this.urlPublique = urlPublique == null ? "" : urlPublique.strip().replaceAll("/+$", "");
    }

    @PreDestroy
    void arreter() {
        executeur.shutdown();
    }

    /** Programme l'envoi apres le commit de la transaction en cours (immediat s'il n'y en a pas). */
    public void envoyerApresCommit(Notification notification) {
        String adresse = notification.getDestinataire() == null ? null : notification.getDestinataire().getEmailNotification();
        if (!actif || !StringUtils.hasText(adresse) || expediteur.getIfAvailable() == null) return;

        // Instantane des valeurs : la notification et son destinataire (lazy) ne doivent pas etre lus hors transaction.
        String prenom = notification.getDestinataire().getPrenom();
        String titre = notification.getTitre();
        String message = notification.getMessage();
        String lien = notification.getLien();
        Runnable envoi = () -> executeur.execute(() -> envoyer(adresse.strip(), prenom, titre, message, lien));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    envoi.run();
                }
            });
        } else {
            envoi.run();
        }
    }

    private void envoyer(String adresse, String prenom, String titre, String message, String lien) {
        try {
            JavaMailSender sender = expediteur.getIfAvailable();
            if (sender == null) return;
            ParametresEntreprise e = entreprise.findAll().stream().findFirst().orElse(null);
            String nom = e != null && StringUtils.hasText(e.getNom()) ? e.getNom() : "M-FINAPP";
            String couleur = e != null && StringUtils.hasText(e.getCouleurPrimaire()) ? e.getCouleurPrimaire() : "#15803D";

            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(from)) h.setFrom(from, nom);
            h.setTo(adresse);
            h.setSubject("[" + nom + "] " + titre);
            h.setText(texte(prenom, titre, message, lienAbsolu(lien)), corpsHtml(nom, couleur, prenom, titre, message, lienAbsolu(lien)));
            sender.send(mime);
        } catch (Exception ex) {
            log.warn("Envoi du mail de notification impossible ({}) : {}", adresse, ex.toString());
        }
    }

    /** Lien complet vers l'application (null si l'URL publique n'est pas configuree ou s'il n'y a pas de lien). */
    String lienAbsolu(String lien) {
        if (!StringUtils.hasText(lien) || urlPublique.isEmpty()) return null;
        return lien.startsWith("http") ? lien : urlPublique + (lien.startsWith("/") ? lien : "/" + lien);
    }

    static String texte(String prenom, String titre, String message, String lien) {
        return (StringUtils.hasText(prenom) ? "Bonjour " + prenom + ",\n\n" : "Bonjour,\n\n")
            + titre + "\n" + message + (lien != null ? "\n\nOuvrir : " + lien : "") + "\n";
    }

    /** Courriel HTML autonome (styles en ligne, mise en page en tableau : compatible clients de messagerie). */
    static String corpsHtml(String entreprise, String couleur, String prenom, String titre, String message, String lien) {
        String c = couleur != null && couleur.matches("#[0-9a-fA-F]{6}") ? couleur : "#15803D";
        StringBuilder b = new StringBuilder(2048);
        b.append("<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"></head>")
            .append("<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2937;\">")
            .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f6;padding:24px 12px;\"><tr><td align=\"center\">")
            .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border-radius:12px;overflow:hidden;border:1px solid #e5e7eb;\">")
            .append("<tr><td style=\"background:").append(c).append(";padding:20px 28px;color:#ffffff;font-size:18px;font-weight:700;letter-spacing:.3px;\">")
            .append(echap(entreprise)).append("</td></tr>")
            .append("<tr><td style=\"padding:28px;\">")
            .append("<p style=\"margin:0 0 6px;font-size:14px;color:#6b7280;\">")
            .append(StringUtils.hasText(prenom) ? "Bonjour " + echap(prenom) + "," : "Bonjour,").append("</p>")
            .append("<h1 style=\"margin:0 0 14px;font-size:20px;line-height:1.3;color:#111827;\">").append(echap(titre)).append("</h1>")
            .append("<div style=\"border-left:4px solid ").append(c).append(";background:#f9fafb;padding:12px 16px;border-radius:6px;font-size:15px;line-height:1.55;\">")
            .append(echap(message).replace("\n", "<br>")).append("</div>");
        if (lien != null) {
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:26px 0 8px;\"><tr><td style=\"background:")
                .append(c).append(";border-radius:8px;\"><a href=\"").append(echap(lien))
                .append("\" style=\"display:inline-block;padding:12px 26px;color:#ffffff;text-decoration:none;font-weight:600;font-size:15px;\">Ouvrir la notification</a></td></tr></table>")
                .append("<p style=\"margin:0;font-size:12px;color:#6b7280;word-break:break-all;\">Si le bouton ne fonctionne pas, copiez ce lien : <a href=\"")
                .append(echap(lien)).append("\" style=\"color:").append(c).append(";\">").append(echap(lien)).append("</a></p>");
        }
        b.append("</td></tr><tr><td style=\"padding:16px 28px;background:#f9fafb;border-top:1px solid #e5e7eb;font-size:12px;color:#9ca3af;\">")
            .append("Ce message est une copie automatique d'une notification de ").append(echap(entreprise))
            .append(". Pour ne plus le recevoir, videz le champ « Mail de notification » dans votre profil.")
            .append("</td></tr></table></td></tr></table></body></html>");
        return b.toString();
    }

    static String echap(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
