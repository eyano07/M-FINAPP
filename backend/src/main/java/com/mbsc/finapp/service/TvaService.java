package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.dto.comptabilite.LigneTvaResponse;
import com.mbsc.finapp.dto.comptabilite.TvaSituationResponse;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gestion transparente de la TVA : rapproche la TVA collectée sur les
 * ventes (comptes 443x, créditée à la facturation) et la TVA récupérable
 * sur les achats (comptes 445x, débitée — notamment depuis les notes de
 * frais marquées « soumises à la TVA », voir {@link RegleTresorerieService}).
 *
 * <p>« Transparente » se traduit ici par une piste d'audit systématique :
 * chaque montant agrégé est adossé à la liste des écritures qui le
 * composent, pas seulement à un total.</p>
 */
@Service
@RequiredArgsConstructor
public class TvaService {

    private static final String PREFIXE_COLLECTEE = "443";
    private static final String PREFIXE_RECUPERABLE = "445";

    private final EcritureGrandLivreRepository ecritureRepository;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public TvaSituationResponse situation(LocalDate du, LocalDate au) {
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);
        LocalDate fin = au != null ? au : LocalDate.now();

        List<EcritureGrandLivre> collectees = ecritureRepository.grandLivreParCompte(PREFIXE_COLLECTEE, debut, fin);
        List<EcritureGrandLivre> recuperables = ecritureRepository.grandLivreParCompte(PREFIXE_RECUPERABLE, debut, fin);

        BigDecimal totalCollectee = collectees.stream()
            .map(e -> nz(e.getCredit()).subtract(nz(e.getDebit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRecuperable = recuperables.stream()
            .map(e -> nz(e.getDebit()).subtract(nz(e.getCredit())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LigneTvaResponse> lignes = new ArrayList<>();
        for (EcritureGrandLivre e : collectees) {
            if (nz(e.getCredit()).subtract(nz(e.getDebit())).signum() != 0) {
                lignes.add(LigneTvaResponse.collectee(e));
            }
        }
        for (EcritureGrandLivre e : recuperables) {
            if (nz(e.getDebit()).subtract(nz(e.getCredit())).signum() != 0) {
                lignes.add(LigneTvaResponse.recuperable(e));
            }
        }
        lignes.sort(Comparator.comparing(LigneTvaResponse::date));

        return new TvaSituationResponse(debut, fin, totalCollectee, totalRecuperable,
            totalCollectee.subtract(totalRecuperable), lignes);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
