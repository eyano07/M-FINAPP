package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.dto.admin.SuggestionImport;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Assistance a l'import de journal : propose des corrections plutot que de se
 * contenter de refuser le fichier.
 *
 * <p><b>Le probleme observe.</b> La cause de rejet la plus frequente n'est pas
 * une erreur de saisie mais un piege du referentiel SYSCOHADA : les numeros
 * qu'on ecrit spontanement — 101 Capital, 401 Fournisseurs, 411 Clients, 681
 * Dotations — sont des comptes de <em>regroupement</em>, sur lesquels aucune
 * imputation n'est possible. Il faut descendre au sous-compte de saisie
 * (1011, 4011, 4111, 6813). Un comptable qui exporte depuis un autre outil
 * produit donc naturellement un fichier integralement rejete.</p>
 *
 * <p><b>Repartition des roles.</b> Le choix des candidats est deterministe :
 * ce sont les descendants imputables et actifs du compte fourni, lus en base.
 * L'IA n'intervient que la ou il faut trancher — 101 a cinq sous-comptes
 * possibles, et seul le libelle de l'ecriture permet de dire lequel. Elle ne
 * decide donc jamais seule d'un compte : elle choisit dans une liste deja
 * validee par la base. Quand un seul candidat existe, elle n'est meme pas
 * appelee.</p>
 *
 * <p><b>Rien n'est applique d'office.</b> Les propositions remontent a
 * l'administrateur, qui les applique explicitement. Une substitution de compte
 * change l'imputation comptable : la decision lui revient. En l'absence de cle
 * API, le service degrade en listant les candidats, sans jamais bloquer
 * l'import.</p>
 */
@Service
@RequiredArgsConstructor
public class ImportIaService {

    private static final Logger log = LoggerFactory.getLogger(ImportIaService.class);
    /** Au-dela, on n'interroge plus l'IA : le fichier a un probleme de fond. */
    private static final int MAX_SUGGESTIONS_IA = 25;

    private final CompteOHADARepository compteRepository;
    private final ChatGptClient chatGptClient;

