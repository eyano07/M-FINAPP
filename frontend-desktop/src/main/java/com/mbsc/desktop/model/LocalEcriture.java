package com.mbsc.desktop.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ecriture du grand livre rattachee a une transaction locale. */
@Entity
@Table(name = "ecriture_grand_livre")
public class LocalEcriture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private LocalTransaction transaction;

    @Column(nullable = false, length = 20)
    private String numeroCompte;

    @Column(length = 200)
    private String libelle;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate dateEcriture = LocalDate.now();

    public LocalEcriture() {}

    public LocalEcriture(String numeroCompte, String libelle, BigDecimal debit, BigDecimal credit) {
        this.numeroCompte = numeroCompte;
        this.libelle = libelle;
        this.debit = debit;
        this.credit = credit;
    }

    public Long getId() { return id; }
    public LocalTransaction getTransaction() { return transaction; }
    public void setTransaction(LocalTransaction transaction) { this.transaction = transaction; }
    public String getNumeroCompte() { return numeroCompte; }
    public void setNumeroCompte(String numeroCompte) { this.numeroCompte = numeroCompte; }
    public String getLibelle() { return libelle; }
    public void setLibelle(String libelle) { this.libelle = libelle; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public LocalDate getDateEcriture() { return dateEcriture; }
    public void setDateEcriture(LocalDate dateEcriture) { this.dateEcriture = dateEcriture; }
}
