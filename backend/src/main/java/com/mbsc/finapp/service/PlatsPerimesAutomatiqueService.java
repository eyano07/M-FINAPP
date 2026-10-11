package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.restaurant.PlatPerimeResponse;
import com.mbsc.finapp.dto.restaurant.SortiePlatResponse;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ce que la tâche planifiée fait des plats périmés (voir {@code PlatsPerimesPlanificateur}), selon le mode que
 * l'administrateur a choisi dans les paramètres du restaurant :
 *
 * <ul>
 *   <li><b>manuel</b> : elle prévient le responsable du restaurant et les administrateurs, une fois par jour tant qu'il reste
 *       des portions périmées ; le responsable les retire lui-même (Sorties de plats) ;</li>
 *   <li><b>automatique</b> : elle les retire elle-même, avec la même écriture qu'un retrait manuel, au nom de
 *       l'administrateur qui a activé le mode, puis prévient. Si cet administrateur n'existe plus, est désactivé ou n'est
 *       plus administrateur, elle ne retire rien et prévient comme en mode manuel : personne ne répond d'une écriture
 *       passée à son nom.</li>
 * </ul>
 *
 * <p>Les notifications ne citent que des quantités et des noms de plats, jamais de montants : elles sont aussi copiées par
 * e-mail. Module Restaurant désactivé par l'administrateur : la tâche ne fait rien.</p>
 */
@Service
@RequiredArgsConstructor
public class PlatsPerimesAutomatiqueService {

    private static final Logger log = LoggerFactory.getLogger(PlatsPerimesAutomatiqueService.class);

    /** Écran où l'on retire les plats périmés et où l'on retrouve le détail des retraits. */
    static final String LIEN = "/restaurant/sorties-plats";

    /** Le message d'une notification tient en 500 caractères. */
    private static final int LIMITE_MESSAGE = 500;

    /** Ce que la vérification a fait. */
    public enum Issue {
        /** Le module Restaurant est désactivé : la tâche n'écrit rien et ne prévient personne. */
        MODULE_INACTIF,
        /** Aucun plat périmé en stock (ou plus rien à retirer quand le verrou a été obtenu). */
        RIEN_A_FAIRE,
        /** Des plats périmés ont été retirés du stock (mode automatique). */
        RETIRE,
        /** Une notification a prévenu qu'il faut retirer des plats périmés. */
        SIGNALE,
        /** Il reste des plats périmés, mais la notification du jour est déjà partie. */
        DEJA_SIGNALE
    }

    private final ParametresRestaurantRepository parametresRepository;
    private final RestaurantService restaurantService;
    private final NotificationService notifications;
    private final UserRepository userRepository;
    private final ModuleConfigService modules;

    /**
     * Vérifie les plats périmés au {@code jour} donné : les retire ou prévient, selon le mode. Une transaction : si le
     * retrait échoue, rien n'est retiré (l'appelant prévient alors par {@link #signalerEchec}).
     */
    @Transactional
    public Issue traiter(LocalDate jour) {
        if (!modules.estActif(ModuleMetier.RESTAURANT)) {
            return Issue.MODULE_INACTIF;   // l'administrateur a désactivé le module : ni retrait automatique, ni notification
        }
        // Verrou d'abord : la liste des plats périmés doit être lue après tout retrait concurrent, sinon on préviendrait de
        // portions que le responsable vient de retirer (ou on les retirerait une seconde fois).
        Optional<ParametresRestaurant> existants = parametresRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID);
        List<PlatPerimeResponse> perimes = restaurantService.platsPerimesInterne(jour);
        if (perimes.isEmpty()) {
            return Issue.RIEN_A_FAIRE;   // rien à faire : on ne crée pas de ligne de paramètres pour si peu
        }
        ParametresRestaurant p = existants.orElseGet(this::creerParametres);

        User auteur = p.getRetraitPerimesAuteur();
        boolean automatique = p.getRetraitPerimesMode() == ModeRetraitPerimes.AUTOMATIQUE;
        if (automatique && p.retraitPerimesAuteurHabilite()) {
            List<SortiePlatResponse> sorties = restaurantService.retirerPlatsPerimesInterne(auteur, jour, null, null, true);
            if (sorties.isEmpty()) {
                return Issue.RIEN_A_FAIRE;
            }
            log.info("Plats périmés retirés automatiquement du stock [sorties={}, auteur={}]", sorties.size(), auteur.getId());
            prevenir(TypeNotification.PLATS_PERIMES_RETIRES, "Plats périmés retirés du stock",
                message("Retrait automatique : ", sorties.stream().map(s -> quantite(s.quantite()) + " " + s.articleLibelle()).toList(),
                    ". Écritures passées au nom de " + nomComplet(auteur) + " ; détail dans Sorties de plats."));
            return Issue.RETIRE;
        }

