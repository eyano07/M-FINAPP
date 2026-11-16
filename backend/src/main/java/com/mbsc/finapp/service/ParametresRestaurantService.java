package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantRequest;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantResponse;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Paramètres du module Restaurant (ligne unique) : devise d'affichage par
 * défaut, utilisée par les écrans de la carte, du stock et des tableaux de
 * bord pour présenter les montants en USD ou en FC, et mode de retrait des
 * plats périmés (manuel avec notification, ou automatique), choisi par
 * l'administrateur.
 */
@Service
@RequiredArgsConstructor
public class ParametresRestaurantService {

    private static final Logger log = LoggerFactory.getLogger(ParametresRestaurantService.class);

    private final ParametresRestaurantRepository repository;
    private final CurrentUserProvider currentUser;

    @Transactional
    public ParametresRestaurant get() {
        return repository.findById(ParametresRestaurant.SINGLETON_ID)
            .orElseGet(() -> repository.save(parDefaut()));
    }

    /**
     * Lecture seule : ne tente jamais de créer la ligne manquante. La
     * migration V53 l'insère, et un {@code save} dans une transaction
     * {@code readOnly} ne serait de toute façon pas garanti d'être vidé en
     * base — mieux vaut répondre la valeur par défaut que dépendre d'une
     * écriture qui pourrait être silencieusement perdue.
     *
     * <p>Ouvert aux mêmes rôles que la lecture du module (voir
     * {@code RestaurantService.LECTURE}), caissier compris : sans cette
     * lecture, ses écrans ne connaissaient ni la devise d'affichage ni le
     * taux du jour, et montraient les montants en dollars ou en tirets.</p>
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'CAISSIER', 'ADMIN')")
    public ParametresRestaurantResponse consulter() {
        return ParametresRestaurantResponse.from(
            repository.findById(ParametresRestaurant.SINGLETON_ID).orElseGet(this::parDefaut));
    }

    /**
     * Valeurs par défaut quand la ligne n'existe pas (base neuve, ou après une purge totale) :
     * le franc congolais, devise du restaurant. Le choix de l'utilisateur, une fois enregistré,
     * prime toujours.
     */
    private ParametresRestaurant parDefaut() {
        return ParametresRestaurant.builder()
            .id(ParametresRestaurant.SINGLETON_ID)
            .deviseAffichage(Devise.CDF)
            .build();
    }

    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'ADMIN')")
    @Transactional
    public ParametresRestaurantResponse enregistrer(ParametresRestaurantRequest req) {
        ParametresRestaurant p = get();
        p.setDeviseAffichage(req.deviseAffichage());
        p = repository.save(p);
        log.info("Paramètres restaurant mis à jour [deviseAffichage={}]", p.getDeviseAffichage());
        return ParametresRestaurantResponse.from(p);
    }

    /**
     * Retrait des plats périmés : l'administrateur décide s'il est manuel (une notification prévient le responsable, qui
     * retire les portions) ou automatique (la tâche planifiée les retire elle-même, puis prévient). Réservé à
     * l'administrateur : c'est lui qui autorise la tâche à écrire dans le stock et la comptabilité.
     *
     * <p>Les retraits automatiques sont passés au nom de l'administrateur qui active le mode : une écriture a toujours un
     * auteur identifiable. Le confirmer depuis un autre compte transfère cette responsabilité (utile quand le premier
     * administrateur a quitté l'entreprise) ; le refaire depuis le même compte ne change rien. Un changement de mode remet à
     * zéro le compteur d'alerte du jour.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ParametresRestaurantResponse definirRetraitPerimes(ModeRetraitPerimes mode) {
        User admin = currentUser.requireUser();
        ParametresRestaurant p = repository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)
            .orElseGet(() -> repository.save(parDefaut()));
        boolean inchange = p.getRetraitPerimesMode() == mode
            && (mode == ModeRetraitPerimes.MANUEL
                || (p.getRetraitPerimesAuteur() != null && p.getRetraitPerimesAuteur().getId().equals(admin.getId())));
        if (!inchange) {
            p.setRetraitPerimesMode(mode);
            p.setRetraitPerimesAuteur(mode == ModeRetraitPerimes.AUTOMATIQUE ? admin : null);
            p.setRetraitPerimesModifieLe(Instant.now());
            p.setPerimesSignalesLe(null);
            p = repository.save(p);
            log.info("Retrait des plats périmés : mode {} défini par l'utilisateur {}", mode, admin.getId());
        }
        return ParametresRestaurantResponse.from(p);
    }
}
