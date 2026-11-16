package com.mbsc.finapp.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Détail métier dans le journal d'audit : un service dépose le détail de son action (note, montant, reçu...) et le filtre
 * l'inscrit dans la ligne de la requête — sans lui, l'auditeur ne voit que l'opération et l'identifiant de la ressource.
 */
class AuditFilterDetailTest {

    private final AuditService audit = mock(AuditService.class);
    private final AuditFilter filtre = new AuditFilter(audit);

    @AfterEach
    void nettoyer() {
        RequestContextHolder.resetRequestAttributes();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ligneAudit(String detailDepose) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/caisse/retours-fonds/7/valider");
        req.setServletPath("/caisse/retours-fonds/7/valider");
        filtre.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            // Ce que fait le service appelé pendant la requête (RequestContextHolder est posé par Spring MVC).
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((HttpServletRequest) rq));
            AuditService.detailler(detailDepose);
        });
        ArgumentCaptor<Map<String, Object>> ligne = ArgumentCaptor.forClass(Map.class);
        verify(audit).enregistrer(ligne.capture());
        return ligne.getValue();
    }

    @Test
    void leDetailDeposeParLeServiceEntreDansLaLigneDuJournal() throws Exception {
        Map<String, Object> ligne = ligneAudit("NF-2026-000011 — 98 000,00 CDF reçus en espèces, reçu REC-2026-000004");

        assertThat(ligne).containsEntry("detail", "NF-2026-000011 — 98 000,00 CDF reçus en espèces, reçu REC-2026-000004")
            .containsEntry("operation", "VALIDER").containsEntry("reussi", true);
    }

    @Test
    void sansDetailLaLigneNEnPortePas() throws Exception {
        assertThat(ligneAudit(null)).doesNotContainKey("detail");
    }

    @Test
    void unDetailTropLongEstTronque() throws Exception {
        String detail = (String) ligneAudit("x".repeat(800)).get("detail");

        assertThat(detail).hasSize(AuditService.TAILLE_DETAIL).endsWith("…");
    }

    @Test
    void horsRequeteDeposerUnDetailEstSansEffet() {
        RequestContextHolder.resetRequestAttributes();

        AuditService.detailler("tâche planifiée");   // ne lève pas

        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }
}
