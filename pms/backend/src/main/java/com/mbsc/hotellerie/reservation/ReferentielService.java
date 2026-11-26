package com.mbsc.hotellerie.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mbsc.hotellerie.finapp.FinappClient;
import com.mbsc.hotellerie.securite.Session;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Référentiel de M-FINAPP utile à l'encaissement (banques, opérateurs mobile money, entrepôts, articles vendables, taux
 * de TVA, devise) : lu au nom de l'utilisateur, gardé une minute.
 */
@Service
public class ReferentielService {

    private final FinappClient finapp;
    private volatile JsonNode cache;
    private volatile long expire;

    public ReferentielService(FinappClient finapp) {
        this.finapp = finapp;
    }

    public JsonNode referentiel(Session session) {
        long maintenant = System.currentTimeMillis();
        JsonNode r = cache;
        if (r == null || expire < maintenant) {
            r = finapp.signe("GET", "/pms/referentiel", null, session.jeton());
            cache = r;
            expire = maintenant + 60_000;
        }
        return r;
    }

    public BigDecimal tauxTva(Session session) {
        JsonNode t = referentiel(session).path("tauxTva");
        return t.isMissingNode() || t.isNull() ? BigDecimal.ZERO : t.decimalValue();
    }

    /** L'article est-il soumis à la TVA ? (vrai par défaut, comme dans M-FINAPP) */
    public boolean soumisTva(Session session, Long articleId) {
        if (articleId == null) return true;
        for (JsonNode a : referentiel(session).path("articles")) {
            if (a.path("id").asLong() == articleId) return a.path("soumisTva").asBoolean(true);
        }
        return true;
    }
}
