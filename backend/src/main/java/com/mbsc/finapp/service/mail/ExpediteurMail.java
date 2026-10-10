package com.mbsc.finapp.service.mail;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Properties;

/** Construit l'expéditeur JavaMail à partir de la configuration courante (reconstruit si elle change). */
@Component
public class ExpediteurMail {

    private record Memo(ConfigMail config, JavaMailSenderImpl sender) {}

    private volatile Memo memo;

    public JavaMailSender pour(ConfigMail c) {
        Memo m = memo;
        if (m != null && m.config().equals(c)) return m.sender();
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(c.hote());
        s.setPort(c.port());
        s.setDefaultEncoding("UTF-8");
        Properties p = s.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "8000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        if (StringUtils.hasText(c.utilisateur())) {
            s.setUsername(c.utilisateur());
            s.setPassword(c.motDePasse());
            p.put("mail.smtp.auth", "true");
        }
        switch (c.securite() == null ? "AUCUNE" : c.securite()) {
            case "SSL" -> p.put("mail.smtp.ssl.enable", "true");
            case "STARTTLS" -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            default -> { }
        }
        memo = new Memo(c, s);
        return s;
    }
}
