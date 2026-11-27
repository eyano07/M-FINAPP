package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.DureeBudget;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.domain.enums.TypeBudget;
import com.mbsc.finapp.domain.enums.TypeNotification;
import com.mbsc.finapp.dto.budget.BudgetRequest;
import com.mbsc.finapp.dto.budget.BudgetResponse;
import com.mbsc.finapp.dto.budget.LigneBudgetRequest;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.service.BudgetService;
import com.mbsc.finapp.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Projets (V167), côté comptable : à partir de la liste de besoins envoyée par la logistique, le comptable (ou le DFIN)
 * crée un sous-compte « Projet … » par besoin (6588 pour un achat divers ; 6058 ou un compte 21 à 24 pour une
 * acquisition), puis le budget SECONDAIRE du projet en brouillon, une ligne par sous-compte. Il le soumet ensuite depuis
 * la page du budget : le DA l'approuve, le DFIN le met en exécution (circuit des budgets, inchangé).
 */
@Service
@RequiredArgsConstructor
public class ProjetBudgetService {

    static final String COMPTABILITE = "hasAnyRole('COMPTABLE', 'DFIN')";

    private final JdbcTemplate jdbc;
    private final ProjetService projets;
    private final ComptesProjetService comptesProjet;
    private final BudgetService budgets;
    private final NotificationService notifications;

    /** Choix du comptable pour un besoin : nature, compte parent (acquisition), prix et quantité confirmés. */
    public record ChoixBesoin(@NotNull Long articleId, @NotNull @Pattern(regexp = "ACHAT|ACQUISITION") String nature,
                              String compteParent, @NotNull @DecimalMin("0.001") BigDecimal quantite,
                              @NotNull @DecimalMin("0.00") BigDecimal prixEstimeUsd) { }

    public record CreationBudgetRequest(@NotNull @Min(2000) @Max(2100) Integer exercice, DureeBudget duree,
                                        @Min(1) @Max(12) Integer periode, @NotNull @Valid List<ChoixBesoin> besoins) { }

    public record RetourRequest(@NotBlank @Size(max = 1000) String motif) { }

    /** Projets envoyés par la logistique (à budgéter), puis ceux déjà budgétés. */
    @PreAuthorize(COMPTABILITE)
    @Transactional(readOnly = true)
    public List<ProjetService.Projet> aBudgeter() {
        return projets.lister().stream()
            .filter(p -> List.of("ENVOYE", "BUDGETE", "RETOURNE").contains(p.statut()))
            .toList();
    }

    @PreAuthorize(COMPTABILITE)
    @Transactional(readOnly = true)
    public ProjetService.Detail consulter(Long id) {
        return projets.consulter(id);
    }

    /** Retour à la logistique avec un motif (besoin à préciser, prix irréaliste...). */
    @PreAuthorize(COMPTABILITE)
    @Transactional
    public ProjetService.Projet retourner(Long id, RetourRequest r) {
        ProjetService.Projet p = projets.projet(id);
        exigerEnvoye(p);
        jdbc.update("update projets set statut = 'RETOURNE', motif_retour = ? where id = ?", r.motif().trim(), id);
        notifications.notifierRole(RoleType.LOGISTIQUE, TypeNotification.PROJET_RETOURNE, "Projet retourné",
            p.code() + " — " + p.nom() + " : " + r.motif().trim(), "/logistique/projets/" + id, null);
        return projets.projet(id);
    }

    /**
     * Crée les sous-comptes « Projet … » puis le budget secondaire du projet (brouillon). Renvoie le budget, que le
     * comptable soumet ensuite au DA depuis sa page.
     */
    @PreAuthorize(COMPTABILITE)
    @Transactional
    public BudgetResponse creerBudget(Long id, CreationBudgetRequest r) {
        ProjetService.Projet p = projets.projet(id);
        exigerEnvoye(p);
        Map<Long, ChoixBesoin> choix = new HashMap<>();
        r.besoins().forEach(c -> choix.put(c.articleId(), c));
        List<ProjetService.Article> articles = projets.articles(id);
        if (articles.isEmpty()) {
            throw new IllegalArgumentException("Ce projet n'a aucun besoin.");
        }

        Map<String, CompteOHADA> compteDuProjet = new HashMap<>();
        List<LigneBudgetRequest> lignes = new ArrayList<>();
        for (ProjetService.Article a : articles) {
            ChoixBesoin c = choix.get(a.id());
            String nature = c == null ? a.nature() : c.nature();
            BigDecimal quantite = c == null ? a.quantitePrevue() : c.quantite();
            BigDecimal prix = (c == null ? a.prixEstimeUsd() : c.prixEstimeUsd()).setScale(2, RoundingMode.HALF_UP);
            String parent = "ACHAT".equals(nature) ? ComptesProjetService.PARENT_ACHAT
                : c != null && c.compteParent() != null && !c.compteParent().isBlank() ? c.compteParent().trim()
                : ComptesProjetService.PARENT_ACQUISITION;
            if ("ACQUISITION".equals(nature) && !ComptesProjetService.parentAcquisitionAdmis(parent)) {
                throw new IllegalArgumentException("« " + a.designation() + " » : une acquisition s'impute à 6058 ou à un compte "
                    + "d'immobilisation 21 à 24 (pas " + parent + ").");
            }
            CompteOHADA regroupement = compteDuProjet.computeIfAbsent(parent, x -> comptesProjet.compteDuProjet(x, p.nom()));
            CompteOHADA compte = comptesProjet.compteDuBesoin(regroupement, p.nom(), a.designation());
            jdbc.update("""
                update projet_articles set nature = ?, compte_parent = ?, quantite_prevue = ?, prix_estime_usd = ?, compte_id = ?
                where id = ?
                """, nature, parent, quantite, prix, compte.getId(), a.id());
            BigDecimal montant = quantite.multiply(prix).setScale(2, RoundingMode.HALF_UP);
            if (montant.signum() > 0) {
                lignes.add(new LigneBudgetRequest(compte.getNumero(), null, montant,
                    a.designation() + " — " + quantite.stripTrailingZeros().toPlainString()
                        + (a.unite() == null ? "" : " " + a.unite()) + " × " + prix.toPlainString() + " USD", null));
            }
        }
        if (lignes.isEmpty()) {
            throw new IllegalArgumentException("Aucun besoin n'a de montant : renseignez les prix estimés.");
        }
        DureeBudget duree = r.duree() == null ? DureeBudget.ANNUEL : r.duree();
        BudgetResponse budget = budgets.creer(new BudgetRequest("Projet " + p.nom(), r.exercice(),
            "Budget du projet " + p.code() + " (" + p.nom() + "), établi d'après la liste de besoins de la logistique.",
            lignes, duree, duree == DureeBudget.ANNUEL ? 1 : (r.periode() == null ? 1 : r.periode()), TypeBudget.SECONDAIRE));
        jdbc.update("update projets set statut = 'BUDGETE', budget_id = ?, budgete_le = now(), motif_retour = null where id = ?",
            budget.id(), id);
        notifications.notifierRole(RoleType.LOGISTIQUE, TypeNotification.PROJET_BUDGETE, "Budget du projet créé",
            p.code() + " — " + p.nom() + " : budget " + budget.reference() + " créé, à valider par le DA puis à exécuter par le DFIN.",
            "/logistique/projets/" + id, null);
        return budget;
    }

    private static void exigerEnvoye(ProjetService.Projet p) {
        if (!"ENVOYE".equals(p.statut())) {
            throw new TransitionInvalideException("Le projet " + p.code() + " n'attend pas de budget (statut « "
                + p.statut().toLowerCase() + " »).");
        }
    }
}
