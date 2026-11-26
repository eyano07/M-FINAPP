package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.ServeurDistantRequest;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adresse du serveur en ligne et cle d'appairage saisies sur le serveur local (V132) : une cle laissee vide garde la cle
 * enregistree, un ancien serveur local se relie avec une nouvelle cle seulement, rien ne change pendant le mode local.
 */
class InstanceAdminServiceTest {

    private static final String ADRESSE = "192.0.2.10:9090";

    private final InstanceService instance = mock(InstanceService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final InstanceAdminService service = new InstanceAdminService(instance, mock(ReplicationClient.class), currentUser,
        new ObjectMapper(), mock(JournalReplication.class), mock(ReplicationEmetteur.class));
    private ParametresInstance parametres;

    @BeforeEach
    void preparer() {
        when(currentUser.requireUser()).thenReturn(User.builder().id(1L).email("admin").build());
        when(instance.chiffrer(anyString())).thenAnswer(i -> "chiffre:" + i.getArgument(0));
        when(instance.etat()).thenAnswer(i -> parametres);
        when(instance.mode()).thenAnswer(i -> parametres.getMode());
        when(instance.versionSchema()).thenReturn(133);
        when(instance.modifier(any(), anyString())).thenAnswer(i -> {
            Consumer<ParametresInstance> maj = i.getArgument(0);
            maj.accept(parametres);
            return parametres;
        });
    }

    private static ParametresInstance avecCle(ModeInstance mode, SignatureReplication.Cle cle, String adresse) {
        return ParametresInstance.builder().mode(mode).adresseDistante(adresse).cleChiffree("chiffre:" + cle.texte())
            .cleFin(cle.fin()).appairageId(cle.appairageId()).build();
    }

    @Test
    void une_cle_laissee_vide_garde_la_cle_enregistree_et_change_seulement_l_adresse() {
        SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
        parametres = avecCle(ModeInstance.EN_LIGNE, cle, "http://ancienne-adresse:9090");

        service.configurerServeurDistant(new ServeurDistantRequest(ADRESSE, "  "));

        assertThat(parametres.getAdresseDistante()).isEqualTo("http://192.0.2.10:9090");
        assertThat(parametres.getCleChiffree()).isEqualTo("chiffre:" + cle.texte());
        assertThat(parametres.getAppairageId()).isEqualTo(cle.appairageId());
    }

    @Test
    void une_cle_vide_est_refusee_s_il_n_y_a_pas_de_cle_saisie_avec_une_adresse() {
        // Aucune cle, puis une cle generee sur ce serveur quand il servait de serveur en ligne (sans adresse distante).
        for (ParametresInstance p : new ParametresInstance[] {
            ParametresInstance.builder().mode(ModeInstance.EN_LIGNE).build(),
            avecCle(ModeInstance.EN_LIGNE, SignatureReplication.nouvelleCle(), null)}) {
            parametres = p;
            assertThatThrownBy(() -> service.configurerServeurDistant(new ServeurDistantRequest(ADRESSE, "")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(instance, never()).modifier(any(), anyString());
    }

    @Test
    void un_ancien_serveur_local_se_relie_avec_une_nouvelle_cle_seulement() {
        SignatureReplication.Cle ancienne = SignatureReplication.nouvelleCle();
        parametres = avecCle(ModeInstance.LOCAL_ARRETE, ancienne, "http://192.0.2.10:9090");

        assertThatThrownBy(() -> service.configurerServeurDistant(new ServeurDistantRequest(ADRESSE, "")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("nouvelle clé");
        assertThat(parametres.getAppairageId()).isEqualTo(ancienne.appairageId());

        SignatureReplication.Cle nouvelle = SignatureReplication.nouvelleCle();
        service.configurerServeurDistant(new ServeurDistantRequest(ADRESSE, nouvelle.texte()));

        assertThat(parametres.getAppairageId()).isEqualTo(nouvelle.appairageId());
        assertThat(parametres.getCleChiffree()).isEqualTo("chiffre:" + nouvelle.texte());
        assertThat(parametres.getMode()).as("le mode ne change qu'a l'activation").isEqualTo(ModeInstance.LOCAL_ARRETE);
    }

    @Test
    void rien_ne_change_pendant_le_mode_local_ni_sur_la_copie_en_ligne() {
        for (ModeInstance mode : new ModeInstance[] {ModeInstance.LOCAL, ModeInstance.MIROIR}) {
            parametres = ParametresInstance.builder().mode(mode).build();
            String cle = SignatureReplication.nouvelleCle().texte();
            assertThatThrownBy(() -> service.configurerServeurDistant(new ServeurDistantRequest(ADRESSE, cle)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
        verify(instance, never()).modifier(any(), anyString());
    }
}
