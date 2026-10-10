package com.mbsc.finapp.service.emcf;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Dispositif factice local : sert aux essais et à la formation. Ses factures n'ont AUCUNE valeur fiscale. */
@Component
public class EmcfSimulateur implements EmcfClient {

    @Override
    public EmcfReponse certifier(ConfigEmcf config, EmcfDemande d, String json) {
        String uid = "SIM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String def = config.numeroDef() == null || config.numeroDef().isBlank() ? "SIMULATION" : config.numeroDef();
        Instant maintenant = Instant.now();
        String signature = signer(uid + "|" + d.reference() + "|" + d.totalTtc().toPlainString());
        String qr = "DGI-RDC-SIMULATION|" + uid + "|" + def + "|" + d.totalTtc().toPlainString() + " " + d.devise()
            + "|" + maintenant;
        return new EmcfReponse(uid, signature, def, maintenant, qr, "{\"simulation\":true,\"uid\":\"" + uid + "\"}");
    }

    @Override
    public String tester(ConfigEmcf config) {
        return "Mode simulation : le dispositif factice répond. Les factures émises n'ont aucune valeur fiscale.";
    }

    private static String signer(String contenu) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("simulation-emcf".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(contenu.getBytes(StandardCharsets.UTF_8)));
            return b64.substring(0, 24).toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
