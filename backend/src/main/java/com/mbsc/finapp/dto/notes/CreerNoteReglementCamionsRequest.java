package com.mbsc.finapp.dto.notes;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Demande de reglement, via note(s) de frais (circuit DFIN/DA/Tresorerie), de
 * la dette fournisseur d'un ou plusieurs camions de minerais et/ou de leurs
 * frais accessoires (transport, peage, pont bascule...).
 *
 * <p>Le fournisseur du minerais et le prestataire des frais accessoires
 * n'etant generalement pas la meme partie, le service cree jusqu'a deux
 * notes independantes — voir {@code NoteFraisService
 * .creerReglementCamionsMinerai} : une pour {@code camionIds}
 * (beneficiaire = {@link #beneficiaireCamions}), une pour l'ensemble des
 * frais a regler (beneficiaire = {@link #beneficiaireFrais}) — cet ensemble
 * regroupe {@code chargeIds} (selection explicite, ecran "Frais connexes a
 * regler") ET tout frais non solde rattache aux camions de {@code
 * camionIds}, poste ou encore en attente. Un des deux beneficiaires peut
 * rester vide si la liste correspondante (camions, ou frais au sens large
 * ci-dessus) se revele vide une fois resolue cote service — d'ou l'absence
 * de {@code @NotBlank} ici, la regle etant contextuelle.</p>
 */
public record CreerNoteReglementCamionsRequest(

    List<Long> camionIds,

    /** Frais accessoires a regler en plus de ceux rattaches automatiquement aux camions ci-dessus. */
    List<Long> chargeIds,

    /** Destinataire du paiement des camions (le fournisseur de minerais). */
    @Size(max = 200)
    String beneficiaireCamions,

    /** Destinataire du paiement des frais accessoires (le prestataire — transport, peage...). */
    @Size(max = 200)
    String beneficiaireFrais,

    @Size(max = 2000)
    String description
) {}
