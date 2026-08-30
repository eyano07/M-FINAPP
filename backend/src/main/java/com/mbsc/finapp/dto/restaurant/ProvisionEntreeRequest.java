package com.mbsc.finapp.dto.restaurant;

import com.mbsc.finapp.domain.enums.Devise;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Réception d'une provision (vivres, épices, charbon...) : entrée en stock
 * valorisée, avec pièce comptable (D compte de stock de la provision / C
 * contrepartie) — même mécanique que la réception de boissons, sans la
 * notion de casier qui ne s'applique pas à ces articles.
 *
 * @param coutUnitaire prix unitaire tel que quoté par le fournisseur, dans
 *                      {@link #devise}
 * @param devise        devise de {@code coutUnitaire} (défaut USD si absente).
 *                       Si CDF, le taux du jour est résolu une seule fois, au
 *                       moment de cette réception, pour convertir en USD avant
 *                       stockage définitif — un changement de taux ultérieur
 *                       n'affecte donc jamais cette réception déjà enregistrée.
 */
public record ProvisionEntreeRequest(
    @NotNull Long articleId,
    @NotNull @Positive BigDecimal quantite,
    @NotNull @Positive BigDecimal coutUnitaire,
    Devise devise,
    @NotNull Long entrepotId,
    @NotBlank String compteContrepartieNumero,
    LocalDate dateReception
) {}
