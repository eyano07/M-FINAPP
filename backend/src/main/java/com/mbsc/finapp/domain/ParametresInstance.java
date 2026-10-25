package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.ModeInstance;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Mode de ce serveur et appairage avec l'autre serveur (ligne unique, propre a chaque serveur, jamais recopiee : V132).
 * Voir {@code InstanceService}.
 */
@Entity
@Table(name = "parametres_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ParametresInstance {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ModeInstance mode = ModeInstance.EN_LIGNE;

    /** Cote serveur local : adresse du serveur en ligne (ex. https://erp.exemple.com, http://192.0.2.10:9090). */
    @Column(name = "adresse_distante", length = 300)
    private String adresseDistante;

    /** Cle d'appairage complete, chiffree (ChiffrementSecretService) : elle signe chaque appel entre les deux serveurs. */
    @Column(name = "cle_chiffree", length = 1000)
    private String cleChiffree;

    /** Derniers caracteres de la cle, pour l'affichage. */
    @Column(name = "cle_fin", length = 8)
    private String cleFin;

    /** Identifiant de l'appairage (contenu dans la cle) : un appel d'un autre appairage est refuse. */
    @Column(name = "appairage_id", length = 40)
    private String appairageId;

    @Column(name = "cle_creee_le")
    private Instant cleCreeeLe;

    /** Cote en ligne : nom du serveur local appaire ; cote local : rappel de l'adresse du serveur en ligne. */
    @Column(name = "serveur_associe", length = 150)
    private String serveurAssocie;

    @Column(name = "appaire_le")
    private Instant appaireLe;

    /** Cote en ligne : appairage auquel « Reprendre la main » a mis fin (le serveur local qui l'utilise s'arrete). */
    @Column(name = "appairage_revoque", length = 40)
    private String appairageRevoque;

    /** Cote en ligne : dernier appel recu du serveur local. */
    @Column(name = "dernier_contact")
    private Instant dernierContact;

    /** Derniere synchronisation reussie (cote local : envoi termine ; cote en ligne : dernier lot applique). */
    @Column(name = "derniere_synchro")
    private Instant derniereSynchro;

    @Column(name = "derniere_erreur", length = 1000)
    private String derniereErreur;

    @Column(name = "derniere_erreur_le")
    private Instant derniereErreurLe;

    /** Identifiant de connexion de l'administrateur qui a fait la derniere modification. */
    @Column(name = "modifie_par", length = 150)
    private String modifiePar;

    @Column(name = "date_maj", nullable = false)
    @Builder.Default
    private Instant dateMaj = Instant.now();
}
