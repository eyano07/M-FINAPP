package com.mbsc.finapp.dto.logistique;

import com.mbsc.finapp.domain.MouvementStock;
import com.mbsc.finapp.domain.enums.StatutMouvement;
import com.mbsc.finapp.domain.enums.TypeMouvementStock;

import java.time.LocalDate;
import java.util.List;

public record MouvementResponse(
    Long id,
    String reference,
    TypeMouvementStock type,
    LocalDate dateMouvement,
    String libelle,
    StatutMouvement statut,
    String compteContrepartieNumero,
    String pieceReference,
    String createdByEmail,
    List<LigneMouvementResponse> lignes,
    boolean annulable,
    boolean moduleRestaurant
) {
    public static MouvementResponse from(MouvementStock m) {
        return from(m, true);
    }

    public static MouvementResponse from(MouvementStock m, boolean withLignes) {
        return from(m, withLignes, false, false);
    }

    /**
     * @param annulable        true si le mouvement est validé et n'appartient à aucune
     *                         opération (vente, production, camion, perte de boisson) qui doive l'annuler
     *                         elle-même — voir StockService.exigerAnnulableSeul
     * @param moduleRestaurant true s'il porte sur un plat, une boisson ou une provision :
     *                         seul l'administrateur peut alors l'annuler depuis la Logistique
     */
    public static MouvementResponse from(MouvementStock m, boolean withLignes,
                                         boolean annulable, boolean moduleRestaurant) {
        List<LigneMouvementResponse> lignesDto = withLignes && m.getLignes() != null
            ? m.getLignes().stream().map(LigneMouvementResponse::from).toList()
            : List.of();
        return avec(m, lignesDto, annulable, moduleRestaurant);
    }

    /**
     * Le mouvement avec seulement certaines de ses lignes : la vue d'un responsable cuisine ou bar sur un mouvement
     * mixte (une vente porte un plat et une boisson). Jamais annulable ni « module restaurant » : ces deux
     * indicateurs ne servent qu'aux boutons d'annulation, qu'un lecteur seul n'a pas.
     */
    public static MouvementResponse from(MouvementStock m, List<com.mbsc.finapp.domain.LigneMouvementStock> lignes) {
        return avec(m, lignes.stream().map(LigneMouvementResponse::from).toList(), false, false);
    }

    private static MouvementResponse avec(MouvementStock m, List<LigneMouvementResponse> lignesDto,
                                          boolean annulable, boolean moduleRestaurant) {
        return new MouvementResponse(
            m.getId(),
            m.getReference(),
            m.getType(),
            m.getDateMouvement(),
            m.getLibelle(),
            m.getStatut(),
            m.getCompteContrepartie() == null ? null : m.getCompteContrepartie().getNumero(),
            m.getPiece() == null ? null : m.getPiece().getReference(),
            m.getCreatedBy() == null ? null : m.getCreatedBy().getEmail(),
            lignesDto,
            annulable,
            moduleRestaurant
        );
    }
}
