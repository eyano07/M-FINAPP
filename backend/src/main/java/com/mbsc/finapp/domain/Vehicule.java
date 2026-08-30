package com.mbsc.finapp.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "vehicules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String immatriculation;

    @Column(length = 100)
    private String marque;

    @Column(length = 100)
    private String modele;

    @Column(length = 60)
    private String type;

    @Column(name = "date_acquisition")
    private LocalDate dateAcquisition;

    @Column(nullable = false)
    @Builder.Default
    private boolean actif = true;
}
