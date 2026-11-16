package com.mbsc.finapp.dto.drh;

import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.enums.OrganismePaie;
import com.mbsc.finapp.domain.enums.StatutNote;

import java.math.BigDecimal;
import java.util.List;

/**
 * Règlement de la paie d'un mois, vu depuis l'écran des bulletins : bulletins à régler, note de paie et
 * notes fiscales (voir {@code PaieNoteService}).
 *
 * @param bulletinsValides   bulletins VALIDE du mois
 * @param bulletinsBrouillon bulletins encore BROUILLON (bloquent la clôture et la note de paie)
 * @param cloture            tous les bulletins validés sont clôturés
 * @param bulletinsEligibles bulletins réglés par la note de paie ; sans note, bulletins validés qu'elle couvrira
 * @param totalNet           total des salaires nets des bulletins éligibles
 * @param paiePayee          la note de paie est payée : constatation écrite, notes fiscales possibles
 * @param notesAnnulees      historique des notes annulées du mois
 * @param comptabilisee      tous les bulletins validés du mois ont leur pièce de constatation (journal PAIE) ; faux
 *                           pour un mois clôturé en mode de comptabilisation manuel que le DFIN n'a pas encore
 *                           comptabilisé (audit DRH du 15/11/2026, D-01)
 */
public record NotesPaieResponse(
    int mois,
    int annee,
    int bulletinsValides,
    int bulletinsBrouillon,
    boolean cloture,
    int bulletinsEligibles,
    BigDecimal totalNet,
    NoteResume notePaie,
    boolean paiePayee,
    List<VersementFiscal> versements,
    List<NoteResume> notesAnnulees,
    boolean comptabilisee
) {

    /**
     * Montant dû à un organisme pour le mois, et sa note de versement si elle existe. {@code comptes} : les comptes
     * que la constatation a crédités pour lui (CNSS : 4311, 4312, 4313 ; 4315 pour une paie antérieure à la
     * répartition par branche) ; {@code compte} : les mêmes, joints par « / », pour l'affichage.
     */
    public record VersementFiscal(OrganismePaie organisme, String libelle, String beneficiaire, String compte,
                                  List<String> comptes, BigDecimal montant, NoteResume note) {}

    public record NoteResume(Long id, String reference, StatutNote statut, BigDecimal montant, String devise,
                             OrganismePaie organisme) {
        public static NoteResume from(NoteFrais n) {
            return new NoteResume(n.getId(), n.getReference(), n.getStatut(), n.getMontant(),
                n.getDevise() == null ? null : n.getDevise().name(), n.getOrganismePaie());
        }
    }
}
