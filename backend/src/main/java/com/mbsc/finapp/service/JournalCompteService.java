package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.StatutPiece;
import com.mbsc.finapp.dto.comptabilite.JournalCompteResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Journal d'un compte, au choix dans tout le plan comptable : chaque mouvement de la periode avec sa note de frais, son
 * objet, son beneficiaire, son compte de contrepartie et le solde cumule depuis le solde d'ouverture — le format de
 * l'annexe « Journal de caisse » remise a la direction, pour n'importe quel compte (caisse, banque, fournisseur,
 * charge...). Un compte inclut ses sous-comptes (571 : 5711, 5712...). Calcul : {@link JournalCompteCalcul}.
 */
@Service
@RequiredArgsConstructor
public class JournalCompteService {

    /** Au plus ce nombre de comptes de contrepartie sont nommes ; au-dela, « ... ». */
    private static final int COMPTES_MAX = 3;

    private final EcritureGrandLivreRepository ecritureRepository;
    private final CompteOHADARepository compteRepository;
    private final ConversionDeviseService conversionDevise;

    @PreAuthorize("hasAnyRole('COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public JournalCompteResponse journal(String numero, LocalDate du, LocalDate au, Devise devise) {
        if (numero == null || numero.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choisissez un compte.");
        }
        CompteOHADA choisi = compteRepository.findByNumero(numero.strip())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Le compte " + numero.strip() + " n'existe pas dans le plan comptable."));
        String compte = choisi.getNumero();
        LocalDate fin = au == null ? LocalDate.now() : au;
        LocalDate debut = du == null ? fin.withDayOfMonth(1) : du;
        if (debut.isAfter(fin)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La date de debut est posterieure a la date de fin.");
        }
        ConversionDeviseService.Affichage affichage = conversionDevise.affichage(devise, fin);

        Object[] anterieur = ecritureRepository.soldeAnterieur(compte, debut);
        // Selon la version de Spring Data, un agregat mono-ligne arrive sous forme de tableau imbrique : on normalise.
        if (anterieur != null && anterieur.length == 1 && anterieur[0] instanceof Object[] interieur) {
            anterieur = interieur;
        }
        BigDecimal ouverture = anterieur == null || anterieur.length < 2 ? BigDecimal.ZERO
            : nz(anterieur[0]).subtract(nz(anterieur[1]));

        List<EcritureGrandLivre> lignes = ecritureRepository.mouvementsDuCompte(compte, debut, fin);
        return JournalCompteCalcul.construire(compte, choisi.getLibelle(), debut, fin, affichage, ouverture,
            mouvements(lignes, compte));
    }

    private List<JournalCompteCalcul.MouvementBrut> mouvements(List<EcritureGrandLivre> lignes, String compte) {
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

        List<JournalCompteCalcul.MouvementBrut> resultat = new ArrayList<>();
        for (EcritureGrandLivre e : lignes) {
            List<EcritureGrandLivre> groupe = e.getPiece() != null ? parPiece.getOrDefault(e.getPiece().getId(), List.of())
                : e.getTransaction() != null ? parOperation.getOrDefault(e.getTransaction().getId(), List.of()) : List.of();
            resultat.add(mouvement(e, groupe, compte));
        }
        return resultat;
    }

    private static JournalCompteCalcul.MouvementBrut mouvement(EcritureGrandLivre e, List<EcritureGrandLivre> groupe,
                                                               String compte) {
        PieceComptable piece = e.getPiece();
        NoteFrais note = note(e);
        // Contrepartie : les autres lignes de la piece hors compte du journal, la plus grosse d'abord.
        List<String> numeros = groupe.stream()
            .filter(x -> !x.getId().equals(e.getId()) && x.getCompte() != null
                && !x.getCompte().getNumero().startsWith(compte))
            .sorted(Comparator.comparing((EcritureGrandLivre x) -> nz(x.getDebit()).add(nz(x.getCredit()))).reversed()
                .thenComparing(x -> x.getCompte().getNumero()))
            .map(x -> x.getCompte().getNumero())
            .distinct()
            .toList();
        String comptes = numeros.isEmpty() ? null
            : String.join(" / ", numeros.subList(0, Math.min(numeros.size(), COMPTES_MAX)))
                + (numeros.size() > COMPTES_MAX ? " / ..." : "");
        String libelle = e.getLibelle() != null ? e.getLibelle() : piece != null ? piece.getLibelle() : null;
        return new JournalCompteCalcul.MouvementBrut(e.getDateEcriture(),
            note == null ? null : note.getReference(),
            note == null ? null : note.getObjet(),
            note == null ? null : note.getBeneficiaire(),
            libelle, comptes,
            piece == null ? null : piece.getReference(),
            piece != null && piece.getStatut() == StatutPiece.ANNULEE,
            e.getDebit(), e.getCredit());
    }

    /** La note de frais reglee par l'operation (caisse, banque ou mobile money) qui porte l'ecriture, s'il y en a une. */
    private static NoteFrais note(EcritureGrandLivre e) {
        if (e.getTransaction() != null && e.getTransaction().getNoteFrais() != null) {
            return e.getTransaction().getNoteFrais();
        }
        if (e.getTransactionBancaire() != null && e.getTransactionBancaire().getNoteFrais() != null) {
            return e.getTransactionBancaire().getNoteFrais();
        }
        if (e.getTransactionMobileMoney() != null) {
            return e.getTransactionMobileMoney().getNoteFrais();
        }
        return null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nz(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }
}
