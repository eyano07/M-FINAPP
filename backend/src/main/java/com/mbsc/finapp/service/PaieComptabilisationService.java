package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Construit les écritures comptables d'un bulletin de paie clôturé — une
 * pièce BROUILLON par bulletin (un employé = une pièce), voir
 * {@code BulletinPaieService.cloturer} et
 * {@code ComptabiliteService.creerPieceInterneBrouillon}.
 *
 * <p>Mapping des comptes OHADA (numéros vérifiés actifs dans le plan
 * comptable au moment de la conception de ce module) :</p>
 * <ul>
 *   <li>Débit — charges : 6611/6621 (appointements, national/non national),
 *       6631 (logement), 6638 (transport), 6613 (congé), 6618 (heures
 *       supplémentaires), 6616 (allocation familiale), 6612 (primes),
 *       6641/6642 (charges sociales patronales, national/non national)</li>
 *   <li>Crédit — passif : 431.1 (CNSS ouvrière + patronale), 4472 (IPR),
 *       4478.1 (ONEM), 4478.2 (INPP), 4211 (avance sur salaire — suppose que
 *       le décaissement initial a déjà débité ce même compte via une pièce
 *       Caisse), 2762 (prêt, même hypothèse), 4221.1 (net à payer)</li>
 * </ul>
 *
 * <p>L'équilibre découle directement de la formule de calcul : en substituant
 * {@code net} et {@code H} dans le total crédit, chaque terme (cnssOuvriere,
 * ipr, avanceSalaire, pret) s'annule contre sa propre ligne de crédit
 * explicite ; il reste H + primes/indemnités + charges patronales des deux
 * côtés, identique au total débit — ce n'est pas juste supposé équilibré,
 * {@code ComptabiliteService.creerPieceInterneBrouillon} le revérifie de
 * toute façon avant d'enregistrer quoi que ce soit.</p>
 */
@Service
@RequiredArgsConstructor
public class PaieComptabilisationService {

    private final CompteOHADARepository compteRepository;

    public List<EcritureGrandLivre> construireLignes(BulletinPaie b) {
        boolean expatrie = b.getEmploye().isExpatrie();
        String libelle = "Paie " + String.format(Locale.FRENCH, "%02d/%d", b.getMois(), b.getAnnee())
            + " — " + b.getEmploye().getNomComplet();

        List<EcritureGrandLivre> lignes = new ArrayList<>();

        // --- Débit : charges -------------------------------------------
        debit(lignes, expatrie ? "6621" : "6611", b.getBaseImposableInss(), libelle, b);
        debit(lignes, "6631", b.getIndemniteLogement(), libelle, b);
        debit(lignes, "6638", b.getIndemniteTransport(), libelle, b);
        debit(lignes, "6613", b.getConge(), libelle, b);
        debit(lignes, "6618", b.getHeuresSupplementaires(), libelle, b);
        debit(lignes, "6616", b.getAllocationFamiliale(), libelle, b);
        BigDecimal primes = nz(b.getPrimeDiplome()).add(nz(b.getPrimeAnciennete())).add(nz(b.getPrimeRendement()));
        debit(lignes, "6612", primes, libelle, b);
        BigDecimal chargesPatronales = nz(b.getCnssPatronale()).add(nz(b.getOnem())).add(nz(b.getInpp()));
        debit(lignes, expatrie ? "6642" : "6641", chargesPatronales, libelle, b);

        // --- Crédit : passif ---------------------------------------------
        BigDecimal cnssTotal = nz(b.getCnssOuvriere()).add(nz(b.getCnssPatronale()));
        credit(lignes, "431.1", cnssTotal, libelle, b);
        credit(lignes, "4472", b.getIpr(), libelle, b);
        credit(lignes, "4478.1", b.getOnem(), libelle, b);
        credit(lignes, "4478.2", b.getInpp(), libelle, b);
        credit(lignes, "4211", b.getAvanceSalaire(), libelle, b);
        credit(lignes, "2762", b.getPret(), libelle, b);
        credit(lignes, "4221.1", b.getSalaireNet(), libelle, b);

        return lignes;
    }

    private void debit(List<EcritureGrandLivre> lignes, String numeroCompte, BigDecimal montant, String libelle,
                        BulletinPaie b) {
        if (montant == null || montant.signum() <= 0) {
            return;
        }
        lignes.add(EcritureGrandLivre.builder()
            .compte(compte(numeroCompte))
            .debit(montant)
            .credit(BigDecimal.ZERO)
            .libelle(libelle)
            .build());
    }

    private void credit(List<EcritureGrandLivre> lignes, String numeroCompte, BigDecimal montant, String libelle,
                         BulletinPaie b) {
        if (montant == null || montant.signum() <= 0) {
            return;
        }
        lignes.add(EcritureGrandLivre.builder()
            .compte(compte(numeroCompte))
            .debit(BigDecimal.ZERO)
            .credit(montant)
            .libelle(libelle)
            .build());
    }

    private CompteOHADA compte(String numero) {
        return compteRepository.findByNumero(numero)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Compte OHADA " + numero + " introuvable — la migration DRH ne l'a pas créé "
                + "ou a été modifiée après coup."));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
