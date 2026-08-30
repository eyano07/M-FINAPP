package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.TauxTva;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.admin.TauxTvaRequest;
import com.mbsc.finapp.dto.admin.TauxTvaResponse;
import com.mbsc.finapp.repository.TauxTvaRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Taux de TVA applique aux ventes.
 *
 * <p>Le taux est historise par date d'effet : la vente fige le taux en
 * vigueur a sa date, de sorte qu'un changement de taux legal ne
 * reecrit jamais les factures deja emises. Seul l'administrateur peut
 * enregistrer un nouveau taux ; la lecture est ouverte a tout
 * utilisateur authentifie, car la saisie d'une vente en a besoin.</p>
 */
@Service
@RequiredArgsConstructor
public class TauxTvaService {

    private static final Logger log = LoggerFactory.getLogger(TauxTvaService.class);

    private final TauxTvaRepository tauxTvaRepository;
    private final CurrentUserProvider currentUser;

    /** Taux en vigueur a la date donnee, ou zero si aucun taux n'a ete defini. */
    @Transactional(readOnly = true)
    public BigDecimal tauxALaDate(LocalDate date) {
        LocalDate reference = date == null ? LocalDate.now() : date;
        return tauxTvaRepository
            .findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(reference)
            .map(TauxTva::getTaux)
            .orElse(BigDecimal.ZERO);
    }

    /** Taux courant + historique complet. */
    @Transactional(readOnly = true)
    public TauxTvaResponse consulter() {
        List<TauxTva> historique = tauxTvaRepository.findAllByOrderByDateEffetDescCreatedAtDesc();
        LocalDate aujourdhui = LocalDate.now();
        TauxTva enVigueur = tauxTvaRepository
            .findFirstByDateEffetLessThanEqualOrderByDateEffetDescCreatedAtDesc(aujourdhui)
            .orElse(null);

        return new TauxTvaResponse(
            enVigueur == null ? BigDecimal.ZERO : enVigueur.getTaux(),
            enVigueur == null ? aujourdhui : enVigueur.getDateEffet(),
            historique.stream().map(TauxTvaResponse.HistoriqueEntry::from).toList());
    }

    /** Enregistre un nouveau taux (ADMIN). L'historique n'est jamais modifie. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TauxTvaResponse.HistoriqueEntry enregistrer(TauxTvaRequest req) {
        User auteur = currentUser.requireUser();
        TauxTva taux = tauxTvaRepository.save(TauxTva.builder()
            .taux(req.taux())
            .dateEffet(req.dateEffet())
            .note(req.note())
            .createdBy(auteur)
            .build());

        log.info("Taux de TVA enregistre a {} % a compter du {} par {}",
            req.taux(), req.dateEffet(), auteur.getEmail());
        return TauxTvaResponse.HistoriqueEntry.from(taux);
    }
}
