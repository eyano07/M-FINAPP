package com.mbsc.finapp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.budget.PropositionBudgetRequest;
import com.mbsc.finapp.dto.budget.PropositionBudgetResponse;
import com.mbsc.finapp.dto.budget.PropositionBudgetResponse.LigneProposee;
import com.mbsc.finapp.repository.CompteOHADARepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Proposition d'un budget annuel a partir des donnees reelles du systeme (grand livre).
 *
 * <p>Periode de reference : l'exercice precedent s'il a des ecritures (douze mois, saisonnalite reelle) ;
 * sinon l'exercice vise lui-meme, de janvier au mois ecoule, annualise. Les comptes mouvementes sont regroupes
 * au niveau du compte a trois chiffres du plan SYSCOHADA (601 Achats de marchandises, 604, 622...), lisible
 * et suffisant pour le controle (une ligne couvre ses sous-comptes).</p>
 *
 * <p>Avec une cle OpenAI, le modele recoit ces chiffres reels et les hypotheses de la direction, et propose
 * montants, ventilation mensuelle et justification par ligne ; ses comptes et montants sont reverifies (comptes
 * existants et budgetables, mois positifs, somme exacte). Sans cle, ou si l'appel echoue, la meme methode est
 * appliquee localement (reconduction du realise de reference avec sa saisonnalite). Rien n'est enregistre : le
 * DFIN relit et corrige dans l'editeur avant d'enregistrer.</p>
 */
@Service
public class PropositionBudgetIaService {

    private static final Logger log = LoggerFactory.getLogger(PropositionBudgetIaService.class);
    private static final Locale FR = Locale.FRANCE;
    private static final int MAX_LIGNES = 60;

    private final SuiviBudgetaireService suivi;
    private final CompteOHADARepository compteRepository;
    private final ChatGptClient chatGptClient;
    private final ObjectMapper objectMapper;

    public PropositionBudgetIaService(SuiviBudgetaireService suivi, CompteOHADARepository compteRepository,
                                      ChatGptClient chatGptClient, ObjectMapper objectMapper) {
        this.suivi = suivi;
        this.compteRepository = compteRepository;
        this.chatGptClient = chatGptClient;
        this.objectMapper = objectMapper;
    }

    /** Realise de reference d'un compte a trois chiffres. */
    record Reference(CompteOHADA compte, BigDecimal[] mensuel, BigDecimal annuel, BigDecimal annualise, BigDecimal[] profil) {
    }

    @PreAuthorize(BudgetService.ECRITURE)
    @Transactional(readOnly = true)
    public PropositionBudgetResponse proposer(PropositionBudgetRequest req) {
        int exercice = req.exercice();
        List<String> avertissements = new ArrayList<>();
        Map<String, CompteOHADA> plan = compteRepository.findAllByOrderByNumeroAsc().stream()
            .collect(Collectors.toMap(CompteOHADA::getNumero, Function.identity(), (a, b) -> a));

        // 1. Periode de reference
        Map<String, SuiviBudgetaireService.MouvementsCompte> mouvements = suivi.mouvementsDeLExercice(exercice - 1);
        int moisReference = 12;
        String periode = "réalisé de l'exercice " + (exercice - 1);
        boolean saisonnalite = true;
        int ecoulesPrecedent = SuiviBudgetaireService.moisEcoules(exercice - 1);
        if (!mouvements.isEmpty() && ecoulesPrecedent < 12) {
            // Exercice precedent encore en cours : ses derniers mois manquent, on annualise au lieu de les compter pour zero.
            moisReference = Math.max(1, ecoulesPrecedent);
            saisonnalite = false;
            periode = "réalisé de janvier à " + Month.of(moisReference).getDisplayName(TextStyle.FULL, FR) + " " + (exercice - 1)
                + ", annualisé";
        }
        if (mouvements.isEmpty()) {
            int ecoules = SuiviBudgetaireService.moisEcoules(exercice);
            if (ecoules > 0) {
                mouvements = suivi.mouvementsDeLExercice(exercice);
                moisReference = ecoules;
                saisonnalite = false;
                periode = "réalisé de janvier à " + Month.of(ecoules).getDisplayName(TextStyle.FULL, FR) + " " + exercice
                    + ", annualisé";
            }
        }
        Map<String, Reference> references = references(mouvements, plan, moisReference, saisonnalite);
        if (references.isEmpty()) {
            avertissements.add("Aucune écriture comptable exploitable (classes 2, 6, 7, 8) en " + (exercice - 1) + " ni en "
                + exercice + " : saisissez le budget manuellement, ou importez d'abord le journal.");
            return new PropositionBudgetResponse(exercice, "CALCUL_LOCAL", periode,
                "Aucune donnée réelle disponible pour fonder une proposition.", List.of(), avertissements);
        }

        // 2. Proposition par l'IA, sinon calcul local
        if (chatGptClient.disponible()) {
            try {
                PropositionBudgetResponse ia = parIa(exercice, req.hypotheses(), periode, references, plan, avertissements);
                if (ia != null) {
                    return ia;
                }
                avertissements.add("La réponse de l'IA était inexploitable : proposition calculée localement.");
            } catch (Exception e) {
                log.warn("IA indisponible pour la proposition de budget ({}) : repli sur le calcul local", e.getMessage());
                avertissements.add("IA indisponible (" + e.getClass().getSimpleName() + ") : proposition calculée localement.");
            }
        } else {
            avertissements.add("IA non configurée (clé OpenAI absente) : proposition calculée localement, par reconduction du réalisé.");
        }
        return localement(exercice, periode, references, saisonnalite, avertissements);
    }

