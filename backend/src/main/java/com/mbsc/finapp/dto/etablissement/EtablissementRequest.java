package com.mbsc.finapp.dto.etablissement;

import com.mbsc.finapp.domain.enums.TypeEtablissement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Creation d'une banque ou d'un operateur mobile money. */
public record EtablissementRequest(

    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 100)
    String nom,

    @NotNull(message = "Le type est obligatoire")
    TypeEtablissement type,

    /** Devise de tenue du compte et de ses relevés (USD par défaut). */
    com.mbsc.finapp.domain.enums.Devise devise
) {}
