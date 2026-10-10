package com.mbsc.finapp.service.emcf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format d'échange avec le dispositif DGI. <strong>Seul fichier à ajuster à la spécification technique officielle</strong>
 * (noms des champs, codes de type de facture, format de date) : tout le reste de l'intégration manipule
 * {@link EmcfDemande} et {@link EmcfReponse}. Les noms ci-dessous sont provisoires, en attendant la spécification
 * publiée sur le portail développeur de la DGI.
 */
public final class EmcfJson {

    private EmcfJson() {}

    public static String versJson(ObjectMapper mapper, EmcfDemande d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.type());
        m.put("reference", d.reference());
        m.put("dateHeure", d.dateHeure().toString());
        m.put("devise", d.devise());
        m.put("emetteur", Map.of(
            "nom", nn(d.emetteur().nom()), "nif", nn(d.emetteur().nif()), "rccm", nn(d.emetteur().rccm()),
            "idNat", nn(d.emetteur().idNat()), "adresse", nn(d.emetteur().adresse())));
        m.put("client", Map.of("nom", nn(d.client().nom()), "nif", nn(d.client().nif()), "type", nn(d.client().type())));
        if (d.origineUid() != null) m.put("origineUid", d.origineUid());
        m.put("reglement", d.modeReglement());
        List<Map<String, Object>> lignes = new ArrayList<>();
        for (EmcfDemande.Ligne l : d.lignes()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("designation", l.designation());
            o.put("quantite", l.quantite());
            o.put("prixUnitaire", l.prixUnitaire());
            o.put("groupe", l.groupe());
            o.put("montantHt", l.montantHt());
            o.put("montantTva", l.montantTva());
            o.put("montantTtc", l.montantTtc());
            lignes.add(o);
        }
        m.put("lignes", lignes);
        List<Map<String, Object>> groupes = new ArrayList<>();
        for (EmcfDemande.TotalGroupe g : d.parGroupe()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("groupe", g.groupe());
            o.put("taux", g.taux());
            o.put("ht", g.ht());
            o.put("tva", g.tva());
            o.put("ttc", g.ttc());
            groupes.add(o);
        }
        m.put("totaux", Map.of("ht", d.totalHt(), "tva", d.totalTva(), "ttc", d.totalTtc(), "parGroupe", groupes));
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("Sérialisation de la facture impossible : " + e.getMessage(), e);
        }
    }

    /** Lecture tolérante de la réponse : plusieurs noms de champ usuels sont acceptés. */
    public static EmcfReponse depuisJson(ObjectMapper mapper, String brut) {
        try {
            JsonNode n = mapper.readTree(brut);
            String uid = texte(n, "uid", "id", "invoiceUid", "invoiceId");
            String signature = texte(n, "signature", "securityCode", "codeSecurite", "fiscalSignature");
            String def = texte(n, "numeroDef", "def", "nim", "deviceId");
            String qr = texte(n, "codeQr", "qrCode", "qr", "qr_code");
            String date = texte(n, "dateFiscale", "dateHeure", "dateTime", "date");
            if (uid == null || signature == null) {
                throw new ErreurEmcf("Réponse du dispositif incomplète (UID ou signature absents).", false, null);
            }
            Instant instant;
            try {
                instant = date == null ? Instant.now() : Instant.parse(date);
            } catch (Exception e) {
                instant = Instant.now();
            }
            return new EmcfReponse(uid, signature, def, instant, qr, brut);
        } catch (ErreurEmcf e) {
            throw e;
        } catch (Exception e) {
            throw new ErreurEmcf("Réponse du dispositif illisible : " + e.getMessage(), false, e);
        }
    }

    private static String texte(JsonNode n, String... noms) {
        for (String nom : noms) {
            JsonNode v = n.get(nom);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }
}
