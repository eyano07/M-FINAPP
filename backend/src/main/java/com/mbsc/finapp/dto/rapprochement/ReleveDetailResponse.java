package com.mbsc.finapp.dto.rapprochement;

import com.mbsc.finapp.domain.enums.StatutReleve;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Relevé en cours de rapprochement : lignes, écritures candidates du compte et état de rapprochement. */
public record ReleveDetailResponse(
    Long id,
    Etablissement etablissement,
    int mois,
    int annee,
    String devise,
    BigDecimal soldeOuverture,
    BigDecimal soldeCloture,
    StatutReleve statut,
    String source,
    String creeParNom,
    String valideParNom,
    Instant dateValidation,
    String motifDevalidation,
    List<Ligne> lignes,
    List<Ecriture> ecritures,
    Etat etat,
    List<String> avertissements
) {
    public record Etablissement(Long id, String nom, String type, String compteNumero, String compteLibelle, String devise) {}

    /** Ligne du relevé ; {@code pieceRegularisation} : écriture créée depuis cette ligne. */
    public record Ligne(Long id, int ordre, LocalDate dateOperation, String libelle, String reference,
                        BigDecimal entree, BigDecimal sortie, Long pointageId, boolean automatique,
                        String pieceRegularisation) {}

    /** Écriture du compte, montants dans la devise du compte (entrée = débit du compte, sortie = crédit). */
    public record Ecriture(Long id, LocalDate date, String libelle, String pieceReference, String journal,
                           BigDecimal entree, BigDecimal sortie, Long pointageId, boolean anterieure) {}

    /**
     * État de rapprochement : solde du relevé − lignes non pointées = solde comptable − écritures non
     * pointées. {@code ecart} doit être nul ; {@code ecartReleve} contrôle le relevé lui-même (ouverture +
     * mouvements = clôture).
     */
    public record Etat(BigDecimal soldeReleve, BigDecimal lignesNonPointeesEntrees, BigDecimal lignesNonPointeesSorties,
                       int nombreLignesNonPointees, BigDecimal soldeComptable,
                       BigDecimal ecrituresNonPointeesEntrees, BigDecimal ecrituresNonPointeesSorties,
                       int nombreEcrituresNonPointees, BigDecimal ecart, BigDecimal ecartReleve, boolean validable) {}
}
