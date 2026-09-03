package com.mbsc.finapp.dto.drh;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bordereau recapitulatif des cotisations et retenues d'une periode de paie,
 * ventile par organisme destinataire.
 *
 * <p>Chaque organisme percoit une assiette et un montant qui lui sont propres
 * — la CNSS sur la base cotisable, l'INPP sur une assiette elargie aux primes,
 * l'IPR sur la base imposable — d'ou une ligne par organisme plutot qu'un
 * total unique. Le detail par agent accompagne le total : c'est lui qui est
 * exige a l'appui d'une declaration.</p>
 */
public record DeclarationSocialeResponse(
    int mois,
    int annee,
    int nombreBulletins,
    /** Bulletins pris en compte : seuls les bulletins VALIDE ou clotures. */
    String perimetre,
    BigDecimal totalSalaireBrut,
    BigDecimal totalBaseCotisable,
    BigDecimal totalNetPaye,
    List<LigneOrganisme> organismes,
    List<LigneAgent> agents
) {
    /**
     * @param organisme   destinataire du versement
     * @param assiette    base de calcul retenue pour cet organisme
     * @param partSalarie retenue prelevee sur le salaire de l'agent
     * @param partPatronale charge supportee par l'employeur
     * @param compteOhada compte de dette ou l'ecriture de paie a porte le du
     */
    public record LigneOrganisme(
        String organisme,
        String base,
        BigDecimal assiette,
        BigDecimal partSalarie,
        BigDecimal partPatronale,
        BigDecimal total,
        String compteOhada
    ) {}

    public record LigneAgent(
        String matricule,
        String nomComplet,
        String numeroCnss,
        BigDecimal salaireBrut,
        BigDecimal baseCotisable,
        BigDecimal cnssOuvriere,
        BigDecimal cnssPatronale,
        BigDecimal onem,
        BigDecimal inpp,
        BigDecimal ipr,
        BigDecimal netPaye
    ) {}
}
