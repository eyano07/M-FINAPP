package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.LigneReleve;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.dto.rapprochement.ExtractionReleveResponse;
import com.mbsc.finapp.repository.TauxChangeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Rapprochement bancaire : choix du pointage automatique, montants en devise du compte, lecture de l'IA. */
class RapprochementServiceTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static LigneReleve ligne(String date, String sortie, String reference) {
        return LigneReleve.builder().dateOperation(LocalDate.parse(date)).libelle("Op").reference(reference)
            .entree(BigDecimal.ZERO).sortie(bd(sortie)).build();
    }

    private static EcritureGrandLivre ecriture(long id, String date, String credit, String libelle) {
        return EcritureGrandLivre.builder().id(id).dateEcriture(LocalDate.parse(date)).debit(BigDecimal.ZERO)
            .credit(bd(credit)).libelle(libelle).piece(PieceComptable.builder().reference("PC-" + id).build()).build();
    }

    @Test
    void candidateUniqueRetenue() {
        EcritureGrandLivre e = ecriture(1, "2026-09-10", "100", "Paiement");
        assertThat(RapprochementService.choisir(ligne("2026-09-12", "100", null), List.of(e))).isSameAs(e);
    }

    @Test
    void laReferenceDepartageDeuxCandidates() {
        EcritureGrandLivre a = ecriture(1, "2026-09-10", "100", "Chèque 0001234 fournisseur");
        EcritureGrandLivre b = ecriture(2, "2026-09-10", "100", "Chèque 0005678 loyer");
        assertThat(RapprochementService.choisir(ligne("2026-09-11", "100", "0005678"), List.of(a, b))).isSameAs(b);
    }

    @Test
    void laDateLaPlusProcheDepartageSinonRienNestPointe() {
        EcritureGrandLivre a = ecriture(1, "2026-09-05", "100", "A");
        EcritureGrandLivre b = ecriture(2, "2026-09-09", "100", "B");
        assertThat(RapprochementService.choisir(ligne("2026-09-10", "100", null), List.of(a, b))).isSameAs(b);
        EcritureGrandLivre c = ecriture(3, "2026-09-11", "100", "C");
        assertThat(RapprochementService.choisir(ligne("2026-09-10", "100", null), List.of(b, c))).isNull();
    }

    @Test
    void montantsDUnCompteEnCdf() {
        ConversionDeviseService conversion = new ConversionDeviseService(mock(TauxChangeRepository.class));
        RapprochementService service = new RapprochementService(null, null, null, null, null, null, conversion, null);
        // Opération saisie en CDF : le montant en francs tracé sur l'écriture fait foi.
        EcritureGrandLivre enCdf = EcritureGrandLivre.builder().debit(BigDecimal.ZERO).credit(bd("35.09"))
            .devise("CDF").montantDevise(bd("100000")).tauxApplique(bd("2850")).build();
        assertThat(service.montantCompte(enCdf, Devise.CDF)).isEqualByComparingTo("-100000");
        // Opération en USD sur un compte en CDF : convertie au taux de l'écriture.
        EcritureGrandLivre enUsd = EcritureGrandLivre.builder().debit(bd("10")).credit(BigDecimal.ZERO)
            .devise("USD").tauxApplique(bd("2850")).build();
        assertThat(service.montantCompte(enUsd, Devise.CDF)).isEqualByComparingTo("28500");
        assertThat(service.montantCompte(enUsd, Devise.USD)).isEqualByComparingTo("10");
    }

    @Test
    void lectureDeLaReponseDeLIaEtControleDesSoldes() {
        ExtractionReleveIaService extraction = new ExtractionReleveIaService(null, new ObjectMapper());
        String json = """
            {"devise":"USD","periodeDebut":"2026-09-01","periodeFin":"2026-09-30","soldeOuverture":1000,
             "soldeCloture":1180,"lignes":[
               {"date":"2026-09-03","libelle":"Virement reçu client X","reference":"VIR123","entree":250,"sortie":0},
               {"date":"2026-09-30","libelle":"Frais tenue de compte","reference":null,"entree":0,"sortie":"50,00"},
               {"date":"","libelle":"Report","reference":null,"entree":0,"sortie":0}]}
            """;
        ExtractionReleveResponse r = extraction.interpreter(json, "releve.pdf", "USD");
        assertThat(r.lignes()).hasSize(2);
        assertThat(r.lignes().get(1).sortie()).isEqualByComparingTo("50");
        assertThat(r.avertissements()).anyMatch(a -> a.contains("ignorées"));
        // 1000 + 250 − 50 = 1200 ≠ 1180 : signalé.
        assertThat(r.avertissements()).anyMatch(a -> a.contains("-20") || a.contains("20.00"));
    }
}