    // ---------------------------------------------------------------------
    // Donnees de reference
    // ---------------------------------------------------------------------

    private Map<String, Reference> references(Map<String, SuiviBudgetaireService.MouvementsCompte> mouvements,
                                              Map<String, CompteOHADA> plan, int moisReference, boolean saisonnalite) {
        Map<String, BigDecimal[]> parCompte = new TreeMap<>();
        for (SuiviBudgetaireService.MouvementsCompte m : mouvements.values()) {
            Integer classe = SuiviBudgetaireService.classe(m.numero());
            if (!MoteurBudgetaire.estBudgetable(m.numero(), classe)) {
                continue;
            }
            String regroupement = m.numero().length() >= 3 ? m.numero().substring(0, 3) : m.numero();
            CompteOHADA compte = plan.get(regroupement);
            if (compte == null || !MoteurBudgetaire.estBudgetable(compte.getNumero(), compte.getClasse())) {
                continue;
            }
            TypeCompte type = compte.getType() != null ? compte.getType() : m.type();
            parCompte.merge(regroupement, m.realiseSelon(type), MoteurBudgetaire::additionner);
        }
        Map<String, Reference> refs = new LinkedHashMap<>();
        parCompte.forEach((numero, mensuel) -> {
            BigDecimal annuel = MoteurBudgetaire.total(mensuel);
            if (annuel.signum() <= 0) {
                return;   // compte solde ou crediteur sur la periode (extournes, avoirs) : rien a reconduire
            }
            BigDecimal annualise = moisReference >= 12 ? annuel
                : annuel.multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(moisReference), 0, RoundingMode.HALF_UP);
            refs.put(numero, new Reference(plan.get(numero), mensuel, annuel, annualise, saisonnalite ? mensuel : null));
        });
        // Les comptes les plus importants d'abord, dans la limite d'une proposition lisible.
        return refs.values().stream()
            .sorted(Comparator.comparing(Reference::annualise).reversed())
            .limit(MAX_LIGNES)
            .sorted(Comparator.comparing(r -> r.compte().getNumero()))
            .collect(Collectors.toMap(r -> r.compte().getNumero(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    // ---------------------------------------------------------------------
    // Calcul local
    // ---------------------------------------------------------------------

    private PropositionBudgetResponse localement(int exercice, String periode, Map<String, Reference> refs,
                                                 boolean saisonnalite, List<String> avertissements) {
        List<LigneProposee> lignes = refs.values().stream().map(r -> {
            BigDecimal annuel = arrondiUnite(r.annualise());
            BigDecimal[] mensuel = MoteurBudgetaire.repartirSelonProfil(annuel, r.profil());
            String justification = saisonnalite
                ? "Reconduction du réalisé " + (exercice - 1) + " (" + fmt(r.annuel()) + " USD), avec sa saisonnalité mensuelle."
                : "Réalisé de l'exercice en cours (" + fmt(r.annuel()) + " USD) annualisé, réparti à parts égales.";
            return ligne(r.compte(), mensuel, r.annuel(), justification);
        }).toList();
        return new PropositionBudgetResponse(exercice, "CALCUL_LOCAL", periode, syntheseLocale(lignes, periode), lignes, avertissements);
    }

    private String syntheseLocale(List<LigneProposee> lignes, String periode) {
        BigDecimal produits = totalSection(lignes, "PRODUITS");
        BigDecimal charges = totalSection(lignes, "CHARGES");
        BigDecimal invest = totalSection(lignes, "INVESTISSEMENTS");
        return "Proposition fondée sur le " + periode + " : produits " + fmt(produits) + " USD, charges " + fmt(charges)
            + " USD, résultat prévisionnel " + fmt(produits.subtract(charges)) + " USD"
            + (invest.signum() > 0 ? ", investissements " + fmt(invest) + " USD" : "")
            + ". Chaque ligne reconduit le réalisé ; ajustez-les selon vos objectifs (croissance, recrutements, investissements)"
            + " avant d'enregistrer.";
    }

    // ---------------------------------------------------------------------
    // IA
    // ---------------------------------------------------------------------

    private record LigneIa(String compte, BigDecimal montantAnnuel, List<BigDecimal> mensuel, String justification) {
    }

    private record PropositionIa(String synthese, List<LigneIa> lignes) {
    }

    private static final Map<String, Object> SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
            "synthese", Map.of("type", "string"),
            "lignes", Map.of("type", "array", "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "compte", Map.of("type", "string"),
                    "montantAnnuel", Map.of("type", "number"),
                    "mensuel", Map.of("type", "array", "items", Map.of("type", "number")),
                    "justification", Map.of("type", "string")),
                "required", List.of("compte", "montantAnnuel", "mensuel", "justification"),
                "additionalProperties", false))),
        "required", List.of("synthese", "lignes"),
        "additionalProperties", false);

    private static final String SYSTEME = """
        Tu es directeur financier, expert du SYSCOHADA revise et du controle budgetaire d'une PME en Republique \
        democratique du Congo. On te donne le realise comptable reel de l'entreprise (grand livre), par compte a \
        trois chiffres du plan SYSCOHADA et par mois, en USD. Propose le budget annuel de l'exercice demande.

        Regles :
        - Une ligne par compte, choisi dans la liste fournie (tu peux ajouter un compte de la meme classe seulement \
          si les hypotheses de la direction l'exigent, avec un numero a trois chiffres du plan SYSCOHADA).
        - Pars toujours du realise fourni ; ajuste-le seulement pour une raison explicite (tendance visible dans les \
          mois, hypotheses de la direction, inflation raisonnable). N'invente aucun chiffre sans justification.
        - "mensuel" : exactement 12 montants positifs ou nuls, de janvier a decembre, dont la somme vaut \
          "montantAnnuel" ; reprends la saisonnalite observee quand elle existe.
        - Montants en USD, arrondis a l'unite.
        - "justification" : une phrase en francais par ligne (base de calcul, evolution retenue).
        - "synthese" : 3 a 5 phrases en francais (equilibre produits/charges, resultat previsionnel, points de \
          vigilance).
        Reponds uniquement en JSON conforme au schema.""";

    private PropositionBudgetResponse parIa(int exercice, String hypotheses, String periode, Map<String, Reference> refs,
                                            Map<String, CompteOHADA> plan, List<String> avertissements) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Exercice a budgeter : ").append(exercice).append('\n');
        sb.append("Periode de reference : ").append(periode).append('\n');
        if (StringUtils.hasText(hypotheses)) {
            sb.append("Hypotheses de la direction : ").append(hypotheses.trim()).append('\n');
        }
        sb.append("\nRealise par compte (compte | libelle | section | total de la periode | 12 mois janvier->decembre) :\n");
        for (Reference r : refs.values()) {
            sb.append(r.compte().getNumero()).append(" | ").append(r.compte().getLibelle()).append(" | ")
                .append(MoteurBudgetaire.section(r.compte().getNumero(), r.compte().getType()).name()).append(" | ")
                .append(r.annuel().setScale(0, RoundingMode.HALF_UP)).append(" | ")
                .append(Arrays.stream(r.mensuel()).map(v -> v.setScale(0, RoundingMode.HALF_UP).toPlainString())
                    .collect(Collectors.joining(" ")))
                .append('\n');
        }
        String contenu = chatGptClient.jsonAnalyse(SYSTEME, sb.toString(), 6000, SCHEMA);
        if (contenu == null) {
            return null;
        }
        PropositionIa ia = objectMapper.readValue(nettoyer(contenu), PropositionIa.class);
        if (ia == null || ia.lignes() == null || ia.lignes().isEmpty()) {
            return null;
        }

        // Reverification de tout ce que propose le modele.
        List<LigneProposee> lignes = new ArrayList<>();
        List<String> retenus = new ArrayList<>();
        for (LigneIa l : ia.lignes()) {
            String numero = l.compte() == null ? "" : l.compte().trim();
            CompteOHADA compte = plan.get(numero);
            if (compte == null || !MoteurBudgetaire.estBudgetable(numero, compte.getClasse())) {
                avertissements.add("Compte " + numero + " proposé par l'IA écarté : absent du plan comptable ou non budgétable.");
                continue;
            }
            if (MoteurBudgetaire.ligneCouvrant(numero, retenus) != null || retenus.stream().anyMatch(r -> r.startsWith(numero))) {
                avertissements.add("Compte " + numero + " proposé par l'IA écarté : il recouvre une autre ligne.");
                continue;
            }
            BigDecimal annuel = l.montantAnnuel() == null ? BigDecimal.ZERO : l.montantAnnuel().max(BigDecimal.ZERO);
            BigDecimal[] mensuel = mensuelValide(l.mensuel());
            if (mensuel == null) {
                Reference r = refs.get(numero);
                mensuel = MoteurBudgetaire.repartirSelonProfil(arrondiUnite(annuel), r == null ? null : r.profil());
            }
            Reference r = refs.get(numero);
            retenus.add(numero);
            lignes.add(ligne(compte, mensuel, r == null ? BigDecimal.ZERO : r.annuel(),
                StringUtils.hasText(l.justification()) ? l.justification().trim() : "Proposition de l'IA."));
        }
        if (lignes.isEmpty()) {
            return null;
        }
        lignes.sort(Comparator.comparing(LigneProposee::compteNumero));
        return new PropositionBudgetResponse(exercice, "IA", periode,
            StringUtils.hasText(ia.synthese()) ? ia.synthese().trim() : syntheseLocale(lignes, periode), lignes, avertissements);
    }

    /** Douze montants positifs ou nuls, arrondis au centime ; null si la ventilation est inexploitable. */
    private static BigDecimal[] mensuelValide(List<BigDecimal> mensuel) {
        if (mensuel == null || mensuel.size() != MoteurBudgetaire.MOIS) {
            return null;
        }
        BigDecimal[] m = new BigDecimal[MoteurBudgetaire.MOIS];
        for (int i = 0; i < MoteurBudgetaire.MOIS; i++) {
            BigDecimal v = mensuel.get(i);
            if (v == null || v.signum() < 0) {
                return null;
            }
            m[i] = v.setScale(2, RoundingMode.HALF_UP);
        }
        return m;
    }

    // ---------------------------------------------------------------------

    private LigneProposee ligne(CompteOHADA compte, BigDecimal[] mensuel, BigDecimal realiseReference, String justification) {
        return new LigneProposee(compte.getNumero(), compte.getLibelle(),
            MoteurBudgetaire.section(compte.getNumero(), compte.getType()).name(),
            Arrays.asList(mensuel), MoteurBudgetaire.total(mensuel), realiseReference, justification);
    }

    private static BigDecimal totalSection(List<LigneProposee> lignes, String section) {
        return lignes.stream().filter(l -> section.equals(l.section())).map(LigneProposee::montantAnnuel)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal arrondiUnite(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String nettoyer(String contenu) {
        String s = contenu.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").strip();
        }
        return s;
    }

    private static String fmt(BigDecimal v) {
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(FR);
        f.setMaximumFractionDigits(0);
        return f.format(v == null ? BigDecimal.ZERO : v);
    }
}