    /**
     * Propose une substitution pour chaque compte inutilisable du fichier.
     *
     * @param comptesFautifs   numero du compte -> libelle d'ecriture rencontre dans le fichier
     * @param intitulesFichier numero du compte -> intitule porte par le fichier
     *        (colonne « Intitule du compte »). Utilise pour NOMMER un compte
     *        a creer ; le libelle d'ecriture, lui, sert a CHOISIR entre des
     *        sous-comptes existants, ou il est le seul signal pertinent.
     */
    public List<SuggestionImport> suggererComptes(Map<String, String> comptesFautifs,
                                                   Map<String, String> intitulesFichier) {
        List<SuggestionImport> suggestions = new ArrayList<>();
        int appelsIa = 0;

        for (var entree : comptesFautifs.entrySet()) {
            String numero = entree.getKey();
            String libelleLigne = entree.getValue();

            List<CompteOHADA> candidats = candidatsImputables(numero);

            if (candidats.isEmpty()) {
                suggestions.add(proposerCreation(numero, libelleLigne,
                    intitulesFichier == null ? null : intitulesFichier.get(numero)));
                continue;
            }

            if (candidats.size() == 1) {
                CompteOHADA seul = candidats.get(0);
                suggestions.add(new SuggestionImport("COMPTE", numero, seul.getNumero(), seul.getLibelle(),
                    "Seul sous-compte de saisie disponible sous " + numero + ".", false));
                continue;
            }

            // Plusieurs candidats : c'est le libelle de l'ecriture qui departage.
            Optional<CompteOHADA> choix = appelsIa < MAX_SUGGESTIONS_IA
                ? choisirParIa(numero, libelleLigne, candidats)
                : Optional.empty();
            if (choix.isPresent()) {
                appelsIa++;
                CompteOHADA c = choix.get();
                suggestions.add(new SuggestionImport("COMPTE", numero, c.getNumero(), c.getLibelle(),
                    "Choisi parmi " + candidats.size() + " sous-comptes d'apres le libelle « "
                    + abreger(libelleLigne) + " ».", true));
            } else {
                String liste = candidats.stream().limit(6)
                    .map(c -> c.getNumero() + " (" + c.getLibelle() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("");
                suggestions.add(new SuggestionImport("COMPTE", numero, null, null,
                    numero + " est un compte de regroupement. Sous-comptes possibles : " + liste
                    + ". Choisissez celui qui correspond.", false));
            }
        }
        return suggestions;
    }

    /**
     * Propose de creer le compte manquant, plutot que de renvoyer l'utilisateur
     * a une impasse.
     *
     * <p>Le rattachement se fait au plus long prefixe existant : {@code 1211}
     * se raccroche a {@code 121}, dont il heritera du type et de la classe. La
     * creation manuelle imposant la numerotation {@code parent.suffixe}, le
     * compte cree porte {@code 121.1} et non {@code 1211} — l'import substitue
     * alors l'un a l'autre. Le libelle propose est celui lu dans le fichier,
     * qui decrit deja l'usage voulu.</p>
     */
    private SuggestionImport proposerCreation(String numero, String libelleLigne, String intituleFichier) {
        Optional<CompteOHADA> parent = parentExistant(numero);
        if (parent.isEmpty()) {
            return new SuggestionImport("COMPTE", numero, null, null,
                "Compte " + numero + " inconnu et rattachable a aucun compte existant."
                + " Verifiez le numero dans votre fichier.", false);
        }
        CompteOHADA p = parent.get();
        String suffixe = numero.substring(p.getNumero().length());
        String propose = p.getNumero() + "." + suffixe;
        // Seule la colonne « Intitule du compte » peut nommer un compte : elle
        // porte bien un nom de compte. Le libelle d'ecriture, lui, decrit UNE
        // operation (« Paiement courses de service MIKE FLO ») et n'a jamais
        // ete un nom de compte — le retenir polluait durablement le plan
        // comptable, donc le bilan et tous les etats qui en tirent leurs
        // intitules. A defaut d'intitule, le compte cree herite du libelle de
        // son parent, comme il herite deja de son type et de sa classe : le
        // plan comptable reste ainsi la seule source de ses intitules.
        String libelle = StringUtils.hasText(intituleFichier)
            ? abreger(intituleFichier)
            : p.getLibelle();
        return new SuggestionImport("CREATION", numero, propose, libelle,
            "Compte absent du plan comptable. Il peut etre cree sous « " + p.getNumero()
            + " " + p.getLibelle() + " », dont il heritera du type et de la classe."
            + " Il portera le numero " + propose + " (la creation manuelle impose"
            + " la forme parent.suffixe) et les ecritures y seront rattachees.", false);
    }

    /** Plus long prefixe du numero correspondant a un compte existant. */
    private Optional<CompteOHADA> parentExistant(String numero) {
        for (int i = numero.length() - 1; i >= 2; i--) {
            Optional<CompteOHADA> c = compteRepository.findByNumero(numero.substring(0, i));
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    /**
     * Descendants directs ou indirects du compte, imputables et actifs.
     *
     * <p>Recherche par prefixe de numero : c'est ainsi que le referentiel
     * SYSCOHADA est structure (1011 est un sous-compte de 101), et cela reste
     * juste meme lorsque la relation parent/enfant n'est pas renseignee en
     * base pour un compte ajoute manuellement.</p>
     */
    private List<CompteOHADA> candidatsImputables(String numero) {
        return compteRepository.findAllByOrderByNumeroAsc().stream()
            .filter(c -> c.isImputable() && c.isActif())
            .filter(c -> c.getNumero().startsWith(numero) && !c.getNumero().equals(numero))
            .sorted(Comparator.comparing(CompteOHADA::getNumero))
            .toList();
    }

    private Optional<CompteOHADA> choisirParIa(String numero, String libelleLigne, List<CompteOHADA> candidats) {
        if (!chatGptClient.disponible() || !StringUtils.hasText(libelleLigne)) {
            return Optional.empty();
        }
        String catalogue = candidats.stream()
            .map(c -> c.getNumero() + " - " + c.getLibelle())
            .reduce((a, b) -> a + "\n" + b).orElse("");

        String systeme = "Tu es un expert-comptable du referentiel OHADA (SYSCOHADA revise)."
            + " On te donne le libelle d'une ecriture et la liste des sous-comptes de saisie"
            + " disponibles sous un compte de regroupement. Choisis le sous-compte le plus adapte."
            + " Reponds UNIQUEMENT par le numero, sans autre texte. Le numero doit figurer"
            + " tel quel dans la liste.";
        String utilisateur = "Compte de regroupement saisi : " + numero
            + "\nLibelle de l'ecriture : " + libelleLigne
            + "\n\nSous-comptes disponibles :\n" + catalogue;

        try {
            String reponse = chatGptClient.texte(systeme, utilisateur, 12);
            if (reponse == null) {
                return Optional.empty();
            }
            String propose = reponse.replaceAll("[^0-9]", "").trim();
            // On ne fait confiance a la reponse que si elle figure dans la
            // liste soumise : l'IA ne doit pas pouvoir inventer un compte.
            return candidats.stream()
                .filter(c -> c.getNumero().equals(propose))
                .findFirst();
        } catch (RuntimeException e) {
            log.warn("Assistance IA indisponible pour le compte {} : {}", numero, e.getMessage());
            return Optional.empty();
        }
    }



    /**
     * Propose une correspondance entre les en-tetes du fichier et les colonnes
     * attendues, lorsque la detection directe a echoue.
     *
     * @param enTetesFichier en-tetes lus dans le fichier
     * @param colonnesCibles colonnes attendues par l'import
     */
    public List<SuggestionImport> suggererColonnes(List<String> enTetesFichier, String[] colonnesCibles) {
        List<SuggestionImport> suggestions = new ArrayList<>();
        Map<String, String> deterministe = correspondanceLocale(enTetesFichier, colonnesCibles);

        for (String cible : colonnesCibles) {
            String trouve = deterministe.get(cible);
            if (trouve != null) {
                suggestions.add(new SuggestionImport("COLONNE", trouve, cible, null,
                    "Correspondance evidente.", false));
            }
        }
        long manquantes = colonnesCibles.length - suggestions.size();
        if (manquantes > 0) {
            String restantes = String.join(", ", enTetesFichier);
            suggestions.add(new SuggestionImport("COLONNE", restantes, null, null,
                manquantes + " colonne(s) attendue(s) non reconnue(s). Renommez les en-tetes du"
                + " fichier en : " + String.join(", ", colonnesCibles)
                + " — ou telechargez le modele et recopiez vos donnees dedans.", false));
        }
        return suggestions;
    }

    /** Rapprochement sur le texte normalise (accents, casse, espaces ignores). */
    private Map<String, String> correspondanceLocale(List<String> enTetes, String[] cibles) {
        Map<String, String> resultat = new LinkedHashMap<>();
        for (String cible : cibles) {
            String normCible = normaliser(cible);
            for (String enTete : enTetes) {
                String norm = normaliser(enTete);
                if (norm.equals(normCible) || norm.contains(normCible) || normCible.contains(norm)) {
                    if (StringUtils.hasText(norm)) {
                        resultat.put(cible, enTete);
                        break;
                    }
                }
            }
        }
        return resultat;
    }

    private String normaliser(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.FRENCH)
            .replaceAll("[^a-z0-9]", "");
    }

    private String abreger(String texte) {
        if (!StringUtils.hasText(texte)) return "";
        return texte.length() <= 60 ? texte : texte.substring(0, 60) + "...";
    }
}
