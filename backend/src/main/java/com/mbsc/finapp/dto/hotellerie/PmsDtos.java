package com.mbsc.finapp.dto.hotellerie;

import com.mbsc.finapp.domain.enums.ModeReglement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Échanges entre M-FINAPP et l'application Hôtellerie (API {@code /pms/**}, V152). */
public final class PmsDtos {

    private PmsDtos() {
    }

    /** État de M-FINAPP vu par le PMS (appel système, sans utilisateur). */
    public record Etat(boolean moduleActif, String entreprise, String deviseBase, String version) { }

    /** Code de connexion unique à échanger contre une session. */
    public record EchangeCode(@NotBlank String code) { }

    /** L'utilisateur connecté, ses rôles et son niveau sur le module Hôtellerie. */
    public record Moi(Long id, String email, String nom, String prenom, Set<String> roles, String niveauHotellerie) { }

    public record Etablissement(Long id, String nom, String type, String devise, String compte) { }

    public record Entrepot(Long id, String code, String nom) { }

    public record Article(Long id, String code, String libelle, String type, BigDecimal prixVente, boolean soumisTva,
                          String uniteMesure) { }

    /** Ce dont le PMS a besoin pour encaisser : trésorerie, entrepôts, articles vendables (extras), TVA, devise. */
    public record Referentiel(String entreprise, String deviseBase, BigDecimal tauxTva, BigDecimal tauxChange,
                              boolean caisse, List<Etablissement> banques, List<Etablissement> mobileMoney,
                              List<Entrepot> entrepots, List<Article> articles) { }

    public record Client(Long id, String code, String nom, String telephone, String email, String typeClient) { }

    public record NouveauClient(@NotBlank @Size(max = 200) String nom, @Size(max = 40) String telephone,
                                @Size(max = 150) String email, @Size(max = 255) String adresse, @Size(max = 40) String nif,
                                @Pattern(regexp = "PARTICULIER|ENTREPRISE|ADMINISTRATION") String typeClient) { }

    /**
     * Fonds reçus avant ou pendant le séjour : acompte (4191) ou caution (1651), ou restitution d'une caution.
     *
     * @param cle clé d'idempotence du PMS : la même clé ne produit jamais deux écritures
     */
    public record Fonds(@NotBlank @Size(max = 80) String cle, Long clientId, @Size(max = 200) String clientNom,
                        @NotNull @Positive BigDecimal montant, @NotNull ModeReglement mode, Long etablissementId,
                        LocalDate date, @NotBlank @Size(max = 120) String reference) { }

    public record Piece(String cle, String pieceReference, BigDecimal montant, String compte, boolean dejaTraitee) { }

    /**
     * Une ligne du folio. {@code hebergement} : NUIT, HEURE, SEMAINE ou MOIS (article de séjour fourni par M-FINAPP,
     * analytique 986) ; sinon {@code articleId} d'un article vendable (minibar, blanchisserie, restauration…).
     */
    public record LigneFolio(@Pattern(regexp = "NUIT|HEURE|SEMAINE|MOIS") String hebergement, Long articleId,
                             @NotNull @Positive BigDecimal quantite, @NotNull @PositiveOrZero BigDecimal prixUnitaire,
                             @Size(max = 200) String designation) { }

    /**
     * Départ du client : le folio devient une vente M-FINAPP (origine HOTELLERIE), validée puis réglée par la réception.
     * Mode CREDIT : la créance reste ouverte (séjour facturé à une entreprise), réglée plus tard.
     *
     * @param acompteImpute  part des acomptes reçus (4191) imputée sur le folio
     * @param cautionImputee part de la caution (1651) retenue et imputée sur le folio
     */
    public record EncaissementFolio(@NotBlank @Size(max = 80) String cle, @NotBlank @Size(max = 120) String reference,
                                    Long clientId, @Size(max = 200) String clientNom, LocalDate date,
                                    @NotNull ModeReglement mode, Long etablissementId, Long entrepotId,
                                    @NotEmpty @Valid List<LigneFolio> lignes,
                                    @PositiveOrZero BigDecimal acompteImpute, @PositiveOrZero BigDecimal cautionImputee) { }

    public record Folio(String cle, Long venteId, String venteReference, String statut, BigDecimal totalHt,
                        BigDecimal totalTva, BigDecimal totalTtc, String pieceVente, String pieceReglement, boolean reglee,
                        boolean dejaTraitee) { }

    /** Message à un client (phase 2) : e-mail envoyé par la messagerie de M-FINAPP. */
    public record Message(@NotBlank @Size(max = 80) String cle, @NotBlank @jakarta.validation.constraints.Email @Size(max = 150) String destinataire,
                          @Size(max = 150) String nom, @NotBlank @Size(max = 200) String sujet, @NotBlank @Size(max = 8000) String corps) { }

    public record MessageEnvoye(String cle, boolean envoye, boolean dejaTraite) { }

    /** Règlement ultérieur d'un folio laissé à crédit. */
    public record ReglementFolio(@NotBlank @Size(max = 80) String cle, @NotNull ModeReglement mode, Long etablissementId,
                                 LocalDate date, @PositiveOrZero BigDecimal acompteImpute) { }
}
