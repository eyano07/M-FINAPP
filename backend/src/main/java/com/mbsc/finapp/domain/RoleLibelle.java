package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.RoleType;
import jakarta.persistence.*;
import lombok.*;

/**
 * Libelle d'affichage d'un role (forme uniquement : les droits restent portes
 * par {@link RoleType}). Pas de ligne = libelle par defaut.
 */
@Entity
@Table(name = "role_libelles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleLibelle {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RoleType role;

    @Column(nullable = false, length = 60)
    private String libelle;
}
