package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.Vente;
import com.mbsc.finapp.dto.comptabilite.BalanceAgeeResponse;
import com.mbsc.finapp.dto.comptabilite.BalanceAgeeResponse.CreanceAgee;
import com.mbsc.finapp.dto.comptabilite.BalanceAgeeResponse.TrancheAge;
import com.mbsc.finapp.repository.VenteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Balance agee des creances clients.
 *
 * <p>Repond a une question que le grand livre ne sait pas poser : le solde du
 * compte 4111 est global, alors que le recouvrement raisonne par anciennete.
 * Une creance de 30 jours et une de 300 jours ont le meme poids au bilan mais
 * pas du tout le meme risque.</p>
 *
 * <p><b>Source.</b> Les ventes a credit validees et non encore reglees
 * ({@code VenteRepository.creancesOuvertes}) — la meme population que celle
 * reevaluee a la cloture, ce qui garantit que les deux etats parlent des memes
 * creances.</p>
 *
 * <p><b>Devises.</b> Chaque creance garde son montant d'origine, et porte en
 * plus sa contre-valeur en devise de base au taux de sa propre date : c'est la
 * seule maniere d'additionner des creances libellees differemment sans inventer
 * un taux commun qui n'a existe a aucun moment.</p>
 */
@Service
@RequiredArgsConstructor
public class BalanceAgeeService {

    /** Bornes hautes des tranches, en jours. Au-dela de la derniere : « plus de 90 jours ». */
    private static final int[] BORNES = { 30, 60, 90 };
    private static final String[] LIBELLES = {
        "Non échu / 0 à 30 jours", "31 à 60 jours", "61 à 90 jours", "Plus de 90 jours"
    };
    private static final String[] CLES = { "j0_30", "j31_60", "j61_90", "j90_plus" };

    private final VenteRepository venteRepository;
    private final ConversionDeviseService conversionDevise;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public BalanceAgeeResponse calculer(LocalDate au) {
        LocalDate arrete = au != null ? au : LocalDate.now();

        List<CreanceAgee> creances = new ArrayList<>();
        Map<String, BigDecimal> montantParTranche = new LinkedHashMap<>();
        Map<String, Integer> nombreParTranche = new LinkedHashMap<>();
        for (String cle : CLES) {
            montantParTranche.put(cle, BigDecimal.ZERO);
            nombreParTranche.put(cle, 0);
        }
        BigDecimal total = BigDecimal.ZERO;

        for (Vente v : venteRepository.creancesOuvertes()) {
            // Une vente posterieure a la date d'arrete n'est pas encore une
            // creance a cette date : l'exclure permet de rejouer la balance sur
            // une date passee et d'obtenir ce qui etait reellement du alors.
            if (v.getDateVente() != null && v.getDateVente().isAfter(arrete)) {
                continue;
            }
            long jours = v.getDateVente() == null ? 0
                : ChronoUnit.DAYS.between(v.getDateVente(), arrete);
            int index = indexTranche(jours);

            BigDecimal montant = v.getTotalTtc() == null ? BigDecimal.ZERO : v.getTotalTtc();
            BigDecimal montantBase = conversionDevise
                .enDeviseBase(montant, v.getDevise(),
                    conversionDevise.tauxALaDate(v.getDateVente() == null ? arrete : v.getDateVente()))
                .montantBase();

            creances.add(new CreanceAgee(
                v.getId(), v.getReference(),
                v.getClient() != null ? v.getClient().getNom() : v.getClientNom(),
                v.getDateVente(), (int) jours, LIBELLES[index],
                v.getDevise(), montant, montantBase));

            montantParTranche.merge(CLES[index], montantBase, BigDecimal::add);
            nombreParTranche.merge(CLES[index], 1, Integer::sum);
            total = total.add(montantBase);
        }

        List<TrancheAge> tranches = new ArrayList<>();
        for (int i = 0; i < CLES.length; i++) {
            BigDecimal montant = montantParTranche.get(CLES[i]);
            BigDecimal part = total.signum() == 0 ? BigDecimal.ZERO
                : montant.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            tranches.add(new TrancheAge(CLES[i], LIBELLES[i], nombreParTranche.get(CLES[i]), montant, part));
        }

        // Les plus anciennes d'abord : c'est l'ordre dans lequel on relance.
        creances.sort(Comparator.comparingInt(CreanceAgee::joursEcoules).reversed());

        return new BalanceAgeeResponse(arrete, creances.size(), total, tranches, creances);
    }

    private int indexTranche(long jours) {
        for (int i = 0; i < BORNES.length; i++) {
            if (jours <= BORNES[i]) {
                return i;
            }
        }
        return BORNES.length;
    }
}
