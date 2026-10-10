package com.mbsc.finapp.service.emcf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.Client;
import com.mbsc.finapp.domain.FactureNormalisee;
import com.mbsc.finapp.domain.GroupeTaxeDgi;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeReglement;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FactureNormaliseeMapperTest {

    private final FactureNormaliseeMapper mapper = new FactureNormaliseeMapper();
    private final List<GroupeTaxeDgi> groupes = List.of(
        GroupeTaxeDgi.builder().code("A").libelle("Exonéré").taux(BigDecimal.ZERO).ordre(1).build(),
        GroupeTaxeDgi.builder().code("B").libelle("Taxable").taux(new BigDecimal("16")).ordre(2).build());

    private static ParametresEntreprise entreprise(String nif) {
        return ParametresEntreprise.builder().nom("MBSC").nif(nif).build();
    }

    private static LigneVente ligne(String des, String ht, String tva, boolean soumis, String groupe) {
        return LigneVente.builder().designation(des).quantite(new BigDecimal("2")).prixUnitaire(new BigDecimal(ht).divide(new BigDecimal("2")))
            .montantHt(new BigDecimal(ht)).montantTva(new BigDecimal(tva)).soumisTva(soumis).groupeTaxe(groupe).build();
    }

    private static FactureNormalisee facture(Client client, LigneVente... lignes) {
        BigDecimal ht = BigDecimal.ZERO;
        BigDecimal tva = BigDecimal.ZERO;
        for (LigneVente l : lignes) {
            ht = ht.add(l.getMontantHt());
            tva = tva.add(l.getMontantTva());
        }
        Vente v = Vente.builder().reference("VTE-2026-000001").devise(Devise.CDF).modeReglement(ModeReglement.CAISSE)
            .client(client).clientNom("Comptoir").totalHt(ht).totalTva(tva).totalTtc(ht.add(tva))
            .lignes(new ArrayList<>(List.of(lignes))).build();
        return FactureNormalisee.builder().vente(v).type(FactureNormalisee.Type.VENTE).statut(FactureNormalisee.Statut.EN_ATTENTE).build();
    }

    @Test
    void groupeDeduitDeLaTvaSauSiFixe() {
        assertEquals("B", FactureNormaliseeMapper.groupe(ligne("x", "10", "1.6", true, null)));
        assertEquals("A", FactureNormaliseeMapper.groupe(ligne("x", "10", "0", false, null)));
        assertEquals("C", FactureNormaliseeMapper.groupe(ligne("x", "10", "0", false, " c ")));
    }

    @Test
    void totauxParGroupeEtMontants() {
        FactureNormalisee f = facture(null, ligne("Plat", "100.00", "16.00", true, null), ligne("Eau", "50.00", "0.00", false, null),
            ligne("Boisson", "40.00", "6.40", true, null));
        EmcfDemande d = mapper.construire(f, entreprise("A123"), groupes);
        assertEquals("FV", d.type());
        assertEquals(2, d.parGroupe().size());
        var b = d.parGroupe().stream().filter(g -> g.groupe().equals("B")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("140.00"), b.ht());
        assertEquals(new BigDecimal("22.40"), b.tva());
        assertEquals(new BigDecimal("162.40"), b.ttc());
        assertEquals(new BigDecimal("16"), b.taux());
        assertEquals(new BigDecimal("116.00"), d.lignes().get(0).montantTtc());
        assertEquals(new BigDecimal("190.00"), d.totalHt());
    }

    @Test
    void refuseSansNifEntrepriseOuClientAssujetti() {
        FactureNormalisee f = facture(null, ligne("Plat", "100.00", "16.00", true, null));
        ErreurEmcf e = assertThrows(ErreurEmcf.class, () -> mapper.construire(f, entreprise(" "), groupes));
        assertFalse(e.retentable());
        assertTrue(e.getMessage().contains("NIF de l'entreprise"));

        Client sansNif = Client.builder().nom("Société X").code("C1").typeClient("ENTREPRISE").build();
        FactureNormalisee f2 = facture(sansNif, ligne("Plat", "100.00", "16.00", true, null));
        assertTrue(assertThrows(ErreurEmcf.class, () -> mapper.construire(f2, entreprise("A123"), groupes)).getMessage().contains("NIF du client"));

        Client avecNif = Client.builder().nom("Société X").code("C1").typeClient("ENTREPRISE").nif("A999").build();
        assertEquals("A999", mapper.construire(facture(avecNif, ligne("Plat", "100.00", "16.00", true, null)), entreprise("A123"), groupes).client().nif());
    }

    @Test
    void groupeInconnuRefuse() {
        FactureNormalisee f = facture(null, ligne("Plat", "100.00", "0", true, "Z"));
        assertTrue(assertThrows(ErreurEmcf.class, () -> mapper.construire(f, entreprise("A123"), groupes)).getMessage().contains("« Z »"));
    }

    @Test
    void avoirPorteLUidDeLOrigine() {
        FactureNormalisee origine = FactureNormalisee.builder().uid("UID-1").build();
        FactureNormalisee f = facture(null, ligne("Plat", "100.00", "16.00", true, null));
        f.setType(FactureNormalisee.Type.AVOIR);
        f.setOrigine(origine);
        EmcfDemande d = mapper.construire(f, entreprise("A123"), groupes);
        assertEquals("FA", d.type());
        assertEquals("UID-1", d.origineUid());
    }

    @Test
    void jsonAllerRetourEtReponseIncomplete() {
        ObjectMapper om = new ObjectMapper();
        EmcfDemande d = mapper.construire(facture(null, ligne("Plat", "100.00", "16.00", true, null)), entreprise("A123"), groupes);
        String json = EmcfJson.versJson(om, d);
        assertTrue(json.contains("\"type\":\"FV\"") && json.contains("\"groupe\":\"B\""));

        EmcfReponse r = EmcfJson.depuisJson(om, "{\"id\":\"U1\",\"securityCode\":\"SIG\",\"def\":\"D1\",\"qrCode\":\"QR\",\"dateTime\":\"2026-03-01T10:00:00Z\"}");
        assertEquals("U1", r.uid());
        assertEquals("SIG", r.signature());
        assertEquals("D1", r.numeroDef());
        assertEquals("QR", r.codeQr());
        assertEquals("2026-03-01T10:00:00Z", r.dateFiscale().toString());

        ErreurEmcf e = assertThrows(ErreurEmcf.class, () -> EmcfJson.depuisJson(om, "{\"uid\":\"U1\"}"));
        assertFalse(e.retentable());
        assertThrows(ErreurEmcf.class, () -> EmcfJson.depuisJson(om, "pas du json"));
    }

    @Test
    void delaiDeRelanceCroissantPlafonne() {
        assertEquals(Duration.ofMinutes(1), FactureNormaliseeService.delai(1));
        assertEquals(Duration.ofMinutes(2), FactureNormaliseeService.delai(2));
        assertEquals(Duration.ofMinutes(8), FactureNormaliseeService.delai(4));
        assertEquals(Duration.ofMinutes(60), FactureNormaliseeService.delai(30));
    }

    @Test
    void simulateurProduitUneReponseSignee() {
        EmcfDemande d = mapper.construire(facture(null, ligne("Plat", "100.00", "16.00", true, null)), entreprise("A123"), groupes);
        EmcfReponse r = new EmcfSimulateur().certifier(new ConfigEmcf(true, "SIMULATION", null, null, null, 1000), d, "{}");
        assertTrue(r.uid().startsWith("SIM-"));
        assertNotNull(r.signature());
        assertTrue(r.codeQr().contains("SIMULATION"));
    }
}
