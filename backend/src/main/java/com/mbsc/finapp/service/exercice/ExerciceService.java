package com.mbsc.finapp.service.exercice;

import com.mbsc.finapp.service.PeriodeComptableService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exercices comptables consultables (19/11/2026) : années civiles (1er janvier – 31 décembre, SYSCOHADA), de la
 * première pièce enregistrée jusqu'à l'année en cours (ou l'année de la dernière pièce, si une extourne est déjà passée
 * au 1er janvier suivant). Un exercice est clôturé quand la clôture définitive (PeriodeComptableService) atteint son
 * 31 décembre : il ne se consulte plus qu'en lecture seule (ExerciceConsulteFilter), l'impression restant ouverte
 * selon les droits de chacun.
 */
@Service
@RequiredArgsConstructor
public class ExerciceService {

    public enum Statut { CLOTURE, A_CLOTURER, EN_COURS, A_VENIR }

    public record Exercice(int annee, LocalDate debut, LocalDate fin, Statut statut, boolean courant,
                           boolean lectureSeule, LocalDate dateCloture) { }

    private final JdbcTemplate jdbc;
    private final PeriodeComptableService periodes;

    @Transactional(readOnly = true)
    public List<Exercice> lister() {
        int courant = LocalDate.now().getYear();
        Map<String, Object> bornes = jdbc.queryForMap(
            "select min(extract(year from date_piece))::int as premiere, max(extract(year from date_piece))::int as derniere "
                + "from pieces_comptables");
        int premiere = bornes.get("premiere") == null ? courant : Math.min(courant, ((Number) bornes.get("premiere")).intValue());
        int derniere = bornes.get("derniere") == null ? courant : Math.max(courant, ((Number) bornes.get("derniere")).intValue());
        LocalDate cloture = periodes.dateClotureExercice();
        if (cloture != null) premiere = Math.min(premiere, cloture.getYear());
        List<Exercice> liste = new ArrayList<>();
        for (int annee = derniere; annee >= premiere; annee--) liste.add(exercice(annee, courant, cloture));
        return liste;
    }

    /** true si l'exercice de cette année est clôturé définitivement (lecture seule). */
    @Transactional(readOnly = true)
    public boolean estCloture(int annee) {
        return estCloture(annee, periodes.dateClotureExercice());
    }

    static boolean estCloture(int annee, LocalDate cloture) {
        return cloture != null && !cloture.isBefore(LocalDate.of(annee, 12, 31));
    }

    static Exercice exercice(int annee, int courant, LocalDate cloture) {
        boolean clos = estCloture(annee, cloture);
        Statut statut = clos ? Statut.CLOTURE
            : annee < courant ? Statut.A_CLOTURER
            : annee == courant ? Statut.EN_COURS
            : Statut.A_VENIR;
        return new Exercice(annee, LocalDate.of(annee, 1, 1), LocalDate.of(annee, 12, 31), statut, annee == courant,
            clos, clos ? cloture : null);
    }
}
