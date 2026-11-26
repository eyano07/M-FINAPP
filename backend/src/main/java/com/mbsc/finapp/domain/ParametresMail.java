package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** Paramètres de messagerie SMTP (ligne unique) : voir {@code ParametresMailService}. */
@Entity
@Table(name = "parametres_mail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParametresMail {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean actif;

    @Column(length = 200)
    private String hote;

    @Column(nullable = false)
    @Builder.Default
    private int port = 587;

    /** STARTTLS, SSL ou AUCUNE. */
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String securite = "STARTTLS";

    @Column(length = 200)
    private String utilisateur;

    @Column(name = "mot_de_passe_chiffre", length = 1000)
    private String motDePasseChiffre;

    @Column(name = "mot_de_passe_fin", length = 8)
    private String motDePasseFin;

    @Column(length = 200)
    private String expediteur;

    @Column(name = "url_publique", length = 300)
    private String urlPublique;

    @Column(name = "derniere_erreur", length = 500)
    private String derniereErreur;

    @Column(name = "derniere_erreur_le")
    private Instant derniereErreurLe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modifie_par_id")
    @ToString.Exclude
    private User modifiePar;

    @Column(name = "date_maj", nullable = false)
    @Builder.Default
    private Instant dateMaj = Instant.now();
}
