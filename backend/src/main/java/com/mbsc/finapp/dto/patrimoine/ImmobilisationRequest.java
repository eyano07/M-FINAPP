package com.mbsc.finapp.dto.patrimoine;

import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.ModeAmortissement;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Creation ou modification d'un bien. Les trois comptes sont facultatifs :
 * a defaut, ceux de la categorie s'appliquent.
 */
public record ImmobilisationRequest(
    @NotBlank @Size(max = 200) String libelle,
    @NotNull CategorieImmobilisation categorie,
    @Size(max = 1000) String description,
    String compteImmobilisationNumero,
    String compteAmortissementNumero,
    String compteDotationNumero,
    @NotNull LocalDate dateAcquisition,
    @NotNull LocalDate dateMiseService,
    @NotNull @Positive BigDecimal valeurAcquisition,
    @PositiveOrZero BigDecimal valeurResiduelle,
    @NotNull @Positive @Max(1200) Integer dureeMois,
    /** Lineaire si absent : preserve le comportement des biens deja saisis. */
    ModeAmortissement modeAmortissement,
    @Size(max = 255) String localisation,
    Long responsableId,
    @Size(max = 200) String fournisseur,
    @Size(max = 100) String numeroSerie,
    /** true pour generer la piece d'acquisition (debit classe 2 / credit contrepartie). */
    boolean comptabiliserAcquisition,
    /** Contrepartie de l'acquisition : 4812 fournisseurs d'investissement, 571 caisse... */
    String compteContrepartieNumero
) {}
