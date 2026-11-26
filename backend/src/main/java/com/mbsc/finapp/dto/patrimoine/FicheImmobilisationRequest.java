package com.mbsc.finapp.dto.patrimoine;

import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.ModeAmortissement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Modification d'une fiche d'immobilisation par le responsable logistique (19/11/2026). Tant qu'aucune dotation n'est
 * comptabilisée, tout se modifie (valeur et date comprises si l'acquisition n'est pas encore passée) et le plan est
 * recalculé ; ensuite, seule la suite du plan est recalculée (changement d'estimation, sans effet rétroactif :
 * SYSCOHADA révisé, révision prospective de la durée et du mode).
 */
public record FicheImmobilisationRequest(
    @NotBlank @Size(max = 200) String libelle,
    CategorieImmobilisation categorie,
    @Size(max = 1000) String description,
    String compteImmobilisationNumero,
    String compteAmortissementNumero,
    String compteDotationNumero,
    LocalDate dateAcquisition,
    LocalDate dateMiseService,
    @Positive BigDecimal valeurAcquisition,
    @PositiveOrZero BigDecimal valeurResiduelle,
    @Positive @Max(1200) Integer dureeMois,
    ModeAmortissement modeAmortissement,
    @Size(max = 255) String localisation,
    @Size(max = 200) String fournisseur,
    @Size(max = 100) String numeroSerie,
    /** Motif, gardé au journal d'audit (obligatoire quand le plan change). */
    @Size(max = 300) String motif
) {}
