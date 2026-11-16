package com.mbsc.finapp.service;

import com.mbsc.finapp.controller.ParametresRestaurantController;
import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.dto.restaurant.ParametresRestaurantResponse;
import com.mbsc.finapp.dto.restaurant.RetraitPerimesModeRequest;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le choix du retrait des plats périmés (manuel avec notification, ou automatique) appartient à l'administrateur, et
 * l'administrateur qui active l'automatique répond des écritures de la tâche.
 */
class RetraitPerimesModeTest {

    private ParametresRestaurantRepository repository;
    private CurrentUserProvider utilisateur;
    private ParametresRestaurantService service;
    private ParametresRestaurant parametres;
    private User marie;
    private User paul;

    @BeforeEach
    void preparer() {
        repository = mock(ParametresRestaurantRepository.class);
        utilisateur = mock(CurrentUserProvider.class);
        service = new ParametresRestaurantService(repository, utilisateur);
        parametres = ParametresRestaurant.builder().id(ParametresRestaurant.SINGLETON_ID).build();
        when(repository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.of(parametres));
        when(repository.save(any(ParametresRestaurant.class))).thenAnswer(i -> i.getArgument(0));
        marie = administrateur(1L, "Marie", "Admin");
        paul = administrateur(4L, "Paul", "Autre");
        when(utilisateur.requireUser()).thenReturn(marie);
    }

    private static User administrateur(long id, String prenom, String nom) {
        return User.builder().id(id).prenom(prenom).nom(nom).roles(new HashSet<>(Set.of(Role.builder().nom(RoleType.ADMIN).build()))).build();
    }

    @Test
    void leModeParDefautEstManuel() {
        assertThat(parametres.getRetraitPerimesMode()).isEqualTo(ModeRetraitPerimes.MANUEL);
        assertThat(parametres.getRetraitPerimesAuteur()).isNull();
        assertThat(ParametresRestaurantResponse.from(parametres).retraitPerimesMode()).isEqualTo(ModeRetraitPerimes.MANUEL);
    }

    @Test
    void lAdministrateurActiveLeRetraitAutomatiqueEtEnRepond() {
        parametres.setPerimesSignalesLe(java.time.LocalDate.of(2026, 10, 12));

        ParametresRestaurantResponse reponse = service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);

        assertThat(parametres.getRetraitPerimesMode()).isEqualTo(ModeRetraitPerimes.AUTOMATIQUE);
        assertThat(parametres.getRetraitPerimesAuteur()).isSameAs(marie);
        assertThat(parametres.getRetraitPerimesModifieLe()).isNotNull();
        assertThat(parametres.getPerimesSignalesLe()).isNull();   // un changement de mode remet l'alerte du jour à zéro
        assertThat(reponse.retraitPerimesMode()).isEqualTo(ModeRetraitPerimes.AUTOMATIQUE);
        assertThat(reponse.retraitPerimesPar()).isEqualTo("Marie Admin");
        assertThat(reponse.retraitPerimesParActif()).isTrue();
        assertThat(reponse.retraitPerimesLe()).isNotNull();
        verify(repository).save(parametres);
    }

    @Test
    void repasserEnManuelEffaceLAuteur() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);

        ParametresRestaurantResponse reponse = service.definirRetraitPerimes(ModeRetraitPerimes.MANUEL);

        assertThat(parametres.getRetraitPerimesMode()).isEqualTo(ModeRetraitPerimes.MANUEL);
        assertThat(parametres.getRetraitPerimesAuteur()).isNull();
        assertThat(reponse.retraitPerimesPar()).isNull();
    }

    @Test
    void confirmerLeMemeModeDepuisLeMemeCompteNeChangeRien() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);
        java.time.Instant date = parametres.getRetraitPerimesModifieLe();
        parametres.setPerimesSignalesLe(java.time.LocalDate.of(2026, 10, 13));

        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);

        assertThat(parametres.getRetraitPerimesModifieLe()).isEqualTo(date);
        assertThat(parametres.getPerimesSignalesLe()).isEqualTo(java.time.LocalDate.of(2026, 10, 13));
        verify(repository).save(parametres);   // un seul enregistrement : le premier
    }

    @Test
    void resterEnManuelNeChangeRienNonPlus() {
        service.definirRetraitPerimes(ModeRetraitPerimes.MANUEL);

        verify(repository, never()).save(any());
        assertThat(parametres.getRetraitPerimesModifieLe()).isNull();
    }

    @Test
    void unAutreAdministrateurPeutReprendreLaResponsabiliteDuRetraitAutomatique() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);
        when(utilisateur.requireUser()).thenReturn(paul);

        ParametresRestaurantResponse reponse = service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);

        assertThat(parametres.getRetraitPerimesAuteur()).isSameAs(paul);
        assertThat(reponse.retraitPerimesPar()).isEqualTo("Paul Autre");
    }

    @Test
    void laReponseSignaleUnAdministrateurDesactive() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);
        marie.setActif(false);

        ParametresRestaurantResponse reponse = ParametresRestaurantResponse.from(parametres);

        assertThat(reponse.retraitPerimesPar()).isEqualTo("Marie Admin");
        assertThat(reponse.retraitPerimesParActif()).isFalse();
    }

    @Test
    void laReponseSignaleUnAuteurQuiNEstPlusAdministrateur() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);
        marie.getRoles().clear();

        assertThat(ParametresRestaurantResponse.from(parametres).retraitPerimesParActif()).isFalse();
        assertThat(parametres.retraitPerimesAuteurHabilite()).isFalse();
    }

    @Test
    void lesParametresAbsentsSontCreesPuisMisAJour() {
        when(repository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.empty());

        ParametresRestaurantResponse reponse = service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);

        assertThat(reponse.retraitPerimesMode()).isEqualTo(ModeRetraitPerimes.AUTOMATIQUE);
        assertThat(reponse.retraitPerimesPar()).isEqualTo("Marie Admin");
    }

    @Test
    void lEnregistrementDeLaDeviseNeToucheJamaisAuRetraitDesPlatsPerimes() {
        service.definirRetraitPerimes(ModeRetraitPerimes.AUTOMATIQUE);
        when(repository.findById(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.of(parametres));

        ParametresRestaurantResponse reponse = service.enregistrer(
            new com.mbsc.finapp.dto.restaurant.ParametresRestaurantRequest(com.mbsc.finapp.domain.enums.Devise.USD));

        assertThat(reponse.deviseAffichage()).isEqualTo(com.mbsc.finapp.domain.enums.Devise.USD);
        assertThat(reponse.retraitPerimesMode()).isEqualTo(ModeRetraitPerimes.AUTOMATIQUE);
        assertThat(reponse.retraitPerimesPar()).isEqualTo("Marie Admin");
    }

    @Test
    void seulLAdministrateurChoisitLeModeDeRetrait() throws Exception {
        Method choix = Arrays.stream(ParametresRestaurantService.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("definirRetraitPerimes")).findFirst().orElseThrow();
        assertThat(choix.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");

        // La route de l'API délègue à cette seule méthode : aucun autre chemin n'écrit le mode.
        Method route = ParametresRestaurantController.class.getDeclaredMethod("definirRetraitPerimes", RetraitPerimesModeRequest.class);
        assertThat(route.getAnnotation(PutMapping.class).value()).containsExactly("/retrait-perimes");
        assertThat(Arrays.stream(ParametresRestaurantService.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("enregistrer"))
            .map(m -> m.getAnnotation(PreAuthorize.class).value()).findFirst().orElseThrow())
            .isEqualTo("hasAnyRole('RESP_RESTAURANT', 'ADMIN')");
    }
}
