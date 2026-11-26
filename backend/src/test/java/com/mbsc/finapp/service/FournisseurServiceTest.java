package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Fournisseur;
import com.mbsc.finapp.dto.fournisseur.FournisseurRequest;
import com.mbsc.finapp.dto.fournisseur.FournisseurResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.FournisseurRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static com.mbsc.finapp.service.GardesParRole.autorise;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Répertoire des fournisseurs (V141, V142) : création, compte propre 4011…, archivage plutôt que suppression, rôles. */
class FournisseurServiceTest {

    private FournisseurRepository repository;
    private CompteOHADARepository comptes;
    private EcritureGrandLivreRepository ecritures;
    private FournisseurService service;
    /** Numéros présents au plan comptable simulé. */
    private final Set<String> plan = new HashSet<>(Set.of("4011", "40111", "40112"));

    @BeforeEach
    void preparer() {
        repository = mock(FournisseurRepository.class);
        comptes = mock(CompteOHADARepository.class);
        ecritures = mock(EcritureGrandLivreRepository.class);
        service = new FournisseurService(repository, comptes, ecritures, mock(DocumentationCompteService.class));
        when(repository.save(any())).thenAnswer(i -> {
            Fournisseur f = i.getArgument(0);
            f.setId(7L);
            return f;
        });
        when(comptes.existsByNumero(anyString())).thenAnswer(i -> plan.contains(i.<String>getArgument(0)));
        when(comptes.findByNumero(anyString())).thenAnswer(i -> plan.contains(i.<String>getArgument(0))
            ? Optional.of(compte(i.getArgument(0), true)) : Optional.empty());
        when(comptes.save(any())).thenAnswer(i -> {
            CompteOHADA c = i.getArgument(0);
            plan.add(c.getNumero());
            return c;
        });
    }

    private static CompteOHADA compte(String numero, boolean imputable) {
        CompteOHADA c = new CompteOHADA();
        c.setNumero(numero);
        c.setImputable(imputable);
        c.setActif(true);
        return c;
    }

    private static FournisseurRequest demande(String nom) {
        return new FournisseurRequest(nom, "  Jean Kabila ", "+243 81 000 0000", "ventes@bracongo.cd",
            "Av. du Port, Kinshasa", " a1234567b ", "cd/kin/rccm/14-b-0001");
    }

    @Test
    void creerUnFournisseurLuiDonneUnCodeEtSonPropreCompteSous4011() {
        FournisseurResponse r = service.creer(demande("  Bracongo SA "));

        assertThat(r.code()).isEqualTo("FRN-0007");
        assertThat(r.nom()).isEqualTo("Bracongo SA");
        assertThat(r.contact()).isEqualTo("Jean Kabila");
        assertThat(r.nif()).isEqualTo("A1234567B");
        assertThat(r.rccm()).isEqualTo("CD/KIN/RCCM/14-B-0001");
        // 40111 et 40112 existent : premier numéro libre, le parent suivi du rang, sans point ni zéro.
        assertThat(r.compteComptable()).isEqualTo("40113");
        verify(comptes).save(org.mockito.ArgumentMatchers.argThat(c ->
            c.getNumero().equals("40113") && c.getLibelle().equals("Fournisseurs : Bracongo SA")
            && c.getParent() != null && c.getParent().getNumero().equals("4011") && c.isImputable()));
        assertThat(r.actif()).isTrue();
        assertThat(r.nombreAchats()).isZero();
    }

    @Test
    void seulLeNomEstObligatoire() {
        FournisseurResponse r = service.creer(new FournisseurRequest("Brasimba", null, " ", null, null, null, null));
        assertThat(r.nom()).isEqualTo("Brasimba");
        assertThat(r.telephone()).isNull();
        assertThat(r.compteComptable()).isEqualTo("40113");
    }

    @Test
    void sansLeCollectif4011AucunCompteNiFicheNeSontCrees() {
        plan.remove("4011");
        assertThatThrownBy(() -> service.creer(demande("Brasimba")))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("4011");
        verify(repository, never()).save(any());
    }

