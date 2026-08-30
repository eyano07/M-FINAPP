package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantRequest;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantResponse;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paramètres du module Restaurant (ligne unique) : devise d'affichage par
 * défaut, utilisée par les écrans de la carte, du stock et des tableaux de
 * bord pour présenter les montants en USD ou en FC.
 */
@Service
@RequiredArgsConstructor
public class ParametresRestaurantService {

    private static final Logger log = LoggerFactory.getLogger(ParametresRestaurantService.class);

    private final ParametresRestaurantRepository repository;

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
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'DA', 'COMPTABLE', 'ADMIN')")
    public ParametresRestaurantResponse consulter() {
        return ParametresRestaurantResponse.from(
            repository.findById(ParametresRestaurant.SINGLETON_ID).orElseGet(this::parDefaut));
    }

    private ParametresRestaurant parDefaut() {
        return ParametresRestaurant.builder()
            .id(ParametresRestaurant.SINGLETON_ID)
            .deviseAffichage(Devise.USD)
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
}
