package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.dto.comptabilite.AnalyseFinanciereResponse;
import com.mbsc.finapp.dto.comptabilite.BalanceVerificationResponse;
import com.mbsc.finapp.dto.comptabilite.BilanResponse;
import com.mbsc.finapp.dto.comptabilite.CompteResultatResponse;
import com.mbsc.finapp.dto.comptabilite.EtatFinancierLigne;
import com.mbsc.finapp.dto.comptabilite.LigneBalanceVerificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent IA d'analyse financière : interprète le Bilan, le Compte de résultat
 * et la Balance de vérification d'une période pour produire, en français, une
 * synthèse, des points forts, des points de vigilance et des recommandations
 * concrètes — affichés en fin du PDF imprimable des états financiers (voir
 * {@code pages/comptabilite/{bilan,compte-resultat,balance-verification}}).
 *
 * <p>Tous les montants transmis au modèle proviennent directement des états
 * déjà calculés et vérifiés par {@link ComptabiliteService} : l'IA ne fait
 * qu'interpréter des chiffres exacts, elle n'en invente jamais. Comme pour
 * {@link IaAssistantService}, le service dégrade automatiquement vers une
 * synthèse locale (gabarit de phrase à partir des mêmes totaux) si aucune clé
 * API n'est configurée, si l'IA est désactivée, ou si l'appel échoue.</p>
 */
@Service
public class AnalyseFinanciereIaService {

    private static final Logger log = LoggerFactory.getLogger(AnalyseFinanciereIaService.class);
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Nombre maximal de lignes de detail transmises par etat, pour contenir la taille du prompt. */
    private static final int LIMITE_LIGNES = 20;

    private final ComptabiliteService service;
    private final ChatGptClient chatGptClient;
    private final ObjectMapper objectMapper;

    public AnalyseFinanciereIaService(ComptabiliteService service, ChatGptClient chatGptClient, ObjectMapper objectMapper) {
        this.service = service;
        this.chatGptClient = chatGptClient;
        this.objectMapper = objectMapper;
    }

    /** Reponse JSON attendue du modele — memes cles que le contrat decrit dans le prompt systeme. */
    private record AnalyseIaJson(String synthese, List<String> pointsForts,
                                  List<String> pointsAttention, List<String> recommandations) {
    }

    /**
     * Schema JSON Schema de {@link AnalyseIaJson}, transmis a l'API en sortie
     * structuree : le modele ne peut alors produire qu'un JSON valide et
     * conforme, ce qui supprime le risque de reponse hors format (texte
     * d'introduction, balises markdown) qu'il fallait rattraper auparavant.
     */
    private static final Map<String, Object> SCHEMA_REPONSE = Map.of(
        "type", "object",
        "properties", Map.of(
            "synthese", Map.of("type", "string"),
            "pointsForts", Map.of("type", "array", "items", Map.of("type", "string")),
            "pointsAttention", Map.of("type", "array", "items", Map.of("type", "string")),
            "recommandations", Map.of("type", "array", "items", Map.of("type", "string"))),
        "required", List.of("synthese", "pointsForts", "pointsAttention", "recommandations"),
        "additionalProperties", false);

    @PreAuthorize("hasAnyRole('DFIN', 'ADMIN')")
    @Transactional(readOnly = true)
    public AnalyseFinanciereResponse analyser(LocalDate du, LocalDate au) {
        LocalDate fin = au != null ? au : LocalDate.now();
        LocalDate debut = du != null ? du : LocalDate.of(LocalDate.now().getYear(), 1, 1);

        BilanResponse bilan = service.bilan(fin);
        CompteResultatResponse resultat = service.compteResultat(debut, fin);
        BalanceVerificationResponse balance = service.balanceVerification(debut, fin);

        if (chatGptClient.disponible()) {
            try {
                String prompt = construirePrompt(debut, fin, bilan, resultat, balance);
                // Marge large : le prompt peut lister jusqu'a 20 lignes par etat (voir
                // LIMITE_LIGNES), et le thinking etant desactive, tout le budget va au
                // contenu — une reponse detaillee (comptes cites, plusieurs recommandations)
                // peut depasser 1600 tokens et se faire tronquer en plein milieu d'une chaine.
                String contenu = chatGptClient.json(SYSTEME, prompt, 4000, SCHEMA_REPONSE);
                AnalyseIaJson json = objectMapper.readValue(nettoyerJson(contenu), AnalyseIaJson.class);
                if (StringUtils.hasText(json.synthese())) {
                    return new AnalyseFinanciereResponse(debut, fin, json.synthese(),
                        listeOuVide(json.pointsForts()), listeOuVide(json.pointsAttention()),
                        listeOuVide(json.recommandations()), true);
                }
                log.warn("IA : reponse d'analyse financiere sans synthese exploitable, repli sur l'heuristique");
            } catch (Exception e) {
                // Englobe RuntimeException (reseau, quota, timeout) ET
                // JsonProcessingException (reponse mal formee malgre le mode
                // JSON force) : dans tous les cas, on degrade sans jamais
                // bloquer l'affichage des etats financiers.
                log.warn("IA indisponible pour l'analyse financiere ({}) : repli sur la synthese locale", e.getMessage());
            }
        }
        return analyseHeuristique(debut, fin, bilan, resultat, balance);
    }

