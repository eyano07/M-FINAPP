package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.repository.CompteOHADARepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Constatation de la paie (SYSCOHADA) : équilibre, comptes et regroupement par compte. */
class PaieComptabilisationServiceTest {

    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final PaieComptabilisationService service = new PaieComptabilisationService(comptes);

    PaieComptabilisationServiceTest() {
        when(comptes.findByNumero(anyString()))
            .thenAnswer(i -> Optional.of(CompteOHADA.builder().numero(i.getArgument(0)).build()));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /**
     * Bulletin cohérent avec la formule de calcul : R = 1 000 (H 600 + logement 300 + transport 100),
     * gains 110, CNSS 30/78, IPR 40, ONEM 3, INPP 21,30, avance 20, prêt 10 -> net 1 010.
     */
    private static BulletinPaie bulletin(boolean expatrie) {
        return BulletinPaie.builder()
            .employe(Employe.builder().nomComplet("Agent").matricule("M1").expatrie(expatrie).build())
            .mois(9).annee(2026)
            .baseImposableInss(bd("600")).indemniteLogement(bd("300")).indemniteTransport(bd("100"))
            .primeDiplome(bd("20")).primeAnciennete(bd("20")).primeRendement(bd("10"))
            .conge(bd("20")).heuresSupplementaires(bd("30")).allocationFamiliale(bd("10"))
            .cnssOuvriere(bd("30")).cnssPatronale(bd("78")).ipr(bd("40")).onem(bd("3")).inpp(bd("21.30"))
            .avanceSalaire(bd("20")).pret(bd("10")).salaireNet(bd("1010"))
            .build();
    }

    private static Map<String, BigDecimal> parCompte(List<EcritureGrandLivre> lignes, boolean debit) {
        return lignes.stream()
            .filter(l -> (debit ? l.getDebit() : l.getCredit()).signum() > 0)
            .collect(Collectors.toMap(l -> l.getCompte().getNumero(), l -> debit ? l.getDebit() : l.getCredit()));
    }

    private static BigDecimal total(List<EcritureGrandLivre> lignes, boolean debit) {
        return lignes.stream().map(l -> debit ? l.getDebit() : l.getCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void pieceEquilibreeAvecLesComptesSyscohada() {
        List<EcritureGrandLivre> lignes = service.construireLignes(bulletin(false));

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> debits = parCompte(lignes, true);
        assertThat(debits).containsOnlyKeys("6611", "6612", "6613", "6616", "6618", "6631", "6638", "6641", "6413");
        assertThat(debits.get("6641")).isEqualByComparingTo("78");          // CNSS patronale seule
        assertThat(debits.get("6413")).isEqualByComparingTo("24.30");       // ONEM + INPP : taxes sur salaires
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("431.1")).isEqualByComparingTo("108");
        assertThat(credits.get("4472")).isEqualByComparingTo("40");
        assertThat(credits.get("4221.1")).isEqualByComparingTo("1010");
        assertThat(lignes).allMatch(l -> "USD".equals(l.getDevise()));
    }

    @Test
    void expatrieImputeEn662() {
        Map<String, BigDecimal> debits = parCompte(service.construireLignes(bulletin(true)), true);
        assertThat(debits).containsKeys("6621", "6622", "6623", "6626", "6628", "6642");
        assertThat(debits).doesNotContainKeys("6611", "6612", "6613", "6616", "6618", "6641");
    }

    @Test
    void lignesGroupeesCumuleesParCompte() {
        List<EcritureGrandLivre> lignes = service.construireLignesGroupees(
            List.of(bulletin(false), bulletin(false), bulletin(true)), "Paie 09/2026");

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("4221.1")).isEqualByComparingTo("3030");
        assertThat(parCompte(lignes, true).get("6611")).isEqualByComparingTo("1200");
        assertThat(lignes).extracting(l -> l.getCompte().getNumero() + (l.getDebit().signum() > 0 ? "D" : "C"))
            .doesNotHaveDuplicates();
    }
}
