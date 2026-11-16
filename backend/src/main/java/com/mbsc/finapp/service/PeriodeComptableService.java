package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresComptables;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.ParametresComptablesRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Verrou de période (« closing date ») et date de clôture définitive de l'exercice.
 *
 * <p>Aucune écriture ne peut être créée, comptabilisée ou extournée à une date antérieure ou égale au verrou. Le
 * verrou n'est jamais déplacé directement par un écran : {@link ClotureGouvernanceService} l'avance (DFIN), le recule
 * après double validation (DFIN puis DA ou DG), et la clôture d'exercice le pose (audit SYSCOHADA du 10/11/2026,
 * C-03). Il ne peut jamais redescendre en dessous de la dernière clôture d'exercice. Chaque changement est inscrit à
 * {@code historique_clotures}, en ajout seul.</p>
 */
@Service
@RequiredArgsConstructor
public class PeriodeComptableService {

    private static final Logger log = LoggerFactory.getLogger(PeriodeComptableService.class);

    private final ParametresComptablesRepository parametresRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public LocalDate dateCloture() {
        return parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .map(ParametresComptables::getDateCloture)
            .orElse(null);
    }

    /** Date de la dernière clôture définitive d'exercice (null : aucune). */
    @Transactional(readOnly = true)
    public LocalDate dateClotureExercice() {
        return parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .map(ParametresComptables::getDateClotureExercice)
            .orElse(null);
    }

    /** true si l'auteur d'une pièce peut la comptabiliser lui-même (dérogation C-06). */
    @Transactional(readOnly = true)
    public boolean autoValidationAutorisee() {
        return parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .map(ParametresComptables::isAutoValidationAutorisee)
            .orElse(false);
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

    /**
     * Déplace le verrou (appelé par {@link ClotureGouvernanceService} et la clôture d'exercice, jamais par un écran).
     * Refuse de descendre sous la dernière clôture d'exercice ; inscrit le changement à l'historique.
     */
    @Transactional
    public LocalDate appliquerVerrou(LocalDate nouvelleDate, String typeCloture, String motif, User auteur) {
        ParametresComptables params = parametres();
        LocalDate exercice = params.getDateClotureExercice();
        if (exercice != null && (nouvelleDate == null || nouvelleDate.isBefore(exercice))) {
            throw new TransitionInvalideException("L'exercice est clôturé définitivement au " + exercice
                + " : le verrou de période ne peut pas redescendre en dessous.");
        }
        LocalDate ancienne = params.getDateCloture();
        params.setDateCloture(nouvelleDate);
        params.setMajPar(auteur);
        params.setMajLe(Instant.now());
        parametresRepository.save(params);
        historiser(typeCloture, ancienne, nouvelleDate, motif, auteur);
        log.info("Verrou de période {} -> {} par {} ({})", ancienne, nouvelleDate, auteur.getEmail(), typeCloture);
        return nouvelleDate;
    }

    /** Pose la clôture définitive d'exercice et le verrou à la même date. */
    @Transactional
    public void cloturerDefinitivement(LocalDate dateExercice, String motif, User auteur) {
        ParametresComptables params = parametres();
        params.setDateClotureExercice(dateExercice);
        parametresRepository.save(params);
        LocalDate verrou = params.getDateCloture();
        if (verrou == null || verrou.isBefore(dateExercice)) {
            appliquerVerrou(dateExercice, "EXERCICE", motif, auteur);
        } else {
            historiser("EXERCICE", verrou, verrou, motif, auteur);
        }
    }

    /** Pose ou retire la dérogation à la validation par une autre personne, en l'inscrivant à l'historique. */
    @Transactional
    public void definirAutoValidation(boolean autorisee, String motif, User auteur) {
        ParametresComptables params = parametres();
        params.setAutoValidationAutorisee(autorisee);
        params.setMajPar(auteur);
        params.setMajLe(Instant.now());
        parametresRepository.save(params);
        historiser("PARAMETRE", null, null,
            (autorisee ? "Auto-validation autorisée" : "Auto-validation retirée") + " : " + motif, auteur);
    }

    private ParametresComptables parametres() {
        return parametresRepository.findById(ParametresComptables.SINGLETON_ID)
            .orElseGet(() -> ParametresComptables.builder().id(ParametresComptables.SINGLETON_ID).build());
    }

    private void historiser(String type, LocalDate ancienne, LocalDate nouvelle, String motif, User auteur) {
        jdbc.update("""
            insert into historique_clotures (type_cloture, ancienne_date, nouvelle_date, motif, auteur_id, auteur)
            values (?, ?, ?, ?, ?, ?)
            """, type, ancienne, nouvelle, motif, auteur.getId(), auteur.getEmail());
    }
}
