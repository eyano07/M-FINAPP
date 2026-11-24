package com.mbsc.finapp.service.patrimoine;

import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.patrimoine.DotationsResponse;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.service.ModuleConfigService;
import com.mbsc.finapp.service.PatrimoineService;
import com.mbsc.finapp.service.instance.InstanceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;

/**
 * Passage automatique des dotations aux amortissements : toutes les 6 heures (la première 3 minutes après le démarrage),
 * les dotations des mois écoulés qui ne sont pas encore passées le sont, une pièce par bien, comme avec le bouton
 * « Passer les dotations ». Sans effet quand tout est à jour : une échéance n'est jamais passée deux fois.
 *
 * <p>Les pièces sont passées au nom du premier administrateur actif. Copie en ligne en lecture seule ou module
 * Patrimoine désactivé : la tâche ne fait rien.</p>
 */
@Component
@RequiredArgsConstructor
public class DotationsPlanificateur {

    private static final Logger log = LoggerFactory.getLogger(DotationsPlanificateur.class);

    private final PatrimoineService patrimoine;
    private final UserRepository userRepository;
    private final InstanceService instance;
    private final ModuleConfigService modules;

    @Scheduled(fixedDelayString = "${app.dotations.intervalle-ms:21600000}",
               initialDelayString = "${app.dotations.delai-initial-ms:180000}")
    public void passer() {
        if (instance.lectureSeule() || !modules.estActif(ModuleMetier.PATRIMOINE)) {
            return;
        }
        Optional<User> auteur = userRepository.findByRoles_NomAndActifTrue(RoleType.ADMIN).stream()
            .min(Comparator.comparing(User::getId));
        if (auteur.isEmpty()) {
            log.warn("Dotations automatiques : aucun administrateur actif au nom duquel passer les pièces");
            return;
        }
        LocalDate jusqua = PatrimoineService.finDuMoisEcoule(LocalDate.now());
        try {
            DotationsResponse r = patrimoine.comptabiliserDotationsInterne(jusqua, auteur.get(), null);
            if (r.lignesComptabilisees() > 0 || !r.ignorees().isEmpty()) {
                log.info("Dotations automatiques jusqu'au {} : {} échéance(s), {} pièce(s), {} bien(s) écarté(s) {}",
                    jusqua, r.lignesComptabilisees(), r.piecesCreees().size(), r.ignorees().size(), r.ignorees());
            }
        } catch (RuntimeException e) {
            log.error("Dotations automatiques en échec", e);
        }
    }
}
