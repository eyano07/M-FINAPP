package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.NiveauPermission;
import com.mbsc.finapp.domain.enums.RoleType;
import jakarta.persistence.*;
import lombok.*;

/**
 * Niveau d'acces d'un role a un module. L'absence de ligne pour un couple
 * (role, module) vaut {@link NiveauPermission#AUCUN} (refus par defaut) :
 * voir {@code RolePermissionRepository}/{@code PermissionService}.
 */
@Entity
@Table(name = "role_permissions", uniqueConstraints = @UniqueConstraint(columnNames = {"role", "module"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoleType role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ModuleMetier module;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NiveauPermission niveau = NiveauPermission.AUCUN;
}
