package com.mbsc.finapp.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    @Test
    void seulesLesActionsQuiModifientSontTracees() {
        assertFalse(AuditService.aTracer("GET", "/ventes/5"));
        assertFalse(AuditService.aTracer("HEAD", "/ventes"));
        assertFalse(AuditService.aTracer("OPTIONS", "/ventes"));
        assertTrue(AuditService.aTracer("POST", "/ventes"));
        assertTrue(AuditService.aTracer("PUT", "/clients/3"));
        assertTrue(AuditService.aTracer("PATCH", "/x/1"));
        assertTrue(AuditService.aTracer("DELETE", "/budgets/9"));
        assertFalse(AuditService.aTracer("POST", "/auth/refresh"));
        assertFalse(AuditService.aTracer("POST", "/notifications/lire-tout"));
        assertTrue(AuditService.aTracer("POST", "/authentification-x"));
    }

    @Test
    void decritLActionDepuisLaRequete() {
        assertEquals(new AuditService.Action("ventes", "CREER", null), AuditService.decrire("POST", "/ventes"));
        assertEquals(new AuditService.Action("ventes", "VALIDER", "5"), AuditService.decrire("POST", "/ventes/5/valider"));
        assertEquals(new AuditService.Action("clients", "MODIFIER", "3"), AuditService.decrire("PUT", "/clients/3"));
        assertEquals(new AuditService.Action("budgets", "SUPPRIMER", "9"), AuditService.decrire("DELETE", "/budgets/9"));
        assertEquals(new AuditService.Action("admin/ia", "MODIFIER", null), AuditService.decrire("PUT", "/admin/ia/openai"));
        assertEquals(new AuditService.Action("drh/bulletins", "PAYER", "12"), AuditService.decrire("POST", "/drh/bulletins/12/payer"));
        assertEquals(new AuditService.Action("ventes", "FACTURE_NORMALISEE", "5"), new AuditService.Action("ventes", "FACTURE_NORMALISEE", "5"));
    }

    @Test
    void lEnteteUserAgentEstGardeTronqueOuAbsent() {
        MockHttpServletRequest sans = new MockHttpServletRequest();
        assertNull(AuditService.agent(sans));

        MockHttpServletRequest vide = new MockHttpServletRequest();
        vide.addHeader("User-Agent", "   ");
        assertNull(AuditService.agent(vide));

        MockHttpServletRequest normal = new MockHttpServletRequest();
        normal.addHeader("User-Agent", "  Mozilla/5.0 (Windows NT 10.0)  ");
        assertEquals("Mozilla/5.0 (Windows NT 10.0)", AuditService.agent(normal));

        MockHttpServletRequest long_ = new MockHttpServletRequest();
        long_.addHeader("User-Agent", "x".repeat(500));
        assertEquals(300, AuditService.agent(long_).length());
    }

    @Test
    void laRequeteNeGardePasLesParametresSensibles() {
        assertNull(AuditService.requeteNettoyee(null));
        assertEquals("du=2026-01-01&au=2026-12-31", AuditService.requeteNettoyee("du=2026-01-01&token=abc&au=2026-12-31&password=x"));
        assertNull(AuditService.requeteNettoyee("token=abc"));
    }
}