    private List<String> listeOuVide(List<String> liste) {
        return liste == null ? List.of() : liste;
    }

    /** Retire une eventuelle cloture markdown (```json ... ```) que le modele ajouterait malgre la consigne. */
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
        Tu es un analyste financier senior, expert du referentiel comptable OHADA \
        (SYSCOHADA revise), qui conseille la direction d'une PME. On te fournit les \
        etats financiers reels d'une periode (Bilan, Compte de resultat, Balance de \
        verification), deja calcules et equilibres par le systeme comptable de \
        l'entreprise. Redige une interpretation professionnelle et actionnable, en \
        francais, a destination de la direction financiere.

        Base-toi UNIQUEMENT sur les chiffres fournis : n'invente jamais un montant. \
        Tu peux calculer et commenter des ratios simples (ex. marge nette, poids des \
        charges, part de la tresorerie) a partir des totaux donnes, en precisant \
        qu'il s'agit d'estimations indicatives.

        Reponds UNIQUEMENT en JSON valide, avec exactement ces cles :
        - "synthese" : chaine de 3 a 5 phrases resumant la situation financiere ;
        - "pointsForts" : tableau de 2 a 4 chaines ;
        - "pointsAttention" : tableau de 2 a 4 chaines (risques ou signaux a surveiller) ;
        - "recommandations" : tableau de 3 a 6 chaines, des actions concretes et priorisees.
        Pas de texte hors du JSON, pas de balises markdown.""";

    private String construirePrompt(LocalDate du, LocalDate au, BilanResponse bilan,
                                     CompteResultatResponse resultat, BalanceVerificationResponse balance) {
        StringBuilder sb = new StringBuilder();
        sb.append("Periode analysee : du ").append(du.format(DATE_FR)).append(" au ").append(au.format(DATE_FR)).append("\n\n");

        sb.append("=== BILAN (au ").append(bilan.au().format(DATE_FR)).append(") ===\n");
        sb.append("Total actif : ").append(fmt(bilan.totalActif())).append(" USD\n");
        sb.append("Total passif : ").append(fmt(bilan.totalPassif())).append(" USD\n");
        sb.append("Resultat net inclus au passif : ").append(fmt(bilan.resultatNet())).append(" USD\n");
        sb.append("Bilan equilibre : ").append(bilan.equilibre() ? "oui" : "non").append("\n\n");
        sb.append("Actif (compte - libelle : montant USD), principaux postes :\n").append(formaterLignes(bilan.actifs())).append("\n\n");
        sb.append("Passif (compte - libelle : montant USD), principaux postes :\n").append(formaterLignes(bilan.passifs())).append("\n\n");

        sb.append("=== COMPTE DE RESULTAT (du ").append(resultat.du().format(DATE_FR))
            .append(" au ").append(resultat.au().format(DATE_FR)).append(") ===\n");
        sb.append("Total produits : ").append(fmt(resultat.totalProduits())).append(" USD\n");
        sb.append("Total charges : ").append(fmt(resultat.totalCharges())).append(" USD\n");
        sb.append("Resultat net : ").append(fmt(resultat.resultatNet())).append(" USD (")
            .append(resultat.resultatNet().signum() >= 0 ? "benefice" : "perte").append(")\n\n");
        sb.append("Produits (compte - libelle : montant USD), principaux postes :\n").append(formaterLignes(resultat.produits())).append("\n\n");
        sb.append("Charges (compte - libelle : montant USD), principaux postes :\n").append(formaterLignes(resultat.charges())).append("\n\n");

        sb.append("=== BALANCE DE VERIFICATION (du ").append(balance.du().format(DATE_FR))
            .append(" au ").append(balance.au().format(DATE_FR)).append(") ===\n");
        sb.append("Total debit : ").append(fmt(balance.totalDebit())).append(" USD — Total credit : ")
            .append(fmt(balance.totalCredit())).append(" USD — Equilibree : ")
            .append(balance.equilibree() ? "oui" : "non").append("\n");
        sb.append("Nombre de comptes mouvementes : ").append(balance.lignes().size()).append("\n");
        sb.append("Plus gros soldes debiteurs :\n").append(formaterSoldes(balance.lignes(), true)).append("\n");
        sb.append("Plus gros soldes crediteurs :\n").append(formaterSoldes(balance.lignes(), false)).append("\n");

        return sb.toString();
    }

    private String formaterLignes(List<EtatFinancierLigne> lignes) {
        if (lignes == null || lignes.isEmpty()) return "(aucune)";
        return lignes.stream()
            .sorted(Comparator.comparing((EtatFinancierLigne l) -> l.montant().abs()).reversed())
            .limit(LIMITE_LIGNES)
            .map(l -> l.numero() + " - " + l.libelle() + " : " + fmt(l.montant()) + " USD")
            .collect(Collectors.joining("\n"));
    }

    private String formaterSoldes(List<LigneBalanceVerificationResponse> lignes, boolean debiteurs) {
        if (lignes == null || lignes.isEmpty()) return "(aucun)";
        return lignes.stream()
            .filter(l -> (debiteurs ? l.soldeDebiteur() : l.soldeCrediteur()).signum() > 0)
            .sorted(Comparator.comparing(
                (LigneBalanceVerificationResponse l) -> debiteurs ? l.soldeDebiteur() : l.soldeCrediteur()).reversed())
            .limit(5)
            .map(l -> l.compteNumero() + " - " + l.compteLibelle() + " : "
                + fmt(debiteurs ? l.soldeDebiteur() : l.soldeCrediteur()) + " USD")
            .collect(Collectors.joining("\n"));
    }

    private String fmt(BigDecimal montant) {
        return String.format(Locale.US, "%,.0f", montant == null ? BigDecimal.ZERO : montant);
    }

    // -----------------------------------------------------------------
    // Repli sans IA
    // -----------------------------------------------------------------

    private AnalyseFinanciereResponse analyseHeuristique(LocalDate du, LocalDate au, BilanResponse bilan,
                                                          CompteResultatResponse resultat, BalanceVerificationResponse balance) {
        boolean benefice = resultat.resultatNet().signum() >= 0;
        String synthese = "Sur la periode du " + du.format(DATE_FR) + " au " + au.format(DATE_FR)
            + ", le resultat net s'etablit a " + fmt(resultat.resultatNet()) + " USD ("
            + (benefice ? "benefice" : "perte") + "), pour un total de produits de "
            + fmt(resultat.totalProduits()) + " USD et de charges de " + fmt(resultat.totalCharges())
            + " USD. Le bilan au " + bilan.au().format(DATE_FR) + " est "
            + (bilan.equilibre() ? "equilibre" : "DESEQUILIBRE") + " (actif "
            + fmt(bilan.totalActif()) + " USD, passif " + fmt(bilan.totalPassif())
            + " USD) et la balance de verification est " + (balance.equilibree() ? "equilibree." : "DESEQUILIBREE.");

        List<String> pointsForts = benefice
            ? List.of("La periode degage un resultat net positif de " + fmt(resultat.resultatNet()) + " USD.")
            : List.of();
        List<String> pointsAttention = new java.util.ArrayList<>();
        if (!benefice) pointsAttention.add("La periode degage une perte nette de " + fmt(resultat.resultatNet().abs()) + " USD.");
        if (!bilan.equilibre()) pointsAttention.add("Le bilan n'est pas equilibre : verifier les ecritures de la periode.");
        if (!balance.equilibree()) pointsAttention.add("La balance de verification n'est pas equilibree.");
        if (pointsAttention.isEmpty()) pointsAttention.add("Aucune anomalie d'equilibre detectee sur les etats de la periode.");

        List<String> recommandations = List.of(
            "Analyse simplifiee generee sans assistance IA (service indisponible) : "
                + "activer la cle API pour obtenir une interpretation detaillee et des recommandations personnalisees.");

        return new AnalyseFinanciereResponse(du, au, synthese, pointsForts, pointsAttention, recommandations, false);
    }
}
