package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "mot_de_passe", nullable = false)
    private String motDePasse;   // hash BCrypt

    @Column(length = 100)
    private String nom;

    @Column(length = 100)
    private String prenom;

    @Column(length = 30)
    private String telephone;

    /** Adresse recevant une copie e-mail de chaque notification (facultatif). */
    @Column(name = "email_notification", length = 150)
    private String emailNotification;

    @Column(length = 100)
    private String fonction;

    @Column(length = 100)
    private String affectation;

    @Column(name = "photo_chemin_stockage")
    private String photoCheminStockage;

    @Column(name = "photo_type_mime", length = 100)
    private String photoTypeMime;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    /**
     * Version des jetons JWT de l'utilisateur (claim « tv », voir JwtService) :
     * l'augmenter rend inutilisables tous les jetons déjà émis (V96).
     */
    @Column(name = "version_jetons", nullable = false)
    @Builder.Default
    private int versionJetons = 0;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;

    /** Rend inutilisables tous les jetons déjà émis pour cet utilisateur (voir {@link #versionJetons}). */
    public void revoquerJetons() {
        versionJetons++;
    }
}