    @Test
    void deuxFournisseursNePortentPasLeMemeNom() {
        when(repository.nomDejaPris("Bracongo SA", null)).thenReturn(true);
        assertThatThrownBy(() -> service.creer(demande("Bracongo SA")))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("déjà");
    }

    @Test
    void unFournisseurQuiADesAchatsSArchiveAuLieuDeSeSupprimer() {
        Fournisseur f = Fournisseur.builder().id(3L).code("FRN-0003").nom("Bracongo SA").build();
        when(repository.findById(3L)).thenReturn(Optional.of(f));
        when(repository.compterLots("Bracongo SA")).thenReturn(2L);

        assertThatThrownBy(() -> service.supprimer(3L))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("archivez");
        verify(repository, never()).delete(any());

        FournisseurResponse archive = service.archiver(3L);
        assertThat(archive.actif()).isFalse();
        assertThat(archive.nombreAchats()).isEqualTo(2L);
        assertThatThrownBy(() -> service.modifier(3L, demande("Bracongo Kinshasa")))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("nom ne peut plus changer");
    }

    @Test
    void unFournisseurSansAchatSeSupprime() {
        CompteOHADA collectif = compte("4011", true);
        CompteOHADA propre = compte("40112", true);
        propre.setId(12L);
        propre.setParent(collectif);
        when(comptes.findByNumero("40112")).thenReturn(Optional.of(propre));
        Fournisseur f = Fournisseur.builder().id(4L).code("FRN-0004").nom("Erreur de saisie").compteComptable("40112").build();
        when(repository.findById(4L)).thenReturn(Optional.of(f));
        service.supprimer(4L);
        verify(repository).delete(f);
        verify(comptes).delete(propre);   // son compte part avec elle
    }

    @Test
    void unFournisseurDontLeCompteADesEcrituresSArchive() {
        CompteOHADA propre = compte("40112", true);
        propre.setId(12L);
        propre.setParent(compte("4011", true));
        when(comptes.findByNumero("40112")).thenReturn(Optional.of(propre));
        when(ecritures.existsByCompteId(12L)).thenReturn(true);
        Fournisseur f = Fournisseur.builder().id(4L).code("FRN-0004").nom("Payé").compteComptable("40112").build();
        when(repository.findById(4L)).thenReturn(Optional.of(f));
        assertThatThrownBy(() -> service.supprimer(4L))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("archivez");
        verify(repository, never()).delete(any());
    }

    @Test
    void lesAcheteursLaComptabiliteEtLAdministrateurTiennentLeRepertoireLeCaissierSaisitSansArchiver() {
        for (String role : new String[] {"LOGISTIQUE", "RESP_RESTAURANT", "RESP_CUISINE", "RESP_BAR", "GEST_PATRIMOINE",
            "COMPTABLE", "DFIN", "ADMIN"}) {
            for (String methode : new String[] {"lister", "creer", "modifier", "archiver", "reactiver", "supprimer"}) {
                assertThat(autorise(FournisseurService.class, methode, role)).as(role + " " + methode).isTrue();
            }
        }
        // 17/11/2026 : le caissier lit, crée et modifie les fiches des fournisseurs qu'il paie, sans archiver ni supprimer.
        for (String methode : new String[] {"lister", "creer", "modifier"}) {
            assertThat(autorise(FournisseurService.class, methode, "CAISSIER")).as("CAISSIER " + methode).isTrue();
        }
        for (String methode : new String[] {"archiver", "reactiver", "supprimer"}) {
            assertThat(autorise(FournisseurService.class, methode, "CAISSIER")).as("CAISSIER " + methode).isFalse();
        }
        assertThat(autorise(FournisseurService.class, "lister", "DA")).isTrue();
        assertThat(autorise(FournisseurService.class, "creer", "DA")).isFalse();
    }
}
