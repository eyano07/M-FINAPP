package com.mbsc.finapp.service.patrimoine;

import com.mbsc.finapp.domain.LigneNoteFrais;
import com.mbsc.finapp.domain.NoteFrais;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.CategorieImmobilisation;
import com.mbsc.finapp.domain.enums.ModeAmortissement;
import com.mbsc.finapp.dto.patrimoine.ImmobilisationRequest;
import com.mbsc.finapp.service.ConversionDeviseService;
import com.mbsc.finapp.service.PatrimoineService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Toute acquisition d'immobilisation passe par une note de frais : au paiement (caisse, banque ou mobile money), chaque
 * ligne imputée à un compte d'immobilisation (21 à 24) et marquée amortissable crée la fiche du bien et son plan
 * d'amortissement. La fiche n'écrit rien : le règlement a déjà débité le compte d'immobilisation.
 */
@Service
@RequiredArgsConstructor
public class AcquisitionNoteService {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionNoteService.class);

    private final PatrimoineService patrimoine;
    private final ConversionDeviseService conversion;
    /** Fiche de stock projet (V167) : achats « Projet … » payés, et acquisitions de projet affectées à leur projet. */
    private final com.mbsc.finapp.service.projet.ProjetStockService projetStock;

    @Transactional
    public void apresPaiementInterne(NoteFrais note, User auteur, BigDecimal taux) {
        LocalDate aujourdhui = LocalDate.now(com.mbsc.finapp.config.FuseauEntreprise.ZONE);
        for (LigneNoteFrais l : note.getLignes()) {
            if (!Boolean.TRUE.equals(l.getAmortissable()) || l.getCompteImputation() == null
                || !l.getCompteImputation().getNumero().matches("2[1-4].*")) {
                continue;
            }
            String compte = l.getCompteImputation().getNumero();
            BigDecimal valeur = conversion.enDeviseBase(l.montantHtTotal(), note.getDevise(), taux).montantBase()
                .setScale(2, RoundingMode.HALF_UP);
            if (valeur.signum() <= 0) {
                continue;
            }
            CategorieImmobilisation categorie = categorieDuCompte(compte);
            String libelle = StringUtils.hasText(l.getDescription()) ? l.getDescription().trim() : note.getObjet();
            if (libelle.length() > 200) libelle = libelle.substring(0, 200);
            var fiche = patrimoine.creerInterne(new ImmobilisationRequest(libelle, categorie,
                "Acquis par la note " + note.getReference(), compte, null, null, aujourdhui, aujourdhui, valeur,
                BigDecimal.ZERO, categorie.dureeMoisParDefaut(), ModeAmortissement.LINEAIRE, null, null,
                note.getBeneficiaire(), null, false, null, null), auteur);
            projetStock.lierImmobilisation(fiche.id(), l.getCompteImputation());
            log.info("Immobilisation {} créée au paiement de la note {} ({} USD, compte {})", fiche.reference(),
                note.getReference(), valeur, compte);
        }
        // Achats et acquisitions « Projet … » : fiche de stock du projet (séparée du stock global, sans écriture).
        projetStock.apresPaiementInterne(note, auteur, taux);
    }

    /** Catégorie dont le compte par défaut est le plus long préfixe du compte de la ligne (2442x -> informatique). */
    static CategorieImmobilisation categorieDuCompte(String compte) {
        return Arrays.stream(CategorieImmobilisation.values())
            .filter(c -> c != CategorieImmobilisation.AUTRE && compte.startsWith(c.compteImmobilisationParDefaut()))
            .max(Comparator.comparingInt(c -> c.compteImmobilisationParDefaut().length()))
            .orElse(CategorieImmobilisation.AUTRE);
    }
}
