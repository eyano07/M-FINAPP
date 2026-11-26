package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.enums.OrganismePaie;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.dto.drh.DeclarationSocialeResponse;
import com.mbsc.finapp.dto.drh.DeclarationSocialeResponse.LigneAgent;
import com.mbsc.finapp.dto.drh.DeclarationSocialeResponse.LigneOrganisme;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Bordereaux recapitulatifs des declarations sociales et fiscales sur salaires
 * (CNSS, ONEM, INPP, IPR) pour une periode de paie.
 *
 * <p>Le module DRH calculait deja ces montants bulletin par bulletin, mais
 * n'en produisait aucune synthese : chaque declaration mensuelle imposait une
 * ressaisie manuelle a partir des bulletins un a un — un travail long et,
 * surtout, non reproductible. Ce service agrege ce qui existe deja ; il ne
 * recalcule rien et ne peut donc pas diverger des bulletins.</p>
 *
 * <p><b>Perimetre.</b> Seuls les bulletins VALIDE (donc arretes par le
 * responsable paie) entrent dans le bordereau. Un bulletin encore BROUILLON
 * est une saisie en cours, un bulletin ANNULE ne doit rien a personne — les
 * inclure ferait declarer des montants qui ne seront jamais payes.</p>
 */
@Service
@RequiredArgsConstructor
public class DeclarationSocialeService {

    private final BulletinPaieRepository repository;

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public DeclarationSocialeResponse etablir(int mois, int annee) {
        List<BulletinPaie> bulletins = repository.findByPeriode(mois, annee).stream()
            .filter(b -> b.getStatut() == StatutBulletin.VALIDE)
            .toList();

        BigDecimal brut = BigDecimal.ZERO, baseCotisable = BigDecimal.ZERO, baseInpp = BigDecimal.ZERO;
        BigDecimal baseIpr = BigDecimal.ZERO, net = BigDecimal.ZERO;
        BigDecimal cnssOuv = BigDecimal.ZERO, cnssPat = BigDecimal.ZERO;
        BigDecimal onem = BigDecimal.ZERO, inpp = BigDecimal.ZERO, ipr = BigDecimal.ZERO;

        List<LigneAgent> agents = new ArrayList<>();
        for (BulletinPaie b : bulletins) {
            brut = brut.add(nz(b.getSalaireBrut()));
            baseCotisable = baseCotisable.add(nz(b.getBaseImposableInss()));
            baseInpp = baseInpp.add(nz(b.getBaseImposableInpp()));
            baseIpr = baseIpr.add(nz(b.getBaseImposableIpr()));
            net = net.add(nz(b.getSalaireNet()));
            cnssOuv = cnssOuv.add(nz(b.getCnssOuvriere()));
            cnssPat = cnssPat.add(nz(b.getCnssPatronale()));
            onem = onem.add(nz(b.getOnem()));
            inpp = inpp.add(nz(b.getInpp()));
            ipr = ipr.add(nz(b.getIpr()));

            agents.add(new LigneAgent(
                b.getEmploye().getMatricule(),
                b.getEmploye().getNomComplet(),
                b.getEmploye().getCategorie(),
                nz(b.getSalaireBrut()), nz(b.getBaseImposableInss()),
                nz(b.getCnssOuvriere()), nz(b.getCnssPatronale()),
                nz(b.getOnem()), nz(b.getInpp()), nz(b.getIpr()),
                nz(b.getSalaireNet())));
        }

        // Les comptes cites sont ceux ou l'ecriture de cloture de paie porte la
        // dette (voir PaieComptabilisationService) : le bordereau et le grand
        // livre se rapprochent ainsi ligne a ligne.
        // Depuis le 16/11/2026 (audit DRH du 15/11, D-07 et D-08) : CNSS par branche (4311 prestations familiales,
        // 4312 risques professionnels, 4313 retraite), ONEM et INPP sous 4428 ; base cotisable = appointements + gains
        // imposables, commune à la CNSS, l'ONEM et l'INPP.
        List<LigneOrganisme> organismes = List.of(
            new LigneOrganisme("CNSS", "Base cotisable (appointements + gains imposables)",
                baseCotisable, cnssOuv, cnssPat, cnssOuv.add(cnssPat), OrganismePaie.CNSS.compte()),
            new LigneOrganisme("ONEM", "Base cotisable",
                baseCotisable, BigDecimal.ZERO, onem, onem, OrganismePaie.ONEM.compte()),
            new LigneOrganisme("INPP", "Base cotisable (taux selon l'effectif)",
                baseInpp, BigDecimal.ZERO, inpp, inpp, OrganismePaie.INPP.compte()),
            new LigneOrganisme("IPR — Direction Générale des Impôts", "Base imposable IPR",
                baseIpr, ipr, BigDecimal.ZERO, ipr, "4472"));

        return new DeclarationSocialeResponse(
            mois, annee, bulletins.size(),
            "Bulletins VALIDÉS de la période",
            brut, baseCotisable, net, organismes, agents);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
