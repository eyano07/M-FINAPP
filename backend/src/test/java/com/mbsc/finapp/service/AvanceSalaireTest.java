package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.dto.drh.BulletinPaieRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Avances sur salaire reliées au bulletin (audit DRH du 15/11/2026, D-06) : l'avance ouverte d'un agent est le solde
 * débiteur de son compte d'avances, moins les avances déjà retenues sur ses bulletins non comptabilisés ; un bulletin
 * ne peut pas retenir davantage.
 */
class AvanceSalaireTest {

    private final EmployeRepository employes = mock(EmployeRepository.class);
    private final BulletinPaieRepository bulletins = mock(BulletinPaieRepository.class);
    private final EcritureGrandLivreRepository grandLivre = mock(EcritureGrandLivreRepository.class);
    private final AvanceSalaireService service = new AvanceSalaireService(employes, bulletins, grandLivre);

    private final Employe agent = Employe.builder().id(4L).matricule("MBSC-004").nomComplet("Agent Avance")
        .compteAvance("42113").actif(true).build();

    AvanceSalaireTest() {
        // 150 versés par note de frais (débit 42113), 50 déjà retenus sur un bulletin non comptabilisé.
        when(grandLivre.soldeDuCompte("42113")).thenReturn(new BigDecimal("150"));
        when(bulletins.avancesRetenuesNonComptabilisees(eq(4L), anyLong())).thenReturn(new BigDecimal("50"));
    }

    @Test
    void avanceOuverteEstLeSoldeMoinsLesRetenuesNonComptabilisees() {
        var ouverte = service.avanceOuverte(agent, null);
        assertThat(ouverte.compte()).isEqualTo("42113");
        assertThat(ouverte.solde()).isEqualByComparingTo("100");
        verify(bulletins).avancesRetenuesNonComptabilisees(4L, -1L);
    }

    @Test
    void leBulletinModifieEstExcluDesRetenuesDejaFaites() {
        service.avanceOuverte(agent, 77L);
        verify(bulletins).avancesRetenuesNonComptabilisees(4L, 77L);
    }

    @Test
    void avanceDansLaLimiteAcceptee() {
        assertThatCode(() -> service.verifier(agent, new BigDecimal("100"), null)).doesNotThrowAnyException();
        assertThatCode(() -> service.verifier(agent, BigDecimal.ZERO, null)).doesNotThrowAnyException();
    }

    @Test
    void avanceDepassantLAvanceOuverteRefusee() {
        assertThatThrownBy(() -> service.verifier(agent, new BigDecimal("120"), null))
            .isInstanceOf(TransitionInvalideException.class)
            .hasMessageContaining("120,00").hasMessageContaining("100,00").hasMessageContaining("42113");
    }

    @Test
    void soldeCrediteurOuSansCompteDonneUneAvanceOuverteNulle() {
        when(grandLivre.soldeDuCompte("42113")).thenReturn(new BigDecimal("-30"));
        assertThat(service.avanceOuverte(agent, null).solde()).isEqualByComparingTo("0");

        Employe sansCompte = Employe.builder().id(5L).nomComplet("Sans Compte").build();
        assertThat(service.avanceOuverte(sansCompte, null).compte()).isNull();
        assertThatThrownBy(() -> service.verifier(sansCompte, BigDecimal.TEN, null))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("pas de compte d'avances");
    }

    /** La création d'un bulletin dont l'avance dépasse l'avance ouverte est refusée avant tout calcul. */
    @Test
    void creationDeBulletinRefuseeSiLAvanceDepasseLAvanceOuverte() {
        BulletinPaieRepository repository = mock(BulletinPaieRepository.class);
        when(repository.findByEmployeIdAndMoisAndAnnee(4L, 9, 2026)).thenReturn(Optional.empty());
        when(repository.avancesRetenuesNonComptabilisees(eq(4L), anyLong())).thenReturn(BigDecimal.ZERO);
        when(employes.findById(4L)).thenReturn(Optional.of(agent));
        PayrollCalculationService calcul = mock(PayrollCalculationService.class);
        BulletinPaieService bulletinService = new BulletinPaieService(repository, employes, mock(UserRepository.class),
            mock(ParametresPaieService.class), calcul, mock(ConversionDeviseService.class),
            mock(CurrentUserProvider.class), mock(PaieNoteService.class),
            new AvanceSalaireService(employes, repository, grandLivre));
        BulletinPaieRequest req = new BulletinPaieRequest(4L, 9, 2026, new BigDecimal("1000"), new BigDecimal("100"),
            null, null, null, null, null, null, new BigDecimal("200"), null, null, null);

        assertThatThrownBy(() -> bulletinService.creer(req))
            .isInstanceOf(TransitionInvalideException.class).hasMessageContaining("150,00");
        verify(calcul, never()).calculer(any(), any(), any());
        verify(repository, never()).save(any());
    }
}
