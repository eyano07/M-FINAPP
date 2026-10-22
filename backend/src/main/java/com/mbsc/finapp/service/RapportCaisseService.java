package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.TransactionCaisse;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.PeriodeRapport;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.dto.caisse.RapportCaisseResponse;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Rapport de caisse du jour, de la semaine (lundi a dimanche) ou du mois : solde d'ouverture, chaque entree et chaque
 * sortie d'argent de la periode avec sa contrepartie, resume par jour et par nature, solde de cloture.
 *
 * <p>Il est tire du compte de caisse du grand livre (571 et sous-comptes), comme le journal de caisse : tout ce qui a
 * mouvemente la caisse y figure, quelle qu'en soit l'origine (operation de caisse, paiement d'une note, vente, retour de
 * fonds, import, piece manuelle), et le solde de cloture est celui du grand livre. Les montants sont convertis dans la
 * devise demandee par {@link RapportCaisseCalcul}.</p>
 */
@Service
@RequiredArgsConstructor
public class RapportCaisseService {

    /** Au plus ce nombre de comptes de contrepartie secondaires sont nommes ; au-dela, « ... ». */
    private static final int AUTRES_COMPTES_MAX = 3;

    private final EcritureGrandLivreRepository ecritureRepository;
    private final ConversionDeviseService conversionDevise;

    @PreAuthorize("hasAnyRole('CAISSIER', 'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public RapportCaisseResponse rapport(PeriodeRapport periode, LocalDate date, Devise devise) {
        PeriodeRapport p = periode == null ? PeriodeRapport.JOUR : periode;
        LocalDate reference = date == null ? LocalDate.now() : date;
        PeriodeRapport.Bornes bornes = p.bornes(reference);
        ConversionDeviseService.Affichage affichage = conversionDevise.affichage(devise, bornes.au());

        Object[] anterieur = ecritureRepository.soldeAnterieur(EcritureComptableService.COMPTE_CAISSE, bornes.du());
        // Selon la version de Spring Data, un agregat mono-ligne arrive sous forme de tableau imbrique : on normalise.
        if (anterieur != null && anterieur.length == 1 && anterieur[0] instanceof Object[] interieur) {
            anterieur = interieur;
        }
        BigDecimal ouverture = anterieur == null || anterieur.length < 2 ? BigDecimal.ZERO
            : nz(anterieur[0]).subtract(nz(anterieur[1]));

        List<EcritureGrandLivre> lignes = ecritureRepository.mouvementsDeCaisse(
            EcritureComptableService.COMPTE_CAISSE, bornes.du(), bornes.au());
        return RapportCaisseCalcul.construire(p, reference, bornes, affichage, ouverture, mouvements(lignes));
    }

    /** Les lignes de la caisse, chacune avec la contrepartie tiree des autres lignes de sa piece (ou de son operation). */
    private List<RapportCaisseCalcul.MouvementBrut> mouvements(List<EcritureGrandLivre> lignes) {
        Set<Long> pieces = new TreeSet<>();
        Set<Long> operations = new TreeSet<>();
        for (EcritureGrandLivre e : lignes) {
            if (e.getPiece() != null) {
                pieces.add(e.getPiece().getId());
            } else if (e.getTransaction() != null) {
                operations.add(e.getTransaction().getId());
            }
        }
        Map<Long, List<EcritureGrandLivre>> parPiece = pieces.isEmpty() ? Map.of()
            : ecritureRepository.lignesDesPieces(pieces).stream().collect(Collectors.groupingBy(e -> e.getPiece().getId()));
        Map<Long, List<EcritureGrandLivre>> parOperation = operations.isEmpty() ? Map.of()
            : ecritureRepository.lignesDesTransactions(operations).stream()
                .collect(Collectors.groupingBy(e -> e.getTransaction().getId()));

        List<RapportCaisseCalcul.MouvementBrut> resultat = new ArrayList<>();
        for (EcritureGrandLivre e : lignes) {
            List<EcritureGrandLivre> groupe = e.getPiece() != null ? parPiece.getOrDefault(e.getPiece().getId(), List.of())
                : e.getTransaction() != null ? parOperation.getOrDefault(e.getTransaction().getId(), List.of()) : List.of();
            resultat.add(mouvement(e, groupe));
        }
        return resultat;
    }

    private static RapportCaisseCalcul.MouvementBrut mouvement(EcritureGrandLivre e, List<EcritureGrandLivre> groupe) {
        PieceComptable piece = e.getPiece();
        TransactionCaisse operation = e.getTransaction();

        // Contrepartie : les autres lignes du groupe ; la principale est la plus grosse (a egalite, le plus petit numero).
        List<EcritureGrandLivre> autres = groupe.stream()
            .filter(x -> !x.getId().equals(e.getId()) && x.getCompte() != null)
            .toList();
        EcritureGrandLivre principale = autres.stream()
            .max(Comparator.comparing((EcritureGrandLivre x) -> nz(x.getDebit()).add(nz(x.getCredit())))
                .thenComparing((EcritureGrandLivre x) -> x.getCompte().getNumero(), Comparator.reverseOrder()))
            .orElse(null);
        String autresComptes = null;
        if (principale != null) {
            List<String> numeros = autres.stream().map(x -> x.getCompte().getNumero())
                .filter(n -> !n.equals(principale.getCompte().getNumero()))
                .distinct().sorted().toList();
            autresComptes = numeros.isEmpty() ? null
                : String.join(", ", numeros.subList(0, Math.min(numeros.size(), AUTRES_COMPTES_MAX)))
                    + (numeros.size() > AUTRES_COMPTES_MAX ? ", ..." : "");
        }

        String libelle = e.getLibelle() != null ? e.getLibelle()
            : piece != null && piece.getLibelle() != null ? piece.getLibelle()
            : operation != null ? operation.getLibelle() : null;
        String reference = piece != null ? piece.getReference() : operation != null ? operation.getReference() : null;
        return new RapportCaisseCalcul.MouvementBrut(
            e.getDateEcriture(),
            operation == null ? null : operation.getDateOperation(),
            reference,
            operation == null ? null : operation.getNumeroRecu(),
            libelle,
            principale == null ? null : principale.getCompte().getNumero(),
            principale == null ? null : principale.getCompte().getLibelle(),
            autresComptes,
            operation == null ? null : nom(operation.getCaissier()),
            piece != null && piece.getStatut() == StatutPiece.ANNULEE,
            e.getDebit(),
            e.getCredit());
    }

    private static String nom(User u) {
        if (u == null) {
            return null;
        }
        String n = ((u.getPrenom() == null ? "" : u.getPrenom()) + " " + (u.getNom() == null ? "" : u.getNom())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nz(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }
}
