package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.ParametresRestaurant;
import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModeRetraitPerimes;
import com.mbsc.finapp.domain.enums.ModuleMetier;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.restaurant.PlatPerimeResponse;
import com.mbsc.finapp.dto.restaurant.SortiePlatResponse;
import com.mbsc.finapp.repository.ParametresRestaurantRepository;
import com.mbsc.finapp.repository.UserRepository;
import com.mbsc.finapp.service.PlatsPerimesAutomatiqueService.Issue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La tâche planifiée des plats périmés, selon le mode choisi par l'administrateur : manuel, elle prévient le responsable ;
 * automatique, elle retire elle-même les portions périmées au nom de l'administrateur qui l'a activé, puis prévient.
 */
class PlatsPerimesAutomatiqueServiceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 10, 13);
    private static final String LIEN = "/restaurant/sorties-plats";

    private ParametresRestaurantRepository parametresRepository;
    private RestaurantService restaurant;
    private NotificationService notifications;
    private UserRepository utilisateurs;
    private ModuleConfigService modules;
    private PlatsPerimesAutomatiqueService service;

    private ParametresRestaurant parametres;
    private User responsable;
    private User admin;
    private User cumul;   // porte à la fois le rôle de responsable du restaurant et celui d'administrateur

    @BeforeEach
    void preparer() {
        parametresRepository = mock(ParametresRestaurantRepository.class);
        restaurant = mock(RestaurantService.class);
        notifications = mock(NotificationService.class);
        utilisateurs = mock(UserRepository.class);
        modules = mock(ModuleConfigService.class);
        when(modules.estActif(ModuleMetier.RESTAURANT)).thenReturn(true);
        service = new PlatsPerimesAutomatiqueService(parametresRepository, restaurant, notifications, utilisateurs, modules);

        responsable = User.builder().id(2L).prenom("Papy").nom("Penze").build();
        admin = administrateur(1L, "Marie", "Admin");
        cumul = User.builder().id(3L).prenom("Paul").nom("Cumul").build();
        when(utilisateurs.findByRoles_NomAndActifTrue(RoleType.RESP_RESTAURANT)).thenReturn(List.of(responsable, cumul));
        when(utilisateurs.findByRoles_NomAndActifTrue(RoleType.ADMIN)).thenReturn(List.of(admin, cumul));

        parametres = ParametresRestaurant.builder().id(ParametresRestaurant.SINGLETON_ID).build();
        when(parametresRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.of(parametres));
    }

    private static User administrateur(long id, String prenom, String nom) {
        return User.builder().id(id).prenom(prenom).nom(nom).roles(new HashSet<>(Set.of(Role.builder().nom(RoleType.ADMIN).build()))).build();
    }

    private static PlatPerimeResponse perime(String libelle, String quantite) {
        BigDecimal q = new BigDecimal(quantite);
        return new PlatPerimeResponse(10L, "PLAT-" + libelle, libelle, "portion", 1L, "ENT-01", q, new BigDecimal("2"),
            q.multiply(new BigDecimal("2")), JOUR.minusDays(1), List.of());
    }

    private static SortiePlatResponse sortie(String libelle, String quantite) {
        return new SortiePlatResponse(1L, 10L, "PLAT-" + libelle, libelle, "portion", "ENT-01", new BigDecimal(quantite),
            null, "Périmé", "Retrait automatique", JOUR, new BigDecimal("10"), "MS-1", "PC-1", false, "Marie Admin");
    }

    private void desPlatsPerimes(PlatPerimeResponse... plats) {
        when(restaurant.platsPerimesInterne(JOUR)).thenReturn(List.of(plats));
    }

    private void modeAutomatique(User auteur) {
        parametres.setRetraitPerimesMode(ModeRetraitPerimes.AUTOMATIQUE);
        parametres.setRetraitPerimesAuteur(auteur);
    }

    /** Les destinataires et les messages des notifications envoyées. */
    private List<String> messagesEnvoyes(TypeNotification type, int fois) {
        ArgumentCaptor<User> destinataire = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(fois)).notifierUtilisateur(
            destinataire.capture(), eq(type), anyString(), message.capture(), eq(LIEN), isNull());
        return new ArrayList<>(message.getAllValues());
    }

    // ---------------------------------------------------------------------
    // Module désactivé
    // ---------------------------------------------------------------------

    @Test
    void sansLeModuleRestaurantLaTacheNeFaitRienDuTout() {
        when(modules.estActif(ModuleMetier.RESTAURANT)).thenReturn(false);
        modeAutomatique(admin);
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.MODULE_INACTIF);
        assertThat(service.signalerEchec(JOUR, "erreur")).isEqualTo(Issue.MODULE_INACTIF);

        verify(parametresRepository, never()).findByIdPourMiseAJour(any());
        verify(restaurant, never()).platsPerimesInterne(any());
        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        verify(notifications, never()).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
    }

    // ---------------------------------------------------------------------
    // Rien de périmé
    // ---------------------------------------------------------------------

    @Test
    void sansPlatPerimeIlNeSePasseRien() {
        desPlatsPerimes();

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RIEN_A_FAIRE);
        verify(notifications, never()).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        assertThat(parametres.getPerimesSignalesLe()).isNull();
    }

    @Test
    void sansPlatPerimeEnModeAutomatiqueRienNEstRetire() {
        modeAutomatique(admin);
        desPlatsPerimes();

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RIEN_A_FAIRE);
        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
    }

    // ---------------------------------------------------------------------
    // Mode manuel : une notification, pas de retrait
    // ---------------------------------------------------------------------

    @Test
    void enModeManuelLaTachePrevientSansRienRetirer() {
        desPlatsPerimes(perime("Omelette", "5"), perime("Riz sauté", "2.5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);

        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        List<String> messages = messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3);
        assertThat(messages.get(0))
            .contains("7.5 portion(s) périmée(s)").contains("5 Omelette").contains("2.5 Riz sauté").contains("Sorties de plats");
        assertThat(parametres.getPerimesSignalesLe()).isEqualTo(JOUR);
    }

    @Test
    void leResponsableEtLesAdministrateursSontPrevenusUneSeuleFoisChacun() {
        desPlatsPerimes(perime("Omelette", "5"));

        service.traiter(JOUR);

        // Responsable, administrateur, et celui qui porte les deux rôles : trois notifications, pas quatre.
        ArgumentCaptor<User> destinataires = ArgumentCaptor.forClass(User.class);
        verify(notifications, times(3)).notifierUtilisateur(
            destinataires.capture(), eq(TypeNotification.PLATS_PERIMES), anyString(), anyString(), eq(LIEN), isNull());
        assertThat(destinataires.getAllValues()).containsExactlyInAnyOrder(responsable, cumul, admin);
    }

    @Test
    void laNotificationNePartQuUneFoisParJour() {
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);
        assertThat(service.traiter(JOUR)).isEqualTo(Issue.DEJA_SIGNALE);
        assertThat(service.traiter(JOUR)).isEqualTo(Issue.DEJA_SIGNALE);
        verify(notifications, times(3)).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());

        // Le lendemain, s'il reste du périmé, on prévient de nouveau.
        when(restaurant.platsPerimesInterne(JOUR.plusDays(1))).thenReturn(List.of(perime("Omelette", "5")));
        assertThat(service.traiter(JOUR.plusDays(1))).isEqualTo(Issue.SIGNALE);
        verify(notifications, times(6)).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
        assertThat(parametres.getPerimesSignalesLe()).isEqualTo(JOUR.plusDays(1));
    }

    @Test
    void unLongMessageResteDansLesLimitesDUneNotification() {
        List<PlatPerimeResponse> plats = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            plats.add(perime("Plat numéro " + i + " avec un nom très très long pour remplir le message", "3"));
        }
        when(restaurant.platsPerimesInterne(JOUR)).thenReturn(plats);

        service.traiter(JOUR);

        String message = messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3).get(0);
        assertThat(message.length()).isLessThanOrEqualTo(500);
        // La liste est écourtée, pas la consigne qui la suit.
        assertThat(message).contains("autre(s)").contains("120 portion(s) périmée(s)").endsWith("Retirez-les depuis Sorties de plats.");
    }

    @Test
    void sansPlatPerimeLaLigneDesParametresAbsenteNEstPasCreee() {
        when(parametresRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.empty());
        desPlatsPerimes();

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RIEN_A_FAIRE);
        assertThat(service.signalerEchec(JOUR, "erreur")).isEqualTo(Issue.RIEN_A_FAIRE);

        verify(parametresRepository, never()).save(any());
    }

    @Test
    void lesParametresAbsentsSontCreesAvecLeModeManuel() {
        when(parametresRepository.findByIdPourMiseAJour(ParametresRestaurant.SINGLETON_ID)).thenReturn(Optional.empty());
        when(parametresRepository.save(any(ParametresRestaurant.class))).thenAnswer(i -> i.getArgument(0));
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);

        ArgumentCaptor<ParametresRestaurant> creee = ArgumentCaptor.forClass(ParametresRestaurant.class);
        verify(parametresRepository).save(creee.capture());
        assertThat(creee.getValue().getRetraitPerimesMode()).isEqualTo(ModeRetraitPerimes.MANUEL);
        assertThat(creee.getValue().getPerimesSignalesLe()).isEqualTo(JOUR);
    }

    // ---------------------------------------------------------------------
    // Mode automatique : retrait au nom de l'administrateur, puis notification
    // ---------------------------------------------------------------------

    @Test
    void enModeAutomatiqueLaTacheRetireLesPlatsAuNomDeLAdministrateurPuisPrevient() {
        modeAutomatique(admin);
        desPlatsPerimes(perime("Omelette", "5"));
        when(restaurant.retirerPlatsPerimesInterne(admin, JOUR, null, null, true)).thenReturn(List.of(sortie("Omelette", "5")));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RETIRE);

        verify(restaurant).retirerPlatsPerimesInterne(admin, JOUR, null, null, true);
        List<String> messages = messagesEnvoyes(TypeNotification.PLATS_PERIMES_RETIRES, 3);
        assertThat(messages.get(0)).contains("Retrait automatique").contains("5 Omelette").contains("Marie Admin")
            .contains("Sorties de plats");
        // Ce n'est pas une alerte : le compteur du jour reste intact.
        assertThat(parametres.getPerimesSignalesLe()).isNull();
        verify(notifications, never()).notifierUtilisateur(any(), eq(TypeNotification.PLATS_PERIMES), anyString(), anyString(), any(), any());
    }

    @Test
    void lesMessagesDesRetraitsNeCitentJamaisDeMontant() {
        modeAutomatique(admin);
        desPlatsPerimes(perime("Omelette", "5"));
        when(restaurant.retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of(sortie("Omelette", "5")));

        service.traiter(JOUR);

        // La sortie vaut 10 (USD) : ni ce chiffre ni une devise n'apparaissent, le message étant aussi copié par e-mail.
        assertThat(messagesEnvoyes(TypeNotification.PLATS_PERIMES_RETIRES, 3).get(0)).doesNotContain("10").doesNotContain("USD").doesNotContain("FC");
    }

    @Test
    void siLePlatEstDejaRetireQuandLeVerrouEstPrisIlNYAPlusRienAFaire() {
        modeAutomatique(admin);
        desPlatsPerimes(perime("Omelette", "5"));
        // Le responsable vient de tout retirer : sous le verrou, il ne reste plus rien.
        when(restaurant.retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of());

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RIEN_A_FAIRE);
        verify(notifications, never()).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void sansAdministrateurResponsableLeRetraitAutomatiqueEstSuspenduEtLaTachePrevient() {
        modeAutomatique(null);   // l'administrateur qui l'avait activé a été supprimé
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);

        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        String message = messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3).get(0);
        assertThat(message).contains("n'est plus en mesure d'en répondre").contains("rien n'a été retiré").contains("5 Omelette")
            .contains("paramètres du restaurant");
        assertThat(parametres.getPerimesSignalesLe()).isEqualTo(JOUR);
    }

    @Test
    void unAdministrateurDesactiveNeRetireRienNonPlus() {
        User parti = administrateur(9L, "Ancien", "Admin");
        parti.setActif(false);
        modeAutomatique(parti);
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);
        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        assertThat(messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3).get(0)).contains("n'est plus en mesure d'en répondre");
    }

    @Test
    void unAuteurQuiNEstPlusAdministrateurNeRepondPlusDesRetraits() {
        // Compte actif, mais son rôle d'administrateur lui a été retiré depuis : la tâche ne passe plus d'écriture en son nom.
        User retrograde = User.builder().id(8L).prenom("Ancien").nom("Admin").roles(new HashSet<>(Set.of(Role.builder().nom(RoleType.RESP_RESTAURANT).build()))).build();
        modeAutomatique(retrograde);
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);
        verify(restaurant, never()).retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean());
        assertThat(messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3).get(0)).contains("qui n'est plus administrateur").contains("rien n'a été retiré");
    }

    @Test
    void leRetraitAutomatiqueSuspenduNePrevientQuUneFoisParJourLuiAussi() {
        modeAutomatique(null);
        desPlatsPerimes(perime("Omelette", "5"));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.SIGNALE);
        assertThat(service.traiter(JOUR)).isEqualTo(Issue.DEJA_SIGNALE);
        verify(notifications, times(3)).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void lesRetraitsAutomatiquesNeSontJamaisBloquesParLeCompteurDAlerte() {
        modeAutomatique(admin);
        parametres.setPerimesSignalesLe(JOUR);   // une alerte est déjà partie aujourd'hui (ex. un échec précédent)
        desPlatsPerimes(perime("Omelette", "5"));
        when(restaurant.retirerPlatsPerimesInterne(any(), any(), any(), any(), anyBoolean())).thenReturn(List.of(sortie("Omelette", "5")));

        assertThat(service.traiter(JOUR)).isEqualTo(Issue.RETIRE);
    }

    // ---------------------------------------------------------------------
    // Échec du retrait automatique
    // ---------------------------------------------------------------------

    @Test
    void unEchecDuRetraitAutomatiqueEstSignaleUneFoisParJourAvecSaCause() {
        modeAutomatique(admin);

        assertThat(service.signalerEchec(JOUR, "la période comptable est clôturée")).isEqualTo(Issue.SIGNALE);
        assertThat(service.signalerEchec(JOUR, "la période comptable est clôturée")).isEqualTo(Issue.DEJA_SIGNALE);

        String message = messagesEnvoyes(TypeNotification.PLATS_PERIMES, 3).get(0);
        assertThat(message).contains("a échoué").contains("la période comptable est clôturée").contains("retirez-les à la main");
        assertThat(parametres.getPerimesSignalesLe()).isEqualTo(JOUR);
    }

    @Test
    void enModeManuelUnEchecDeLaTacheNeNotifiePasLeResponsable() {
        assertThat(service.signalerEchec(JOUR, "erreur")).isEqualTo(Issue.RIEN_A_FAIRE);
        verify(notifications, never()).notifierUtilisateur(any(), any(), anyString(), anyString(), any(), any());
    }
}
