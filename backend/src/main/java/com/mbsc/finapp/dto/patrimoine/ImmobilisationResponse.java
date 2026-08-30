package com.mbsc.finapp.dto.patrimoine;

import com.mbsc.finapp.domain.Immobilisation;
import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.StatutImmobilisation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ImmobilisationResponse(
    Long id,
    String reference,
    String libelle,
    CategorieImmobilisation categorie,
    String description,
    String compteImmobilisation,
    String compteAmortissement,
    String compteDotation,
    LocalDate dateAcquisition,
    LocalDate dateMiseService,
    BigDecimal valeurAcquisition,
    BigDecimal valeurResiduelle,
    Integer dureeMois,
    StatutImmobilisation statut,
    String localisation,
    String responsableNom,
    String fournisseur,
    String numeroSerie,
    BigDecimal cumulAmortissements,
    BigDecimal valeurNetteComptable,
    LocalDate dateSortie,
    BigDecimal valeurCession
) {
    public static ImmobilisationResponse from(Immobilisation i) {
        return new ImmobilisationResponse(
            i.getId(), i.getReference(), i.getLibelle(), i.getCategorie(), i.getDescription(),
            i.getCompteImmobilisation() != null ? i.getCompteImmobilisation().getNumero() : null,
            i.getCompteAmortissement() != null ? i.getCompteAmortissement().getNumero() : null,
            i.getCompteDotation() != null ? i.getCompteDotation().getNumero() : null,
            i.getDateAcquisition(), i.getDateMiseService(), i.getValeurAcquisition(),
            i.getValeurResiduelle(), i.getDureeMois(), i.getStatut(), i.getLocalisation(),
            i.getResponsable() != null
                ? i.getResponsable().getPrenom() + " " + i.getResponsable().getNom() : null,
            i.getFournisseur(), i.getNumeroSerie(),
            i.cumulComptabilise(), i.valeurNetteComptable(),
            i.getDateSortie(), i.getValeurCession());
    }
}
