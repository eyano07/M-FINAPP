package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.BulletinPaie;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieNote;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.domain.enums.OrganismePaie;
import com.mbsc.finapp.domain.enums.StatutBulletin;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.drh.NotesPaieResponse;
import com.mbsc.finapp.dto.drh.NotesPaieResponse.NoteResume;
import com.mbsc.finapp.dto.drh.NotesPaieResponse.VersementFiscal;
import com.mbsc.finapp.dto.notes.ActionWorkflowRequest;
import com.mbsc.finapp.dto.notes.LigneNoteFraisRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.BulletinPaieRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Règlement de la paie par notes de frais (module DRH), conforme au SYSCOHADA révisé.
 *
 * <ol>
 *   <li><b>Note de paie</b> (une par mois, catégorie {@link CategorieNote#PAIE}) : une ligne par agent
 *       au débit de 4221.1 « salaires nets », pour le net à payer de son bulletin. Elle suit le circuit
 *       normal des notes (DFIN, DA, transmission) et se paie par la caisse, la banque ou le mobile
 *       money. <b>Au paiement</b>, {@link #apresPaiementInterne} écrit aussi la constatation de la paie
 *       du mois (journal OD, datée du dernier jour du mois) : charges 66x/6413 contre les dettes
 *       4221.1, 431.1, 4472, 4478.x, 4211 et 2762. Le règlement de la note solde ensuite 4221.1.</li>
 *   <li><b>Notes fiscales</b> (une par impôt ou cotisation et par mois, catégorie
 *       {@link CategorieNote#IMPOT_PAIE}) : IPR, CNSS, INPP, ONEM, chacune au débit de la dette
 *       correspondante (voir {@link OrganismePaie}). Elles ne peuvent être créées qu'une fois la note
 *       de paie payée, quand ces dettes existent en comptabilité.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PaieNoteService {

    private static final Logger log = LoggerFactory.getLogger(PaieNoteService.class);

    /** Statuts d'une note qui ne compte plus (une nouvelle note peut la remplacer). */
    private static final List<StatutNote> INACTIFS = List.of(StatutNote.ANNULEE);

    private final BulletinPaieRepository bulletinRepository;
    private final NoteFraisRepository noteRepository;
    private final NoteFraisService noteFraisService;
    private final PaieComptabilisationService comptabilisation;
    private final ComptabiliteService comptabilite;
    private final PeriodeComptableService periodeComptable;

    // ---------------------------------------------------------------------
    // État du mois
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('RESP_DRH', 'DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public NotesPaieResponse etat(int mois, int annee) {
        List<BulletinPaie> bulletins = bulletinRepository.findByPeriode(mois, annee);
        List<BulletinPaie> valides = bulletins.stream().filter(b -> b.getStatut() == StatutBulletin.VALIDE).toList();
        long brouillons = bulletins.stream().filter(b -> b.getStatut() == StatutBulletin.BROUILLON).count();
        boolean cloture = !valides.isEmpty() && valides.stream().allMatch(b -> b.getDateCloture() != null);
        List<NoteFrais> notes = noteRepository.findNotesPaie(mois, annee);
        Optional<NoteFrais> notePaie = active(notes, CategorieNote.PAIE, null);
        // Bulletins réglés par la note de paie si elle existe, sinon ceux qu'elle couvrira.
        List<BulletinPaie> eligibles = notePaie
            .map(n -> valides.stream().filter(b -> b.getNoteFraisPaie() != null
                && n.getId().equals(b.getNoteFraisPaie().getId())).toList())
            .orElseGet(() -> eligibles(valides));
        BigDecimal totalNet = somme(eligibles, BulletinPaie::getSalaireNet);
        boolean paiePayee = notePaie.map(n -> n.getStatut() == StatutNote.PAYEE).orElse(false);

        List<VersementFiscal> versements = new ArrayList<>();
        for (OrganismePaie o : OrganismePaie.values()) {
            versements.add(new VersementFiscal(o, o.libelle(), o.beneficiaire(), o.compte(),
                montantOrganisme(valides, o), active(notes, CategorieNote.IMPOT_PAIE, o).map(NoteResume::from).orElse(null)));
        }
        return new NotesPaieResponse(mois, annee, valides.size(), (int) brouillons, cloture,
            eligibles.size(), totalNet, notePaie.map(NoteResume::from).orElse(null), paiePayee, versements,
            notes.stream().filter(n -> n.getStatut() == StatutNote.ANNULEE).map(NoteResume::from).toList());
    }

    // ---------------------------------------------------------------------
    // Note de paie
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public NotesPaieResponse creerNotePaie(int mois, int annee) {
        List<BulletinPaie> bulletins = bulletinRepository.findByPeriode(mois, annee);
        if (bulletins.stream().anyMatch(b -> b.getStatut() == StatutBulletin.BROUILLON)) {
            throw new TransitionInvalideException("Des bulletins de " + periode(mois, annee)
                + " sont encore en brouillon : validez-les ou annulez-les avant de créer la note de paie.");
        }
        List<BulletinPaie> valides = bulletins.stream().filter(b -> b.getStatut() == StatutBulletin.VALIDE).toList();
        if (valides.isEmpty()) {
            throw new TransitionInvalideException("Aucun bulletin validé pour " + periode(mois, annee) + ".");
        }
        if (valides.stream().anyMatch(b -> b.getDateCloture() == null)) {
            throw new TransitionInvalideException("Clôturez d'abord la paie de " + periode(mois, annee)
                + " : la note de paie fige les bulletins qu'elle règle.");
        }
        if (active(noteRepository.findNotesPaie(mois, annee), CategorieNote.PAIE, null).isPresent()) {
            throw new TransitionInvalideException("Une note de paie existe déjà pour " + periode(mois, annee)
                + " : annulez-la d'abord pour en créer une nouvelle.");
        }
        List<BulletinPaie> eligibles = eligibles(valides);
        if (eligibles.isEmpty()) {
            throw new TransitionInvalideException("Les bulletins de " + periode(mois, annee)
                + " ont été comptabilisés avant la mise en place des notes de paie : aucune note à créer.");
        }
        for (BulletinPaie b : eligibles) {
            if (b.getSalaireNet() == null || b.getSalaireNet().signum() <= 0) {
                throw new TransitionInvalideException("Le net à payer de " + b.getEmploye().getNomComplet()
                    + " est nul ou négatif : corrigez son bulletin (avances et prêts) avant de créer la note.");
            }
        }

        List<LigneNoteFraisRequest> lignes = eligibles.stream()
            .map(b -> ligne(b.getSalaireNet(), PaieComptabilisationService.COMPTE_NET_A_PAYER,
                "Salaire net " + periode(mois, annee) + " — " + b.getEmploye().getMatricule() + " "
                    + b.getEmploye().getNomComplet()))
            .toList();
        NoteFrais note = noteFraisService.creerEtSoumettreNotePaieInterne(CategorieNote.PAIE, mois, annee, null,
            "Paie " + periode(mois, annee) + " — salaires nets (" + eligibles.size() + " agent"
                + (eligibles.size() > 1 ? "s" : "") + ")",
            "Personnel — paie " + periode(mois, annee),
            "Note générée depuis les bulletins de paie. Son paiement écrit la constatation de la paie du mois "
                + "(charges de personnel, IPR, CNSS, INPP, ONEM) puis le règlement des salaires nets.",
            lignes);
        eligibles.forEach(b -> b.setNoteFraisPaie(note));
        bulletinRepository.saveAll(eligibles);
        return etat(mois, annee);
    }

    // ---------------------------------------------------------------------
    // Notes fiscales
    // ---------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public NotesPaieResponse creerNoteImpot(int mois, int annee, OrganismePaie organisme) {
        List<NoteFrais> notes = noteRepository.findNotesPaie(mois, annee);
        NoteFrais notePaie = active(notes, CategorieNote.PAIE, null)
            .filter(n -> n.getStatut() == StatutNote.PAYEE)
            .orElseThrow(() -> new TransitionInvalideException("La note de paie de " + periode(mois, annee)
                + " doit être payée avant les versements fiscaux : c'est son paiement qui constate en "
                + "comptabilité les dettes envers la DGI, la CNSS, l'INPP et l'ONEM."));
        if (active(notes, CategorieNote.IMPOT_PAIE, organisme).isPresent()) {
            throw new TransitionInvalideException("Une note " + organisme + " existe déjà pour "
                + periode(mois, annee) + " : annulez-la d'abord pour en créer une nouvelle.");
        }
        List<BulletinPaie> bulletins = bulletinRepository.findByNoteFraisPaieId(notePaie.getId());
        BigDecimal montant = montantOrganisme(bulletins, organisme);
        if (montant.signum() <= 0) {
            throw new TransitionInvalideException("Aucun montant " + organisme + " à verser pour "
                + periode(mois, annee) + ".");
        }
        noteFraisService.creerEtSoumettreNotePaieInterne(CategorieNote.IMPOT_PAIE, mois, annee, organisme,
            organisme.libelle() + " — paie " + periode(mois, annee),
            organisme.beneficiaire(),
            "Versement de la dette constatée au compte " + organisme.compte() + " par la paie de "
                + periode(mois, annee) + " (note " + notePaie.getReference() + ", " + bulletins.size() + " bulletins).",
            List.of(ligne(montant, organisme.compte(), organisme.libelle() + " " + periode(mois, annee))));
        return etat(mois, annee);
    }

    /** Annulation d'une note de paie ou fiscale depuis l'écran DRH (même règles que l'écran des notes). */
    @PreAuthorize("hasAnyRole('RESP_DRH', 'ADMIN')")
    @Transactional
    public NotesPaieResponse annulerNote(Long noteId, String motif) {
        NoteFrais note = noteRepository.findById(noteId)
            .orElseThrow(() -> RessourceIntrouvableException.of("NoteFrais", noteId));
        if (note.getCategorie() == CategorieNote.STANDARD) {
            throw new TransitionInvalideException("Cette note n'est pas une note de paie.");
        }
        if (note.getCategorie() == CategorieNote.PAIE && active(noteRepository.findNotesPaie(note.getPaieMois(),
                note.getPaieAnnee()), CategorieNote.IMPOT_PAIE, null).isPresent()) {
            throw new TransitionInvalideException("Annulez d'abord les notes fiscales du mois.");
        }
        noteFraisService.annuler(noteId, new ActionWorkflowRequest(motif));
        return etat(note.getPaieMois(), note.getPaieAnnee());
    }

    // ---------------------------------------------------------------------
    // Branchements sur le paiement
    // ---------------------------------------------------------------------

    /**
     * Appelé par les trois canaux de paiement (caisse, banque, mobile money) juste avant qu'une note
     * passe à PAYEE, dans la même transaction. Pour une note de paie : écrit la pièce de constatation de
     * la paie du mois. Sans effet sur toute autre note.
     */
    @Transactional
    public void apresPaiementInterne(NoteFrais note, User operateur) {
        if (note.getCategorie() != CategorieNote.PAIE) {
            return;
        }
        List<BulletinPaie> bulletins = bulletinRepository.findByNoteFraisPaieId(note.getId());
        if (bulletins.isEmpty()) {
            throw new TransitionInvalideException("La note de paie " + note.getReference()
                + " n'est plus rattachée à aucun bulletin : paiement impossible.");
        }
        BigDecimal nets = somme(bulletins, BulletinPaie::getSalaireNet);
        if (nets.compareTo(note.getMontant()) != 0) {
            throw new IllegalStateException("Note de paie " + note.getReference() + " : son montant ("
                + note.getMontant() + ") ne correspond plus au total des salaires nets (" + nets + ").");
        }
        LocalDate datePiece = dateConstatation(note.getPaieMois(), note.getPaieAnnee());
        String libelle = "Constatation de la paie " + periode(note.getPaieMois(), note.getPaieAnnee())
            + " — note " + note.getReference()
            + (datePiece.equals(YearMonth.of(note.getPaieAnnee(), note.getPaieMois()).atEndOfMonth())
                ? "" : " (période du mois de paie clôturée)");
        List<EcritureGrandLivre> lignes = comptabilisation.construireLignesGroupees(bulletins, libelle);
        PieceComptable piece;
        try {
            piece = comptabilite.creerPieceInterne(JournalComptable.OPERATIONS_DIVERSES, libelle, datePiece, lignes, operateur);
        } catch (RuntimeException e) {
            throw new TransitionInvalideException("La constatation de la paie de "
                + periode(note.getPaieMois(), note.getPaieAnnee()) + " ne peut pas être écrite au "
                + datePiece + " : " + e.getMessage());
        }
        bulletins.forEach(b -> b.setPieceComptable(piece));
        bulletinRepository.saveAll(bulletins);
        log.info("Paie {} constatee [piece={}, bulletins={}, note={}]",
            periode(note.getPaieMois(), note.getPaieAnnee()), piece.getReference(), bulletins.size(), note.getReference());
    }

    /**
     * Date de la pièce de constatation : le dernier jour du mois de paie (rattachement des charges au mois
     * qu'elles concernent). Si la période comptable de ce mois est déjà clôturée, le paiement reste
     * accepté : la constatation est alors datée du jour du paiement, ou du lendemain de la date de
     * clôture si celle-ci est postérieure, et son libellé le signale.
     */
    LocalDate dateConstatation(int mois, int annee) {
        LocalDate finDeMois = YearMonth.of(annee, mois).atEndOfMonth();
        LocalDate cloture = periodeComptable.dateCloture();
        if (cloture == null || finDeMois.isAfter(cloture)) {
            return finDeMois;
        }
        LocalDate aujourdhui = LocalDate.now();
        return aujourdhui.isAfter(cloture) ? aujourdhui : cloture.plusDays(1);
    }

    // ---------------------------------------------------------------------

    /** Bulletins validés que la note de paie peut couvrir : pas déjà comptabilisés par l'ancien circuit. */
    private static List<BulletinPaie> eligibles(List<BulletinPaie> valides) {
        return valides.stream().filter(b -> b.getPieceComptable() == null).toList();
    }

    private static Optional<NoteFrais> active(List<NoteFrais> notes, CategorieNote categorie, OrganismePaie organisme) {
        return notes.stream()
            .filter(n -> n.getCategorie() == categorie && !INACTIFS.contains(n.getStatut()))
            .filter(n -> organisme == null || n.getOrganismePaie() == organisme)
            .findFirst();
    }

    static BigDecimal montantOrganisme(List<BulletinPaie> bulletins, OrganismePaie o) {
        return switch (o) {
            case IPR -> somme(bulletins, BulletinPaie::getIpr);
            case CNSS -> somme(bulletins, BulletinPaie::getCnssOuvriere).add(somme(bulletins, BulletinPaie::getCnssPatronale));
            case INPP -> somme(bulletins, BulletinPaie::getInpp);
            case ONEM -> somme(bulletins, BulletinPaie::getOnem);
        };
    }

    private static BigDecimal somme(List<BulletinPaie> bulletins, java.util.function.Function<BulletinPaie, BigDecimal> f) {
        return bulletins.stream().map(f).map(v -> v == null ? BigDecimal.ZERO : v).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LigneNoteFraisRequest ligne(BigDecimal montant, String compte, String description) {
        return new LigneNoteFraisRequest(montant, compte, description, false, null, null, null, null, false, null, null, null);
    }

    private static String periode(int mois, int annee) {
        return String.format("%02d/%d", mois, annee);
    }
}
