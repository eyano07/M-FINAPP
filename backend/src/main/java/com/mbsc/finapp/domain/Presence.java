package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutPresence;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "drh_presences",
    uniqueConstraints = @UniqueConstraint(name = "uq_drh_presence_employe_date", columnNames = {"employe_id", "date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Presence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employe_id", nullable = false)
    @ToString.Exclude
    private Employe employe;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutPresence statut = StatutPresence.PRESENT;

    @Column(length = 200)
    private String motif;
}
