package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "entrepots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Entrepot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String nom;

    @Column(length = 255)
    private String localisation;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;
}
