package com.mbsc.finapp.dto.parametrage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Renseignements de la liasse fiscale OHADA : page de garde et fiches R1 à R3
 * (la fiche R4 se déduit des notes). Tous facultatifs : une case vide reste vide
 * dans le classeur des états financiers.
 *
 * <p>Conservés en JSON dans {@code parametres_entreprise.informations_liasse}
 * (V140) : le formulaire de la liasse évolue avec le référentiel, sans
 * migration à chaque champ.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InformationsLiasse(
    // ---- Page de garde ----
    /** Ex. « REPUBLIQUE DEMOCRATIQUE DU CONGO ». */
    @Size(max = 120) String pays,
    /** Direction des impôts destinataire (ex. « Direction Générale des Impôts »). */
    @Size(max = 120) String directionImpots,
    @Size(max = 120) String centreDepot,

    // ---- Fiche R1 ----
    @Size(max = 40) String sigleUsuel,
    @Size(max = 40) String boitePostale,
    @Size(max = 20) String codePostal,
    @Size(max = 80) String ville,
    /** ZB : date d'arrêté effectif des comptes. */
    LocalDate dateArreteComptes,
    /** ZC : clôture de l'exercice précédent ; vide = veille du début de l'exercice exporté. */
    LocalDate exercicePrecedentClos,
    @Min(1) @Max(24) Integer dureeExercicePrecedentMois,
    /** ZD : greffe du registre du commerce. */
    @Size(max = 80) String greffe,
    @Size(max = 80) String numeroRepertoireEntites,
    /** ZE */
    @Size(max = 60) String numeroCaisseSociale,
    @Size(max = 60) String codeImportateur,
    @Size(max = 20) String codeActivitePrincipale,
    /** ZI : désignation précise de l'activité principale. */
    @Size(max = 255) String activitePrincipale,
    /** Personne à contacter pour des informations complémentaires (nom, adresse, qualité). */
    @Size(max = 255) String personneContact,
    /** Professionnel salarié ou cabinet ayant établi les états financiers (nom, adresse, téléphone). */
    @Size(max = 255) String professionnelComptable,
    @Size(max = 255) String visaExpertComptable,
    /** États financiers approuvés par l'assemblée générale : oui, non, ou vide. */
    Boolean etatsApprouvesAg,
    @Size(max = 120) String nomSignataire,
    @Size(max = 120) String qualiteSignataire,
    LocalDate dateSignature,
    @Valid @Size(max = 10) List<Domiciliation> domiciliations,

    // ---- Fiche R2 ----
    /** ZK : code forme juridique (note 36), ex. « 02 » pour une SARL. */
    @Pattern(regexp = "^$|^\\d{2}$", message = "Code forme juridique : deux chiffres (note 36)") String formeJuridique,
    /** ZL : code régime fiscal (1 réel normal, 2 réel simplifié, 3 synthétique, 4 forfait). */
    @Pattern(regexp = "^$|^\\d{1,2}$", message = "Code régime fiscal : un ou deux chiffres (note 36)") String regimeFiscal,
    /** ZM : code pays du siège social (note 36), ex. « 10 ». */
    @Pattern(regexp = "^$|^\\d{2}$", message = "Code pays : deux chiffres (note 36)") String paysSiege,
    @Min(0) @Max(9999) Integer nbEtablissementsPays,
    @Min(0) @Max(9999) Integer nbEtablissementsHorsPays,
    @Min(1900) @Max(2100) Integer premiereAnneeExercice,
    /** PUBLIC, PRIVE_NATIONAL ou PRIVE_ETRANGER. */
    @Pattern(regexp = "^$|^(PUBLIC|PRIVE_NATIONAL|PRIVE_ETRANGER)$") String controle,
    @Valid @Size(max = 3) List<Activite> activites,

    // ---- Fiche R3 ----
    @Valid @Size(max = 6) List<Dirigeant> dirigeants,
    @Valid @Size(max = 7) List<Administrateur> administrateurs
) {

    /** Domiciliation bancaire (fiche R1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Domiciliation(@Size(max = 80) String banque, @Size(max = 60) String numeroCompte) {}

    /**
     * Activité de la fiche R2. La part du chiffre d'affaires (en %) répartit le
     * chiffre d'affaires de l'exercice entre les activités ; vide pour une seule
     * activité, elle vaut 100 %.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Activite(@Size(max = 120) String designation, @Size(max = 12) String codeNomenclature,
                           @DecimalMin("0") @DecimalMax("100") BigDecimal pourcentage) {}

    /** Dirigeant (fiche R3) : PDG, DG, administrateur général, gérant… */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dirigeant(@Size(max = 80) String nom, @Size(max = 80) String prenoms, @Size(max = 60) String qualite,
                            @Size(max = 40) String nif, @Size(max = 160) String adresse) {}

    /** Membre du conseil d'administration (fiche R3). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Administrateur(@Size(max = 80) String nom, @Size(max = 80) String prenoms, @Size(max = 60) String qualite,
                                 @Size(max = 160) String adresse) {}

    public static InformationsLiasse vide() {
        return new InformationsLiasse(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, List.of(), null, null, null, null, null,
            null, null, List.of(), List.of(), List.of());
    }
}
