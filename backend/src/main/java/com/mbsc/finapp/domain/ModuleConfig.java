package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.ModuleMetier;
import jakarta.persistence.*;
import lombok.*;

/**
 * Etat actif/inactif d'un {@link ModuleMetier}, controle par l'administrateur.
 *
 * <p>{@code parentModule} porte la hierarchie eventuelle (voir DRH dans
 * {@link ModuleMetier}) : null pour un module autonome, sinon le module
 * conteneur dont l'etat actif/inactif prevaut en cascade sur celui-ci
 * (voir {@code PermissionService#niveauEffectif}). Stocke comme simple
 * colonne enum, pas de contrainte FK — la table est deja cle par l'enum
 * lui-meme, une FK vers sa propre PK via une autre colonne n'apporte rien
 * que Java ne verifie pas deja a la compilation.</p>
 */
@Entity
@Table(name = "modules_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModuleConfig {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ModuleMetier module;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "parent_module", length = 30)
    private ModuleMetier parentModule;
}
