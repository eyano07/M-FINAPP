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
     * gains 110, CNSS 30/78 (patronale : pensions 30, prestations familiales 39, risques professionnels 9), IPR 40,
     * ONEM 3, INPP 21,30, avance 20, prêt 10 -> net 1 010. La base cotisable (710 = H + gains imposables) n'est
     * pas le débit 6611 : les appointements restent R − logement − transport = 600.
     */
    private static BulletinPaie bulletin(boolean expatrie) {
        return BulletinPaie.builder()
            .employe(Employe.builder().nomComplet("Agent").matricule("M1").expatrie(expatrie).build())
            .mois(9).annee(2026)
            .salaireBrut(bd("1000"))
            .baseImposableInss(bd("710")).indemniteLogement(bd("300")).indemniteTransport(bd("100"))
            .primeDiplome(bd("20")).primeAnciennete(bd("20")).primeRendement(bd("10"))
            .conge(bd("20")).heuresSupplementaires(bd("30")).allocationFamiliale(bd("10"))
            .cnssOuvriere(bd("30")).cnssPatronale(bd("78"))
            .cnssPatronalePension(bd("30")).cnssPatronalePf(bd("39")).cnssPatronaleRp(bd("9"))
            .ipr(bd("40")).onem(bd("3")).inpp(bd("21.30"))
            .avanceSalaire(bd("20")).pret(bd("10")).salaireNet(bd("1010"))
            .build();
    }

    /** Bulletin calculé avant la répartition de la CNSS patronale par branche (colonnes nulles). */
    private static BulletinPaie bulletinAncien() {
        BulletinPaie b = bulletin(false);
        b.setCnssPatronalePension(null);
        b.setCnssPatronalePf(null);
        b.setCnssPatronaleRp(null);
        b.setBaseImposableInss(bd("600"));
        return b;
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
        assertThat(debits).containsOnlyKeys("6611", "6612", "6613", "6616", "6618", "6631", "6634", "6641", "6413");
        assertThat(debits.get("6611")).isEqualByComparingTo("600");         // appointements, pas la base cotisable
        assertThat(debits.get("6641")).isEqualByComparingTo("78");          // CNSS patronale seule
        assertThat(debits.get("6413")).isEqualByComparingTo("24.30");       // ONEM + INPP : taxes sur salaires
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("4313")).isEqualByComparingTo("60");         // CNSS salariale + patronale pensions
        assertThat(credits.get("4311")).isEqualByComparingTo("39");         // prestations familiales
        assertThat(credits.get("4312")).isEqualByComparingTo("9");          // risques professionnels
        assertThat(credits).doesNotContainKeys("4315", "44781", "44782");
        assertThat(credits.get("44281")).isEqualByComparingTo("3");         // ONEM sous 4428
        assertThat(credits.get("44282")).isEqualByComparingTo("21.30");     // INPP sous 4428
        assertThat(credits.get("4211")).isEqualByComparingTo("20");         // avance sans compte d'agent : repli
        assertThat(credits.get("4472")).isEqualByComparingTo("40");
        assertThat(credits.get("42211")).isEqualByComparingTo("1010");      // fiche sans compte propre : repli
        assertThat(credits.get("2728")).isEqualByComparingTo("10");         // prêt sans compte d'agent : repli
        assertThat(credits).doesNotContainKey("2762");
        assertThat(lignes).allMatch(l -> "USD".equals(l.getDevise()));
    }

    /** Audit DRH du 15/11/2026, D-04 : transport en 6634, plus en 6638. */
    @Test
    void transportEn6634() {
        Map<String, BigDecimal> debits = parCompte(service.construireLignes(bulletin(false)), true);
        assertThat(debits.get("6634")).isEqualByComparingTo("100");
        assertThat(debits).doesNotContainKey("6638");
    }

    /** Audit DRH du 15/11/2026, D-02 et D-03 : net au compte de l'agent, prêt à son compte 272…. */
    @Test
    void netEtPretAuxComptesDeLAgent() {
        BulletinPaie b = bulletin(false);
        b.getEmploye().setCompteRemuneration("422113");
        b.getEmploye().setComptePret("272891");

        List<EcritureGrandLivre> lignes = service.construireLignes(b);

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("422113")).isEqualByComparingTo("1010");
        assertThat(credits.get("272891")).isEqualByComparingTo("10");
        assertThat(credits).doesNotContainKeys("42211", "2728", "2762");
    }

    @Test
    void lignesGroupeesUnCompteDeNetParAgent() {
        BulletinPaie a = bulletin(false);
        a.getEmploye().setCompteRemuneration("422111");
        BulletinPaie b = bulletin(false);
        b.getEmploye().setCompteRemuneration("422112");

        List<EcritureGrandLivre> lignes = service.construireLignesGroupees(List.of(a, b, bulletin(true)), "Paie 09/2026");

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("422111")).isEqualByComparingTo("1010");
        assertThat(credits.get("422112")).isEqualByComparingTo("1010");
        assertThat(credits.get("42211")).isEqualByComparingTo("1010");
        assertThat(credits.get("2728")).isEqualByComparingTo("30");
    }

    /** Audit DRH du 15/11/2026, D-08 : un bulletin sans parts par branche reste sur 4315 pour le total. */
    @Test
    void ancienBulletinSansPartsParBrancheResteSur4315() {
        List<EcritureGrandLivre> lignes = service.construireLignes(bulletinAncien());

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("4315")).isEqualByComparingTo("108");
        assertThat(credits).doesNotContainKeys("4311", "4312", "4313");
    }

    /** Audit DRH du 15/11/2026, D-06 : la retenue d'avance crédite le compte d'avances de l'agent. */
    @Test
    void avanceCrediteeAuCompteDeLAgent() {
        BulletinPaie b = bulletin(false);
        b.getEmploye().setCompteAvance("42113");

        List<EcritureGrandLivre> lignes = service.construireLignes(b);

        assertThat(total(lignes, true)).isEqualByComparingTo(total(lignes, false));
        Map<String, BigDecimal> credits = parCompte(lignes, false);
        assertThat(credits.get("42113")).isEqualByComparingTo("20");
        assertThat(credits).doesNotContainKey("4211");
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
        assertThat(credits.get("42211")).isEqualByComparingTo("3030");
        assertThat(parCompte(lignes, true).get("6611")).isEqualByComparingTo("1200");
        assertThat(lignes).extracting(l -> l.getCompte().getNumero() + (l.getDebit().signum() > 0 ? "D" : "C"))
            .doesNotHaveDuplicates();
    }
}
