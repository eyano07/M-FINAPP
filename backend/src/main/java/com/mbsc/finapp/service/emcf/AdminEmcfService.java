package com.mbsc.finapp.service.emcf;

import com.mbsc.finapp.domain.FactureNormalisee.Statut;
import com.mbsc.finapp.domain.GroupeTaxeDgi;
import com.mbsc.finapp.domain.ParametresEmcf;
import com.mbsc.finapp.dto.emcf.EmcfEnregistrerRequest;
import com.mbsc.finapp.dto.emcf.EmcfEtatResponse;
import com.mbsc.finapp.dto.emcf.EmcfTestResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.FactureNormaliseeRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Administration › Facture normalisée : dispositif e-MCF, jeton, groupes de taxation, test. Administrateur seul. */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminEmcfService {

    private final ParametresEmcfService parametres;
    private final FactureNormaliseeRepository factures;
    private final EmcfSimulateur simulateur;
    private final EmcfHttpClient http;
    private final CurrentUserProvider currentUser;

    public EmcfEtatResponse etat() {
        ConfigEmcf c = parametres.config();
        ParametresEmcf p = parametres.ligne();
        List<EmcfEtatResponse.Groupe> groupes = parametres.groupes().stream()
            .map(g -> new EmcfEtatResponse.Groupe(g.getCode(), g.getLibelle(), g.getTaux(), g.isActif(), g.getOrdre())).toList();
        return new EmcfEtatResponse(c.actif(), c.mode(), c.urlBase(), StringUtils.hasText(c.jeton()),
            p == null ? null : p.getJetonFin(), c.numeroDef(), c.delaiMs(), c.utilisable(),
            factures.countByStatut(Statut.EN_ATTENTE), factures.countByStatut(Statut.REJETEE), groupes);
    }

    public EmcfEtatResponse enregistrer(EmcfEnregistrerRequest req) {
        String mode = req.mode() == null ? "SIMULATION" : req.mode();
        String url = StringUtils.hasText(req.urlBase()) ? req.urlBase().strip() : null;
        if (url != null && !url.matches("(?i)https?://[^\\s]+")) {
            throw new TransitionInvalideException("L'adresse du e-MCF doit commencer par http:// ou https://.");
        }
        if (req.actif() && !"SIMULATION".equals(mode) && url == null) {
            throw new TransitionInvalideException("Renseignez l'adresse du e-MCF pour activer le mode " + mode + ".");
        }
        if (req.groupes() != null) validerGroupes(req.groupes());
        String jeton = StringUtils.hasText(req.jeton()) ? req.jeton().strip() : null;
        parametres.modifier(p -> {
            p.setActif(req.actif());
            p.setMode(mode);
            p.setUrlBase(url);
            p.setNumeroDef(StringUtils.hasText(req.numeroDef()) ? req.numeroDef().strip() : null);
            p.setDelaiMs(req.delaiMs() == null ? 10000 : req.delaiMs());
            if (jeton != null) {
                p.setJetonChiffre(parametres.chiffrer(jeton));
                p.setJetonFin(ParametresEmcfService.fin(jeton));
            }
        }, currentUser.requireUser());
        if (req.groupes() != null) {
            List<GroupeTaxeDgi> liste = new ArrayList<>();
            int ordre = 1;
            for (EmcfEnregistrerRequest.Groupe g : req.groupes()) {
                liste.add(GroupeTaxeDgi.builder().code(g.code()).libelle(g.libelle().strip()).taux(g.taux())
                    .actif(g.actif()).ordre(ordre++).build());
            }
            parametres.enregistrerGroupes(liste);
        }
        return etat();
    }

    private static void validerGroupes(List<EmcfEnregistrerRequest.Groupe> groupes) {
        long distincts = groupes.stream().map(EmcfEnregistrerRequest.Groupe::code).distinct().count();
        if (distincts != groupes.size()) throw new TransitionInvalideException("Deux groupes portent le même code.");
        for (String requis : List.of("A", "B")) {
            if (groupes.stream().noneMatch(g -> g.code().equals(requis) && g.actif())) {
                throw new TransitionInvalideException("Le groupe « " + requis + " » est indispensable et doit rester actif.");
            }
        }
    }

    public EmcfEtatResponse retirerJeton() {
        parametres.modifier(p -> {
            p.setJetonChiffre(null);
            p.setJetonFin(null);
        }, currentUser.requireUser());
        return etat();
    }

    public EmcfTestResponse tester() {
        ConfigEmcf c = parametres.config();
        try {
            EmcfClient client = c.simulation() ? simulateur : http;
            return new EmcfTestResponse(true, client.tester(c));
        } catch (ErreurEmcf e) {
            return new EmcfTestResponse(false, e.getMessage());
        }
    }
}
