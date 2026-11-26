package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Employe;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EmployeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Avances sur salaire reliées au bulletin (audit DRH du 15/11/2026, D-06).
 *
 * <p>Une avance se verse par une note de frais dont la ligne est imputée au compte d'avances de l'agent (4211x,
 * ouvert à la création de sa fiche) : son paiement débite ce compte. La retenue de l'avance sur un bulletin le
 * crédite à la constatation de la paie ({@code PaieComptabilisationService}). L'<b>avance ouverte</b> d'un agent
 * est donc le solde débiteur de son compte d'avances au grand livre, diminué des avances déjà retenues sur ses
 * bulletins pas encore comptabilisés : un bulletin ne peut pas retenir plus que cela.</p>
 */
@Service
@RequiredArgsConstructor
public class AvanceSalaireService {

    private final EmployeRepository employeRepository;
    private final BulletinPaieRepository bulletinRepository;
    private final EcritureGrandLivreRepository grandLivre;

    /** Avance ouverte d'un agent : son compte d'avances et le montant encore à retenir (jamais négatif). */
    public record AvanceOuverte(String compte, BigDecimal solde) {}

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional(readOnly = true)
    public AvanceOuverte consulter(Long employeId) {
        Employe e = employeRepository.findById(employeId)
            .orElseThrow(() -> RessourceIntrouvableException.of("Employe", employeId));
        return avanceOuverte(e, null);
    }

    /**
     * Avance ouverte de l'agent, hors le bulletin {@code bulletinExclu} (celui qu'on modifie ; nul à la création).
     */
    @Transactional(readOnly = true)
    public AvanceOuverte avanceOuverte(Employe e, Long bulletinExclu) {
        String compte = e.getCompteAvance();
        if (compte == null || compte.isBlank()) {
            return new AvanceOuverte(null, BigDecimal.ZERO);
        }
        BigDecimal solde = nz(grandLivre.soldeDuCompte(compte));
        BigDecimal dejaRetenues = nz(bulletinRepository.avancesRetenuesNonComptabilisees(e.getId(),
            bulletinExclu != null ? bulletinExclu : -1L));
        BigDecimal ouverte = solde.subtract(dejaRetenues).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new AvanceOuverte(compte, ouverte);
    }

    /** Refuse une retenue d'avance supérieure à l'avance ouverte de l'agent. */
    public void verifier(Employe e, BigDecimal avance, Long bulletinExclu) {
        if (avance == null || avance.signum() <= 0) {
            return;
        }
        AvanceOuverte ouverte = avanceOuverte(e, bulletinExclu);
        if (avance.compareTo(ouverte.solde()) <= 0) {
            return;
        }
        if (ouverte.compte() == null) {
            throw new TransitionInvalideException(e.getNomComplet() + " n'a pas de compte d'avances : enregistrez "
                + "sa fiche pour en ouvrir un, puis versez l'avance par une note de frais imputée à ce compte, avant de "
                + "la retenir sur son bulletin.");
        }
        throw new TransitionInvalideException(String.format(Locale.FRENCH,
            "Avance retenue (%.2f USD) supérieure à l'avance ouverte de %s (%.2f USD au compte %s, déduction faite "
                + "des avances déjà retenues sur ses bulletins non comptabilisés). Une avance se verse d'abord par une "
                + "note de frais imputée au compte %s ; seule une avance versée peut être retenue.",
            avance, e.getNomComplet(), ouverte.solde(), ouverte.compte(), ouverte.compte()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
