package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.etablissement.EtablissementRequest;
import com.mbsc.finapp.dto.etablissement.EtablissementResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EtablissementTresorerieRepository;
import com.mbsc.finapp.repository.ReleveBancaireRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Banques et operateurs mobile money crees ou repris a partir des comptes du grand livre. */
class EtablissementTresorerieServiceTest {

    private final EtablissementTresorerieRepository etablissements = mock(EtablissementTresorerieRepository.class);
    private final CompteOHADARepository comptes = mock(CompteOHADARepository.class);
    private final EcritureGrandLivreRepository ecritures = mock(EcritureGrandLivreRepository.class);
    private final EtablissementTresorerieService service = new EtablissementTresorerieService(
        etablissements, mock(ReleveBancaireRepository.class), comptes, mock(DocumentationCompteService.class), ecritures);

    private static CompteOHADA compte(long id, String numero, String libelle) {
        return CompteOHADA.builder().id(id).numero(numero).libelle(libelle)
            .classe(Integer.valueOf(numero.substring(0, 1))).imputable(true).actif(true).build();
    }

    @Test
    void reconnaitLesComptesDeBanqueEtDeMobileMoney() {
        assertThat(EtablissementTresorerieService.typePourCompte(compte(1, "5215", "Banque — Equity Bank"))).isEqualTo(TypeEtablissement.BANQUE);
        assertThat(EtablissementTresorerieService.typePourCompte(compte(2, "5211", "Banques en monnaie nationale"))).isEqualTo(TypeEtablissement.BANQUE);
        assertThat(EtablissementTresorerieService.typePourCompte(compte(3, "5222", "Banque — Rawbank"))).isEqualTo(TypeEtablissement.BANQUE);
        assertThat(EtablissementTresorerieService.typePourCompte(compte(4, "5521", "Airtel Money"))).isEqualTo(TypeEtablissement.MOBILE_MONEY);
        assertThat(EtablissementTresorerieService.typePourCompte(compte(5, "5530", "Mobile Money — Orange"))).isEqualTo(TypeEtablissement.MOBILE_MONEY);
        // Ni la caisse, ni les virements internes, ni les interets courus, ni un fournisseur.
        assertThat(EtablissementTresorerieService.typePourCompte(compte(6, "571", "Caisse siège social"))).isNull();
        assertThat(EtablissementTresorerieService.typePourCompte(compte(7, "585", "Virements de fonds"))).isNull();
        assertThat(EtablissementTresorerieService.typePourCompte(compte(8, "5261", "Banques, intérêts courus"))).isNull();
        assertThat(EtablissementTresorerieService.typePourCompte(compte(9, "4011", "Fournisseurs"))).isNull();
        assertThat(EtablissementTresorerieService.typePourCompte(null)).isNull();
    }

    @Test
    void tireLeNomDeLIntituleDuCompte() {
        assertThat(EtablissementTresorerieService.nomDepuisLibelle(compte(1, "5215", "Banque — Equity Bank"))).isEqualTo("Equity Bank");
        assertThat(EtablissementTresorerieService.nomDepuisLibelle(compte(2, "5216", "BANQUE - Rawbank"))).isEqualTo("Rawbank");
        assertThat(EtablissementTresorerieService.nomDepuisLibelle(compte(3, "5521", "Mobile Money — Airtel Money"))).isEqualTo("Airtel Money");
        assertThat(EtablissementTresorerieService.nomDepuisLibelle(compte(4, "5215", "Banques en devises"))).isEqualTo("Banques en devises");
        assertThat(EtablissementTresorerieService.nomDepuisLibelle(compte(5, "5217", "  "))).isEqualTo("Compte 5217");
    }

    @Test
    void creeLaBanqueDUnCompteImporteSansRienSaisir() {
        when(comptes.findByNumero("5215")).thenReturn(Optional.of(compte(1, "5215", "Banque — Equity Bank")));
        when(comptes.findByNumero("571")).thenReturn(Optional.of(compte(2, "571", "Caisse siège social")));
        when(comptes.findByNumero("4011")).thenReturn(Optional.of(compte(3, "4011", "Fournisseurs")));
        when(comptes.findByNumero("5216")).thenReturn(Optional.of(compte(4, "5216", "Banque — Ecobank")));
        when(etablissements.existsByCompteId(4L)).thenReturn(true);      // Ecobank existe deja

        // Simulation : rien n'est ecrit, le compte rendu annonce la creation.
        List<String> annonce = service.rattacherComptesOrphelins(List.of("5215", "571", "4011", "5216"), true);
        assertThat(annonce).containsExactly("Banque « Equity Bank » — compte 5215");
        verify(etablissements, never()).save(any());

        // Import reel : la banque est creee sur le compte existant, en USD, active.
        List<String> crees = service.rattacherComptesOrphelins(List.of("5215", "571", "4011", "5216"), false);
        assertThat(crees).containsExactly("Banque « Equity Bank » — compte 5215");
        ArgumentCaptor<EtablissementTresorerie> cree = ArgumentCaptor.forClass(EtablissementTresorerie.class);
        verify(etablissements).save(cree.capture());
        assertThat(cree.getValue().getNom()).isEqualTo("Equity Bank");
        assertThat(cree.getValue().getType()).isEqualTo(TypeEtablissement.BANQUE);
        assertThat(cree.getValue().getCompte().getNumero()).isEqualTo("5215");
        assertThat(cree.getValue().getDevise()).isEqualTo(Devise.USD);
        assertThat(cree.getValue().isActif()).isTrue();
    }

    @Test
    void distingueParLeNumeroUnNomDejaPris() {
        when(comptes.findByNumero("5215")).thenReturn(Optional.of(compte(1, "5215", "Banque — Equity Bank")));
        when(etablissements.existsByNomIgnoreCaseAndType("Equity Bank", TypeEtablissement.BANQUE)).thenReturn(true);

        assertThat(service.rattacherComptesOrphelins(List.of("5215"), true))
            .containsExactly("Banque « Equity Bank (5215) » — compte 5215");
    }

    @Test
    void ajouterUneBanqueReprendLeCompteDejaOuvertASonNom() {
        CompteOHADA existant = compte(1, "5215", "Banque — Equity Bank");
        when(comptes.findByLibelleIgnoreCaseOrderByNumeroAsc("Banque — Equity Bank")).thenReturn(List.of(existant));
        when(etablissements.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ecritures.soldePourCompte("5215")).thenReturn(new BigDecimal("14500.00"));

        // Aucun nouveau compte n'est ouvert : le gestionnaire d'entites (sequence de comptes) n'est pas sollicite.
        EtablissementResponse r = service.creer(new EtablissementRequest("Equity Bank", TypeEtablissement.BANQUE, null));

        assertThat(r.compteNumero()).isEqualTo("5215");
        assertThat(r.solde()).isEqualByComparingTo("14500.00");
        verify(comptes, never()).existsByNumero(anyString());
        verify(etablissements).save(any());
        verify(ecritures).soldePourCompte(eq("5215"));
    }
}
