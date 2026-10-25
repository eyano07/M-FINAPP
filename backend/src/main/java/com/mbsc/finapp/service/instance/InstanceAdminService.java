package com.mbsc.finapp.service.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.ParametresInstance;
import com.mbsc.finapp.domain.enums.ModeInstance;
import com.mbsc.finapp.dto.instance.CleAppairageResponse;
import com.mbsc.finapp.dto.instance.InstanceAdminResponse;
import com.mbsc.finapp.dto.instance.PingResponse;
import com.mbsc.finapp.dto.instance.ServeurDistantRequest;
import com.mbsc.finapp.dto.instance.TestConnexionResponse;
import com.mbsc.finapp.security.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/**
 * Administration du mode de ce serveur et de l'appairage (ecran Administration › Serveur local et synchronisation).
 * Administrateur seul.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class InstanceAdminService {

    private final InstanceService instance;
    private final ReplicationClient client;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper json;
    private final JournalReplication journal;
    private final ReplicationEmetteur emetteur;

    public InstanceAdminService(InstanceService instance, ReplicationClient client, CurrentUserProvider currentUser,
                                ObjectMapper json, JournalReplication journal, ReplicationEmetteur emetteur) {
        this.emetteur = emetteur;
        this.instance = instance;
        this.client = client;
        this.currentUser = currentUser;
        this.json = json;
        this.journal = journal;
    }

    public InstanceAdminResponse etat() {
        ParametresInstance p = instance.etat();
        return new InstanceAdminResponse(p.getMode().name(), p.getAdresseDistante(), p.getCleFin(), p.getAppairageId(),
            p.getCleCreeeLe(), p.getServeurAssocie(), p.getAppaireLe(), p.getDernierContact(), p.getDerniereSynchro(),
            p.getDerniereErreur(), p.getDerniereErreurLe(), instance.versionSchema(),
            p.getMode() == ModeInstance.LOCAL ? journal.enAttente() : 0, p.getModifiePar(), p.getDateMaj());
    }

    /**
     * Serveur en ligne : genere une nouvelle cle d'appairage (l'ancienne cesse aussitot de valoir). Refuse pendant un
     * appairage actif (copie d'un serveur local) : il faut d'abord reprendre la main.
     */
    public CleAppairageResponse genererCle() {
        exigerMode(ModeInstance.EN_LIGNE, "Une clé d'appairage se génère sur le serveur en ligne, hors appairage actif "
            + "(reprenez d'abord la main si ce serveur est la copie d'un serveur local).");
        SignatureReplication.Cle cle = SignatureReplication.nouvelleCle();
        instance.modifier(p -> {
            p.setCleChiffree(instance.chiffrer(cle.texte()));
            p.setCleFin(cle.fin());
            p.setAppairageId(cle.appairageId());
            p.setCleCreeeLe(Instant.now());
            p.setAdresseDistante(null);
            p.setServeurAssocie(null);
            p.setAppaireLe(null);
            p.setDernierContact(null);
            p.setDerniereErreur(null);
            p.setDerniereErreurLe(null);
        }, auteur());
        return new CleAppairageResponse(cle.texte(), cle.appairageId());
    }

    /**
     * Serveur local (avant activation, ou ancien serveur local a relier de nouveau) : adresse du serveur en ligne et cle
     * d'appairage saisie. Rien n'est envoye : le test de connexion puis l'activation du mode local s'en chargent.
     */
    public InstanceAdminResponse configurerServeurDistant(ServeurDistantRequest req) {
        ParametresInstance actuel = instance.etat();
        if (actuel.getMode() != ModeInstance.EN_LIGNE && actuel.getMode() != ModeInstance.LOCAL_ARRETE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le serveur en ligne ne se change pas pendant que le mode "
                + "local est actif (désactivez-le d'abord).");
        }
        boolean cleVide = req.cle() == null || req.cle().isBlank();
        if (cleVide && actuel.getMode() == ModeInstance.LOCAL_ARRETE) {
            // L'appairage precedent a pris fin (desactivation ou reprise de la main) : son ancienne cle ne vaut plus.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Saisissez la nouvelle clé d'appairage générée sur le "
                + "serveur en ligne : l'ancienne ne vaut plus.");
        }
        // Cle vide : on garde celle deja saisie avec une adresse (pas une cle generee ici comme serveur en ligne).
        boolean garderCle = cleVide && actuel.getCleChiffree() != null && actuel.getAdresseDistante() != null;
        String adresse;
        SignatureReplication.Cle cle;
        try {
            adresse = ReplicationClient.normaliserAdresse(req.adresse());
            cle = garderCle ? null : SignatureReplication.lire(req.cle());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        instance.modifier(p -> {
            p.setAdresseDistante(adresse);
            if (cle != null) {
                p.setCleChiffree(instance.chiffrer(cle.texte()));
                p.setCleFin(cle.fin());
                p.setAppairageId(cle.appairageId());
                p.setCleCreeeLe(Instant.now());
            }
            p.setServeurAssocie(adresse);
            p.setDerniereErreur(null);
            p.setDerniereErreurLe(null);
        }, auteur());
        return etat();
    }

    /** Serveur local : essaie de joindre le serveur en ligne avec la cle saisie et compare les versions. */
    public TestConnexionResponse tester() {
        ParametresInstance p = instance.etat();
        int versionLocale = instance.versionSchema();
        if (p.getAdresseDistante() == null || p.getCleChiffree() == null) {
            return new TestConnexionResponse(false, "Renseignez d'abord l'adresse du serveur en ligne et la clé d'appairage.",
                null, versionLocale, null, null);
        }
        long avant = System.currentTimeMillis();
        try {
            ReplicationClient.Reponse r = client.envoyer("GET", "/replication/ping", null, null, Duration.ofSeconds(20));
            if (!r.ok()) {
                return new TestConnexionResponse(false, "Le serveur en ligne refuse la connexion (" + r.statut() + ") : "
                    + messageRefus(r), null, versionLocale, null, null);
            }
            PingResponse ping = json.readValue(r.corps(), PingResponse.class);
            long ecart = ping.heure() - (avant + System.currentTimeMillis()) / 2;
            if (ping.versionSchema() != versionLocale) {
                return new TestConnexionResponse(false, "Versions différentes : serveur en ligne en V" + ping.versionSchema()
                    + ", serveur local en V" + versionLocale + ". Mettez les deux serveurs à jour avec la même livraison.",
                    ping.versionSchema(), versionLocale, ping.mode(), ecart);
            }
            return new TestConnexionResponse(true, "Connexion réussie : clé acceptée, même version (V" + versionLocale + ").",
                ping.versionSchema(), versionLocale, ping.mode(), ecart);
        } catch (ReplicationClient.Injoignable e) {
            return new TestConnexionResponse(false, e.getMessage(), null, versionLocale, null, null);
        } catch (Exception e) {
            return new TestConnexionResponse(false, "Réponse inattendue du serveur en ligne : " + e.getMessage(), null,
                versionLocale, null, null);
        }
    }

    /** Serveur local : « Synchroniser maintenant » (avec controle des empreintes). */
    public ReplicationEmetteur.Bilan synchroniser() {
        exigerMode(ModeInstance.LOCAL, "Ce serveur n'est pas en mode local.");
        return emetteur.synchroniser(true);
    }

    /** Serveur local : resynchronisation complete du serveur en ligne (ses donnees recopiees sont remplacees). */
    public String resynchroniser() {
        exigerMode(ModeInstance.LOCAL, "Ce serveur n'est pas en mode local.");
        try {
            int fichiers = emetteur.resynchroniser();
            instance.noterSucces();
            return "Resynchronisation complète terminée (" + fichiers + " fichier(s) envoyé(s)).";
        } catch (java.io.IOException | RuntimeException e) {
            instance.noterErreur("Resynchronisation complète interrompue : " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Resynchronisation complète interrompue : " + e.getMessage());
        }
    }

    /** Message d'un refus du serveur en ligne (champ « message » du JSON, sinon le texte brut). */
    String messageRefus(ReplicationClient.Reponse r) {
        try {
            var noeud = json.readTree(r.corps());
            if (noeud.hasNonNull("message")) return noeud.get("message").asText();
        } catch (Exception ignore) {
            // corps non JSON (proxy) : texte brut
        }
        String t = r.texte();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private void exigerMode(ModeInstance attendu, String message) {
        if (instance.mode() != attendu) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private String auteur() {
        return currentUser.requireUser().getEmail();
    }
}
