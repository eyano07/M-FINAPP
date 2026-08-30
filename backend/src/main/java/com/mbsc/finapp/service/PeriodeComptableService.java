package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresComptables;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ParametresComptablesRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Gestion de la date de clôture comptable (« closing date » façon QuickBooks).
 *
 * <p>Aucune écriture ne peut être créée, comptabilisée ou extournée à une date
 * antérieure ou égale à la date de clôture. Seul un ADMIN peut modifier cette
 * date, et jamais la reculer dans le passé sans en avoir le rôle.</p>
 */
@Service
@RequiredArgsConstructor
public class PeriodeComptableService {

    private static final Logger log = LoggerFactory.getLogger(PeriodeComptableService.class);

    private final ParametresComptablesRepository parametresRepository;
    private final CurrentUserProvider currentUser;

    @Transactional(readOnly = true)
    public LocalDate dateCloture() {
        return parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .map(ParametresComptables::getDateCloture)
            .orElse(null);
    }

    /**
     * Vérifie qu'une date d'opération est postérieure à la clôture.
     * @throws TransitionInvalideException si la date est clôturée.
     */
    @Transactional(readOnly = true)
    public void verifierDateOuverte(LocalDate date) {
        LocalDate cloture = dateCloture();
        if (cloture != null && date != null && !date.isAfter(cloture)) {
            throw new TransitionInvalideException(
                "Période clôturée : aucune écriture ne peut être enregistrée au "
                + date + " (clôture au " + cloture + ").");
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public LocalDate definirDateCloture(LocalDate nouvelleDate) {
        User auteur = currentUser.requireUser();
        ParametresComptables params = parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .orElseGet(() -> ParametresComptables.builder()
                .id(ParametresComptables.SINGLETON_ID)
                .build());
        params.setDateCloture(nouvelleDate);
        params.setMajPar(auteur);
        params.setMajLe(Instant.now());
        parametresRepository.save(params);
        log.info("Date de clôture comptable définie au {} par {}", nouvelleDate, auteur.getEmail());
        return nouvelleDate;
    }
}
