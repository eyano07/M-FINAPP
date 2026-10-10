package com.mbsc.finapp.service.emcf;

import com.mbsc.finapp.domain.Client;
import com.mbsc.finapp.domain.FactureNormalisee;
import com.mbsc.finapp.domain.GroupeTaxeDgi;
import com.mbsc.finapp.domain.LigneVente;
import com.mbsc.finapp.domain.ParametresEntreprise;
import com.mbsc.finapp.domain.Vente;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vente → demande de certification : groupe de taxation de chaque ligne, totaux par groupe, identité de l'émetteur
 * et du client. Contrôle aussi les mentions sans lesquelles le dispositif refuserait la facture (NIF).
 */
@Component
public class FactureNormaliseeMapper {

    public static final String GROUPE_EXONERE = "A";
    public static final String GROUPE_TAXABLE = "B";

    /** Groupe d'une ligne : celui fixé à la vente, sinon B si l'article est soumis à la TVA, A sinon. */
    public static String groupe(LigneVente l) {
        if (StringUtils.hasText(l.getGroupeTaxe())) return l.getGroupeTaxe().strip().toUpperCase();
        return l.isSoumisTva() ? GROUPE_TAXABLE : GROUPE_EXONERE;
    }

    public EmcfDemande construire(FactureNormalisee f, ParametresEntreprise e, List<GroupeTaxeDgi> groupes) {
        Vente v = f.getVente();
        boolean avoir = f.getType() == FactureNormalisee.Type.AVOIR;
        Map<String, GroupeTaxeDgi> parCode = groupes.stream().collect(Collectors.toMap(GroupeTaxeDgi::getCode, Function.identity(), (a, b) -> a));

        if (!StringUtils.hasText(e.getNif())) {
            throw new ErreurEmcf("NIF de l'entreprise manquant : renseignez-le dans Administration › Paramètres.", false, null);
        }
        Client c = v.getClient();
        String typeClient = c == null ? "PARTICULIER" : c.getTypeClient();
        String nifClient = c == null ? null : c.getNif();
        if (!"PARTICULIER".equals(typeClient) && !StringUtils.hasText(nifClient)) {
            throw new ErreurEmcf("NIF du client « " + v.designationClient() + " » manquant : renseignez-le sur sa fiche client.", false, null);
        }

        List<EmcfDemande.Ligne> lignes = new ArrayList<>();
        Map<String, BigDecimal[]> totaux = new LinkedHashMap<>();
        for (LigneVente l : v.getLignes()) {
            String g = groupe(l);
            if (!parCode.containsKey(g) || !parCode.get(g).isActif()) {
                throw new ErreurEmcf("Groupe de taxation « " + g + " » inconnu ou inactif (article « " + l.getDesignation() + " »).", false, null);
            }
            BigDecimal ht = l.getMontantHt();
            BigDecimal tva = l.getMontantTva();
            lignes.add(new EmcfDemande.Ligne(l.getDesignation(), l.getQuantite(), l.getPrixUnitaire(), g, ht, tva, ht.add(tva)));
            BigDecimal[] t = totaux.computeIfAbsent(g, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(ht);
            t[1] = t[1].add(tva);
        }
        List<EmcfDemande.TotalGroupe> parGroupe = new ArrayList<>();
        totaux.forEach((g, t) -> parGroupe.add(new EmcfDemande.TotalGroupe(g, parCode.get(g).getTaux(), t[0], t[1], t[0].add(t[1]))));

        return new EmcfDemande(
            avoir ? "FA" : "FV",
            v.getReference(),
            Instant.now(),
            v.getDevise().name(),
            new EmcfDemande.Emetteur(e.getNom(), e.getNif(), e.getRccm(), e.getIdNat(), e.getAdresse()),
            new EmcfDemande.Client(v.designationClient(), nifClient, typeClient),
            avoir && f.getOrigine() != null ? f.getOrigine().getUid() : null,
            v.getModeReglement().name(),
            lignes, parGroupe, v.getTotalHt(), v.getTotalTva(), v.getTotalTtc());
    }
}
