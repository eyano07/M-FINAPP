package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.enums.SituationFamiliale;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.domain.enums.StatutPiece;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BulletinPaieResponse(
    Long id,
    Long employeId,
    String employeMatricule,
    String employeNomComplet,
    String employePoste,
    String employeAffectation,
    LocalDate employeDateEmbauche,
    SituationFamiliale employeSituationFamiliale,
    /** Numero CNSS + lettre de categorie de l'employe (voir Employe.categorie). */
    String employeCategorie,
    Integer mois,
    Integer annee,
    BigDecimal salaireBaseUsd,
    BigDecimal presencePct,
    Integer nombreEnfants,
    BigDecimal conge,
    BigDecimal heuresSupplementaires,
    BigDecimal allocationFamiliale,
    BigDecimal primeDiplome,
    BigDecimal primeAnciennete,
    BigDecimal primeRendement,
    BigDecimal avanceSalaire,
    BigDecimal pret,
    BigDecimal salaireBrut,
    BigDecimal indemniteLogement,
    BigDecimal indemniteTransport,
    BigDecimal baseImposableInpp,
    BigDecimal baseImposableInss,
    BigDecimal baseImposableIpr,
    BigDecimal cnssOuvriere,
    BigDecimal cnssPatronale,
    BigDecimal onem,
    BigDecimal totalInss,
    BigDecimal inpp,
    BigDecimal ipr,
    BigDecimal salaireNet,
    BigDecimal tauxChangeApplique,
    BigDecimal netFc,
    LocalDate datePaiement,
    StatutBulletin statut,
    /** Référence de la pièce comptable posée à la clôture, ou null si non clôturé. */
    String pieceReference,
    /** Statut de la pièce liée (BROUILLON tant que le DFIN ne l'a pas comptabilisée), ou null. */
    StatutPiece pieceStatut,
    /** Vrai dès que la période a été clôturée — verrouille le bulletin, indépendamment de pieceReference (voir ParametresPaie.comptabiliserPaie). */
    boolean cloture,
    /** Pilote le document imprimé : bulletin complet si vrai, reçu de paiement simplifié sinon. */
    boolean employeConforme,
    /** Nom complet du titulaire actuel du rôle RESP_DRH, pour la signature du bulletin imprimé (null si aucun). */
    String drhNom
) {
    public static BulletinPaieResponse from(BulletinPaie b, String drhNom) {
        return new BulletinPaieResponse(
            b.getId(), b.getEmploye().getId(), b.getEmploye().getMatricule(), b.getEmploye().getNomComplet(),
            b.getEmploye().getPoste(), b.getEmploye().getAffectation(),
            b.getEmploye().getDateEmbauche(), b.getEmploye().getSituationFamiliale(), b.getEmploye().getCategorie(),
            b.getMois(), b.getAnnee(), b.getSalaireBaseUsd(), b.getPresencePct(), b.getNombreEnfants(),
            b.getConge(), b.getHeuresSupplementaires(), b.getAllocationFamiliale(), b.getPrimeDiplome(),
            b.getPrimeAnciennete(), b.getPrimeRendement(), b.getAvanceSalaire(), b.getPret(),
            b.getSalaireBrut(), b.getIndemniteLogement(), b.getIndemniteTransport(), b.getBaseImposableInpp(),
            b.getBaseImposableInss(), b.getBaseImposableIpr(), b.getCnssOuvriere(), b.getCnssPatronale(),
            b.getOnem(), b.getTotalInss(), b.getInpp(), b.getIpr(), b.getSalaireNet(), b.getTauxChangeApplique(),
            b.getNetFc(), b.getDatePaiement(), b.getStatut(),
            b.getPieceComptable() != null ? b.getPieceComptable().getReference() : null,
            b.getPieceComptable() != null ? b.getPieceComptable().getStatut() : null,
            b.getDateCloture() != null,
            b.getEmploye().isConforme(), drhNom);
    }
}
