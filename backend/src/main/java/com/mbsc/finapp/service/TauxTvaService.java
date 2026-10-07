package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.TauxTva;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.dto.admin.TauxTvaRequest;
import com.mbsc.finapp.dto.admin.TauxTvaResponse;
import com.mbsc.finapp.repository.ParametresEntrepriseRepository;
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
    /** Régime de TVA de l'entreprise (lecture) — voir {@link #estAssujetti()}. */
    private final ParametresEntrepriseRepository parametresRepository;
    /** Création de la ligne des paramètres si elle manque (écriture du régime). */
    private final ParametresEntrepriseService parametresEntreprise;

    /**
     * Taux appliqué aux opérations à la date donnée : zéro si l'entreprise n'est pas
     * assujettie à la TVA, sinon le taux légal en vigueur à cette date (zéro si aucun
     * n'a été défini). Tout calcul de TVA de l'application passe par ici (ventes, notes
     * de frais, achats au comptant, minerais) : le régime choisi par l'administrateur
     * s'applique donc partout d'un coup.
     */
    @Transactional(readOnly = true)
    public BigDecimal tauxALaDate(LocalDate date) {
        if (!estAssujetti()) {
            return BigDecimal.ZERO;
        }
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

        BigDecimal tauxLegal = enVigueur == null ? BigDecimal.ZERO : enVigueur.getTaux();
        boolean assujetti = estAssujetti();
        return new TauxTvaResponse(
            assujetti ? tauxLegal : BigDecimal.ZERO,
            enVigueur == null ? aujourdhui : enVigueur.getDateEffet(),
            historique.stream().map(TauxTvaResponse.HistoriqueEntry::from).toList(),
            assujetti,
            tauxLegal);
    }

    /** Régime de TVA de l'entreprise ; assujettie tant que l'administrateur n'a rien défini. */
    @Transactional(readOnly = true)
    public boolean estAssujetti() {
        return parametresRepository.findAll().stream().findFirst()
            .map(ParametresEntreprise::isAssujettiTva)
            .orElse(true);
    }

    /**
     * Définit le régime de TVA de l'entreprise (ADMIN). S'applique aux opérations
     * enregistrées ensuite ; les ventes et notes déjà saisies gardent la TVA (ou
     * l'absence de TVA) de leur date.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public TauxTvaResponse definirAssujettissement(boolean assujetti) {
        User auteur = currentUser.requireUser();
        ParametresEntreprise p = parametresEntreprise.obtenirEntite();
        if (p.isAssujettiTva() != assujetti) {
            p.setAssujettiTva(assujetti);
            parametresRepository.save(p);
            log.info("Regime de TVA : entreprise {} a la TVA, defini par {}",
                assujetti ? "assujettie" : "non assujettie", auteur.getEmail());
        }
        return consulter();
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
