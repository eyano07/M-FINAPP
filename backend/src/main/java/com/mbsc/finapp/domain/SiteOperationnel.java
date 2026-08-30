package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "drh_sites_operationnels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteOperationnel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String nom;

    @Column(length = 200)
    private String localisation;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;
}
