package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.dto.restaurant.RestaurantAnalyseIaResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordProvisionsResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.BoissonStatResponse;
import com.mbsc.finapp.dto.restaurant.TableauBordRestaurantResponse.PerteTypeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Agent IA d'analyse du tableau de bord Restaurant : interprète les ventes,
 * achats, pertes et le stock d'une période pour produire, en français, une
 * synthèse et des recommandations concrètes — même patron que
 * {@link AnalyseFinanciereIaService}, dupliqué plutôt que partagé pour garder
 * les deux modules indépendants.
 *
 * <p>Tous les chiffres transmis au modèle proviennent directement de
 * {@link RestaurantService#tableauBord}, déjà calculés : l'IA interprète des
 * montants exacts, elle n'en invente jamais. Dégrade automatiquement vers une
 * synthèse locale si aucune clé API n'est configurée ou si l'appel échoue.</p>
 */
@Service
public class RestaurantAnalyseIaService {

    private static final Logger log = LoggerFactory.getLogger(RestaurantAnalyseIaService.class);
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestaurantService restaurantService;
    private final ChatGptClient chatGptClient;
    private final ObjectMapper objectMapper;

    public RestaurantAnalyseIaService(RestaurantService restaurantService, ChatGptClient chatGptClient,
                                      ObjectMapper objectMapper) {
        this.restaurantService = restaurantService;
        this.chatGptClient = chatGptClient;
        this.objectMapper = objectMapper;
    }

    private record AnalyseIaJson(String synthese, List<String> pointsForts,
                                  List<String> pointsAttention, List<String> recommandations) {}

    private static final Map<String, Object> SCHEMA_REPONSE = Map.of(
        "type", "object",
        "properties", Map.of(
            "synthese", Map.of("type", "string"),
            "pointsForts", Map.of("type", "array", "items", Map.of("type", "string")),
            "pointsAttention", Map.of("type", "array", "items", Map.of("type", "string")),
            "recommandations", Map.of("type", "array", "items", Map.of("type", "string"))),
        "required", List.of("synthese", "pointsForts", "pointsAttention", "recommandations"),
        "additionalProperties", false);

    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public RestaurantAnalyseIaResponse analyser(LocalDate du, LocalDate au) {
        LocalDate fin = au != null ? au : LocalDate.now();
        LocalDate debut = du != null ? du : fin.withDayOfMonth(1);

        TableauBordRestaurantResponse tb = restaurantService.tableauBord(debut, fin);

        if (chatGptClient.disponible()) {
            try {
                String prompt = construirePrompt(tb);
                String contenu = chatGptClient.json(SYSTEME, prompt, 3000, SCHEMA_REPONSE);
                AnalyseIaJson json = objectMapper.readValue(nettoyerJson(contenu), AnalyseIaJson.class);
                if (StringUtils.hasText(json.synthese())) {
                    return new RestaurantAnalyseIaResponse(debut, fin, json.synthese(),
                        listeOuVide(json.pointsForts()), listeOuVide(json.pointsAttention()),
                        listeOuVide(json.recommandations()), true);
                }
                log.warn("IA : réponse d'analyse restaurant sans synthèse exploitable, repli sur l'heuristique");
            } catch (Exception e) {
                log.warn("IA indisponible pour l'analyse restaurant ({}) : repli sur la synthèse locale", e.getMessage());
            }
        }
        return analyseHeuristique(debut, fin, tb);
    }

    /** Pendant de {@link #analyser} pour le tableau de bord des provisions (vivres, épices, charbon...). */
    @PreAuthorize("hasAnyRole('RESP_RESTAURANT', 'DFIN', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public RestaurantAnalyseIaResponse analyserProvisions(LocalDate du, LocalDate au) {
        LocalDate fin = au != null ? au : LocalDate.now();
        LocalDate debut = du != null ? du : fin.withDayOfMonth(1);

        TableauBordProvisionsResponse tb = restaurantService.tableauBordProvisions(debut, fin);

        if (chatGptClient.disponible()) {
            try {
                String prompt = construirePromptProvisions(tb);
                String contenu = chatGptClient.json(SYSTEME_PROVISIONS, prompt, 3000, SCHEMA_REPONSE);
                AnalyseIaJson json = objectMapper.readValue(nettoyerJson(contenu), AnalyseIaJson.class);
                if (StringUtils.hasText(json.synthese())) {
                    return new RestaurantAnalyseIaResponse(debut, fin, json.synthese(),
                        listeOuVide(json.pointsForts()), listeOuVide(json.pointsAttention()),
                        listeOuVide(json.recommandations()), true);
                }
                log.warn("IA : réponse d'analyse provisions sans synthèse exploitable, repli sur l'heuristique");
            } catch (Exception e) {
                log.warn("IA indisponible pour l'analyse provisions ({}) : repli sur la synthèse locale", e.getMessage());
            }
        }
        return analyseHeuristiqueProvisions(debut, fin, tb);
    }

    private List<String> listeOuVide(List<String> liste) {
        return liste == null ? List.of() : liste;
    }

    private String nettoyerJson(String contenu) {
        if (contenu == null) return "{}";
        String nettoye = contenu.strip();
        if (nettoye.startsWith("```")) {
            nettoye = nettoye.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").strip();
        }
        return nettoye;
    }

    // -----------------------------------------------------------------
    // Prompt
    // -----------------------------------------------------------------

    private static final String SYSTEME = """
        Tu es un consultant en gestion de bar/restaurant en République Démocratique \
        du Congo, qui conseille le responsable d'un établissement sur la gestion de \
        sa carte de boissons. On te fournit les chiffres reels d'une periode : \
        ventes, achats, pertes (casse, peremption, cadeaux), stock de boissons \
        pleines et de bouteilles vides, deja calcules par le systeme de gestion.

        Base-toi UNIQUEMENT sur les chiffres fournis : n'invente jamais un montant \
        ou une boisson qui n'apparait pas dans les donnees. Les montants sont en \
        dollars americains (USD).

        Reponds UNIQUEMENT en JSON valide, avec exactement ces cles :
        - "synthese" : chaine de 3 a 5 phrases resumant la performance de la periode ;
        - "pointsForts" : tableau de 2 a 4 chaines ;
        - "pointsAttention" : tableau de 2 a 4 chaines (pertes elevees, stock bas, \
          boisson qui ne se vend pas...) ;
        - "recommandations" : tableau de 3 a 6 chaines, des actions concretes et \
          priorisees (reappro, ajustement de prix, reduction de la casse...).
        Pas de texte hors du JSON, pas de balises markdown.""";

    private String construirePrompt(TableauBordRestaurantResponse tb) {
        StringBuilder sb = new StringBuilder();
        sb.append("Periode analysee : du ").append(tb.du().format(DATE_FR))
          .append(" au ").append(tb.au().format(DATE_FR)).append("\n\n");

        sb.append("=== VENTES ===\n");
        sb.append("Quantite vendue : ").append(tb.quantiteVendue()).append(" bouteilles\n");
        sb.append("Chiffre d'affaires : ").append(fmt(tb.chiffreAffaires())).append(" USD\n");
        sb.append("Nombre de ventes : ").append(tb.nombreVentes()).append("\n\n");

        sb.append("=== ACHATS (receptions) ===\n");
        sb.append("Quantite achetee : ").append(tb.quantiteAchetee()).append(" bouteilles\n");
        sb.append("Montant : ").append(fmt(tb.montantAchats())).append(" USD\n\n");

        sb.append("=== PROFIT ===\n");
        sb.append("Marge brute (ventes - cout des ventes) : ").append(fmt(tb.margeBrute())).append(" USD\n");
        sb.append("Valeur des pertes : ").append(fmt(tb.valeurPertes())).append(" USD\n");
        sb.append("Profit net (marge - pertes) : ").append(fmt(tb.profitNet())).append(" USD (")
          .append(tb.profitNet().signum() >= 0 ? "benefice" : "perte").append(")\n\n");

        sb.append("=== PERTES PAR TYPE ===\n");
        for (PerteTypeResponse p : tb.pertesParType()) {
            sb.append("- ").append(p.label()).append(" : ").append(p.quantite())
              .append(" bouteille(s), valeur ").append(fmt(p.valeur())).append(" USD\n");
        }
        sb.append("\n");

        sb.append("=== STOCK ACTUEL ===\n");
        sb.append("Valeur du stock de boissons pleines : ").append(fmt(tb.valeurStockPleines())).append(" USD\n");
        sb.append("Boissons sous le seuil de reapprovisionnement : ").append(tb.nombreBoissonsSousSeuil()).append("\n");
        sb.append("Total bouteilles vides en stock : ").append(tb.totalBouteillesVides())
          .append(" (").append(tb.totalCasiersVides()).append(" casiers + ")
          .append(tb.totalBouteillesRestantes()).append(" bouteilles)\n\n");

        sb.append("=== DETAIL PAR BOISSON ===\n");
        for (BoissonStatResponse b : tb.parBoisson()) {
            sb.append("- ").append(b.libelle()).append(" : vendu ").append(b.quantiteVendue())
              .append(" (CA ").append(fmt(b.chiffreAffaires())).append(" USD), stock plein ")
              .append(b.stockPleines());
            if (b.bouteillesVides() != null) {
                sb.append(", vides ").append(b.bouteillesVides());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String fmt(BigDecimal montant) {
        return montant == null ? "0" : montant.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    // -----------------------------------------------------------------
    // Repli heuristique (sans IA)
    // -----------------------------------------------------------------

    private RestaurantAnalyseIaResponse analyseHeuristique(LocalDate du, LocalDate au, TableauBordRestaurantResponse tb) {
        boolean benefice = tb.profitNet().signum() >= 0;
        String synthese = "Sur la période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR) + ", "
            + tb.nombreVentes() + " vente(s) ont généré un chiffre d'affaires de " + fmt(tb.chiffreAffaires())
            + " USD pour " + tb.quantiteVendue() + " bouteille(s). Le profit net de la période est "
            + (benefice ? "positif" : "négatif") + " (" + fmt(tb.profitNet()) + " USD), après déduction de "
            + fmt(tb.valeurPertes()) + " USD de pertes.";

        List<String> pointsForts = new java.util.ArrayList<>();
        List<String> pointsAttention = new java.util.ArrayList<>();
        List<String> recommandations = new java.util.ArrayList<>();

        if (!tb.topBoissons().isEmpty()) {
            BoissonStatResponse top = tb.topBoissons().get(0);
            pointsForts.add(top.libelle() + " est la boisson la plus vendue de la période ("
                + top.quantiteVendue() + " bouteilles).");
        }
        if (benefice) {
            pointsForts.add("La période dégage un profit net positif.");
        } else {
            pointsAttention.add("La période est déficitaire une fois les pertes déduites.");
            recommandations.add("Identifier la cause principale des pertes avant la prochaine période.");
        }

        if (tb.nombreBoissonsSousSeuil() > 0) {
            pointsAttention.add(tb.nombreBoissonsSousSeuil() + " boisson(s) sont sous leur seuil de réapprovisionnement.");
            recommandations.add("Planifier une réception pour les boissons sous seuil.");
        }
        if (tb.valeurPertes().signum() > 0) {
            pointsAttention.add("Des pertes valorisées à " + fmt(tb.valeurPertes()) + " USD ont été enregistrées (casse, péremption, cadeaux).");
            recommandations.add("Revoir le stockage et la rotation des boissons pour réduire la casse et la péremption.");
        }
        if (tb.totalCasiersVides() > 5) {
            recommandations.add("Un stock important de bouteilles vides (" + tb.totalCasiersVides()
                + " casiers) est disponible pour un échange de consigne lors de la prochaine réception.");
        }
        if (recommandations.isEmpty()) {
            recommandations.add("Poursuivre le suivi régulier des ventes et du stock.");
        }
        if (pointsForts.isEmpty()) {
            pointsForts.add("Aucune anomalie majeure détectée sur la période.");
        }
        if (pointsAttention.isEmpty()) {
            pointsAttention.add("Aucun point de vigilance particulier sur la période.");
        }

        return new RestaurantAnalyseIaResponse(du, au, synthese, pointsForts, pointsAttention, recommandations, false);
    }

    // -----------------------------------------------------------------
    // Provisions : prompt et repli heuristique
    // -----------------------------------------------------------------

    private static final String SYSTEME_PROVISIONS = """
        Tu es un consultant en gestion de cuisine de restaurant en République \
        Democratique du Congo, qui conseille le responsable sur ses provisions \
        (vivres, epices, charbon, et autres approvisionnements consommes en \
        cuisine). On te fournit les chiffres reels d'une periode : achats, \
        consommation, stock actuel, deja calcules par le systeme de gestion.

        Contrairement a une carte de boissons, les provisions ne sont jamais \
        revendues directement : c'est un centre de COUT, pas de revenu. \
        N'evoque donc jamais de "profit" ou de "marge" a leur sujet — la \
        question est l'efficacite des achats et de la consommation, et le \
        risque de rupture ou de surstock.

        Base-toi UNIQUEMENT sur les chiffres fournis : n'invente jamais un \
        montant ou une provision qui n'apparait pas dans les donnees. Les \
        montants sont en dollars americains (USD).

        Reponds UNIQUEMENT en JSON valide, avec exactement ces cles :
        - "synthese" : chaine de 3 a 5 phrases resumant les achats et la \
          consommation de la periode ;
        - "pointsForts" : tableau de 2 a 4 chaines ;
        - "pointsAttention" : tableau de 2 a 4 chaines (rupture de stock, \
          consommation anormalement elevee, provision jamais utilisee...) ;
        - "recommandations" : tableau de 3 a 6 chaines, des actions concretes \
          et priorisees (reappro, ajustement des quantites achetees...).
        Pas de texte hors du JSON, pas de balises markdown.""";

    private String construirePromptProvisions(TableauBordProvisionsResponse tb) {
        StringBuilder sb = new StringBuilder();
        sb.append("Periode analysee : du ").append(tb.du().format(DATE_FR))
          .append(" au ").append(tb.au().format(DATE_FR)).append("\n\n");

        sb.append("=== ACHATS (receptions) ===\n");
        sb.append("Quantite achetee : ").append(tb.quantiteAchetee()).append("\n");
        sb.append("Montant : ").append(fmt(tb.montantAchats())).append(" USD\n");
        sb.append("Nombre de receptions : ").append(tb.nombreReceptions()).append("\n\n");

        sb.append("=== CONSOMMATION (cuisine, casse, peremption) ===\n");
        sb.append("Quantite consommee : ").append(tb.quantiteConsommee()).append("\n");
        sb.append("Valeur consommee : ").append(fmt(tb.montantConsomme())).append(" USD\n");
        sb.append("Nombre de sorties : ").append(tb.nombreSorties()).append("\n\n");

        sb.append("=== STOCK ACTUEL ===\n");
        sb.append("Nombre de provisions referencees : ").append(tb.nombreProvisions()).append("\n");
        sb.append("Valeur du stock : ").append(fmt(tb.valeurStockActuel())).append(" USD\n");
        sb.append("Provisions sous le seuil de reapprovisionnement : ").append(tb.nombreSousSeuil()).append("\n\n");

        sb.append("=== DETAIL PAR PROVISION ===\n");
        for (var p : tb.parProvision()) {
            sb.append("- ").append(p.libelle()).append(" : achete ").append(p.quantiteAchetee())
              .append(", consomme ").append(p.quantiteConsommee())
              .append(", stock actuel ").append(p.stockActuel())
              .append(p.uniteMesure() != null ? " " + p.uniteMesure() : "")
              .append(p.sousSeuil() ? " [SOUS SEUIL]" : "")
              .append("\n");
        }

        return sb.toString();
    }

    private RestaurantAnalyseIaResponse analyseHeuristiqueProvisions(LocalDate du, LocalDate au, TableauBordProvisionsResponse tb) {
        String synthese = "Sur la période du " + du.format(DATE_FR) + " au " + au.format(DATE_FR) + ", "
            + tb.nombreReceptions() + " réception(s) ont apporté " + tb.quantiteAchetee() + " unité(s) de provisions ("
            + fmt(tb.montantAchats()) + " USD), tandis que " + tb.nombreSorties() + " sortie(s) ont consommé "
            + tb.quantiteConsommee() + " unité(s) (" + fmt(tb.montantConsomme()) + " USD). Le stock actuel est valorisé à "
            + fmt(tb.valeurStockActuel()) + " USD.";

        List<String> pointsForts = new java.util.ArrayList<>();
        List<String> pointsAttention = new java.util.ArrayList<>();
        List<String> recommandations = new java.util.ArrayList<>();

        if (!tb.topConsommees().isEmpty()) {
            var top = tb.topConsommees().get(0);
            pointsForts.add(top.libelle() + " est la provision la plus consommée de la période ("
                + top.quantiteConsommee() + " unité(s)).");
        }
        if (tb.nombreSousSeuil() > 0) {
            pointsAttention.add(tb.nombreSousSeuil() + " provision(s) sont sous leur seuil de réapprovisionnement.");
            recommandations.add("Planifier une réception pour les provisions sous seuil, avant rupture.");
        } else {
            pointsForts.add("Aucune provision sous le seuil de réapprovisionnement.");
        }
        if (tb.quantiteAchetee().compareTo(tb.quantiteConsommee()) > 0
            && tb.quantiteConsommee().signum() > 0
            && tb.quantiteAchetee().compareTo(tb.quantiteConsommee().multiply(java.math.BigDecimal.valueOf(3))) > 0) {
            pointsAttention.add("Les achats de la période dépassent largement la consommation : risque d'immobilisation de trésorerie.");
            recommandations.add("Réduire les prochains achats et privilégier l'écoulement du stock existant.");
        }
        if (recommandations.isEmpty()) {
            recommandations.add("Poursuivre le suivi régulier des achats et de la consommation.");
        }
        if (pointsAttention.isEmpty()) {
            pointsAttention.add("Aucun point de vigilance particulier sur la période.");
        }

        return new RestaurantAnalyseIaResponse(du, au, synthese, pointsForts, pointsAttention, recommandations, false);
    }
}