        // Retrait manuel, ou retrait automatique suspendu : une notification par jour.
        if (jour.equals(p.getPerimesSignalesLe())) {
            return Issue.DEJA_SIGNALE;
        }
        String portions = quantite(perimes.stream().map(PlatPerimeResponse::quantitePerimee).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<String> detail = perimes.stream().map(x -> quantite(x.quantitePerimee()) + " " + x.platLibelle()).toList();
        if (automatique) {
            log.warn("Retrait automatique des plats périmés suspendu : l'administrateur qui l'a activé n'est plus habilité");
            prevenir(TypeNotification.PLATS_PERIMES, "Retrait automatique des plats périmés suspendu",
                message("L'administrateur qui avait activé le retrait automatique n'est plus en mesure d'en répondre "
                        + "(compte supprimé, désactivé, ou qui n'est plus administrateur) : rien n'a été retiré. "
                        + portions + " portion(s) périmée(s) restent en stock (",
                    detail,
                    "). Un administrateur doit confirmer le mode automatique dans les paramètres du restaurant ; "
                        + "en attendant, retirez-les depuis Sorties de plats."));
        } else {
            prevenir(TypeNotification.PLATS_PERIMES, "Plats périmés à retirer du stock",
                message(portions + " portion(s) périmée(s) : ", detail, ". Retirez-les depuis Sorties de plats."));
        }
        p.setPerimesSignalesLe(jour);
        return Issue.SIGNALE;
    }

    /**
     * Le retrait automatique a échoué (période comptable clôturée, compte manquant...) : prévient une fois par jour pour que
     * le responsable retire les plats à la main ou qu'on corrige la cause. Sans effet en mode manuel, où la tâche ne fait que
     * lire. À appeler après l'échec de {@link #traiter}, dans sa propre transaction : celle de {@code traiter} est annulée,
     * notifications comprises.
     */
    @Transactional
    public Issue signalerEchec(LocalDate jour, String cause) {
        if (!modules.estActif(ModuleMetier.RESTAURANT)) {
            return Issue.MODULE_INACTIF;
        }
        ParametresRestaurant p = parametresRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID).orElse(null);
        if (p == null || p.getRetraitPerimesMode() != ModeRetraitPerimes.AUTOMATIQUE) {
            return Issue.RIEN_A_FAIRE;   // sans ligne de paramètres, le mode est le mode par défaut : manuel
        }
        if (jour.equals(p.getPerimesSignalesLe())) {
            return Issue.DEJA_SIGNALE;
        }
        prevenir(TypeNotification.PLATS_PERIMES, "Retrait automatique des plats périmés en échec",
            "Le retrait automatique des plats périmés a échoué (" + cause + "). Rien n'a été retiré : "
                + "vérifiez les plats périmés dans Sorties de plats et retirez-les à la main, ou corrigez la cause.");
        p.setPerimesSignalesLe(jour);
        return Issue.SIGNALE;
    }

    /** La ligne des paramètres manque (base purgée) : on la crée avec les valeurs par défaut, retrait manuel compris. */
    private ParametresRestaurant creerParametres() {
        return parametresRepository.save(ParametresRestaurant.builder().id(ParametresRestaurant.SINGLETON_ID).build());
    }

    /** Le responsable du restaurant et les administrateurs, chacun une seule fois même s'il porte les deux rôles. */
    private void prevenir(TypeNotification type, String titre, String message) {
        String texte = message.length() > LIMITE_MESSAGE ? message.substring(0, LIMITE_MESSAGE - 1) + "…" : message;
        Map<Long, User> destinataires = new LinkedHashMap<>();
        for (RoleType role : List.of(RoleType.RESP_RESTAURANT, RoleType.ADMIN)) {
            for (User u : userRepository.findByRoles_NomAndActifTrue(role)) {
                destinataires.putIfAbsent(u.getId(), u);
            }
        }
        for (User u : destinataires.values()) {
            notifications.notifierUtilisateur(u, type, titre, texte, LIEN, null);
        }
    }

    /**
     * {@code avant} + « 5 Omelette, 3 Riz sauté » + {@code apres}, dans les 500 caractères d'une notification : la liste des
     * plats est écourtée (« …, et 4 autre(s) ») plutôt que de couper la phrase qui dit quoi faire.
     */
    private static String message(String avant, List<String> elements, String apres) {
        int budget = Math.max(40, LIMITE_MESSAGE - avant.length() - apres.length() - 20);   // 20 : « , et 123 autre(s) »
        StringBuilder liste = new StringBuilder();
        int retenus = 0;
        for (String element : elements) {
            if (retenus > 0 && liste.length() + 2 + element.length() > budget) {
                break;
            }
            if (retenus > 0) {
                liste.append(", ");
            }
            liste.append(element.length() > budget ? element.substring(0, budget - 1) + "…" : element);
            retenus++;
        }
        if (retenus < elements.size()) {
            liste.append(", et ").append(elements.size() - retenus).append(" autre(s)");
        }
        return avant + liste + apres;
    }

    private static String quantite(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static String nomComplet(User u) {
        return ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
    }
}
