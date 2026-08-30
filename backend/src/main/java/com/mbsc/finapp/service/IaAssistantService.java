package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.dto.ia.SuggestionCompteResponse;
import com.mbsc.finapp.repository.CompteOHADARepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent IA (ChatGPT, API Chat Completions OpenAI) pour deux usages metier :
 * <ol>
 *   <li>suggerer le compte OHADA le plus adapte a partir du motif (description)
 *       saisi sur une ligne de note de frais ;</li>
 *   <li>reformuler le libelle d'une transaction de caisse (paiement d'une note)
 *       avec un vocabulaire comptable explicite pour le journal.</li>
 * </ol>
 *
 * <p>Le service degrade automatiquement vers une heuristique locale (mots-cles
 * sur le libelle des comptes / gabarit de phrase) si aucune cle API n'est
 * configuree, si l'IA est desactivee, ou si l'appel echoue (reseau, quota,
 * timeout) : la fonctionnalite reste disponible en continu, sans jamais
 * bloquer le circuit metier (creation de note, paiement en caisse).</p>
 */
@Service
public class IaAssistantService {

    private static final Logger log = LoggerFactory.getLogger(IaAssistantService.class);
    /** Longueur retenue par definition du referentiel transmise a l'IA. */
    private static final int LONGUEUR_DEFINITION = 260;

    private static final String COMPTE_CHARGES_DIVERSES = "6588";
    /**
     * Compte de repli d'un encaissement non reconnu.
     *
     * <p>7588 et non 758 : le referentiel SYSCOHADA subdivise 758 en
     * 7581/7582/7588, ce qui en fait un compte de regroupement, sur lequel
     * aucune imputation n'est possible.</p>
     */
    private static final String COMPTE_PRODUITS_DIVERS = "7588";
    private static final Set<String> MOTS_VIDES = Set.of(
        "de", "du", "des", "la", "le", "les", "un", "une", "et", "pour", "en",
        "au", "aux", "sur", "avec", "dans", "a", "l", "d", "ou", "par", "ce", "cette");

    private final CompteOHADARepository compteRepository;
    private final ChatGptClient chatGptClient;

    public IaAssistantService(CompteOHADARepository compteRepository, ChatGptClient chatGptClient) {
        this.compteRepository = compteRepository;
        this.chatGptClient = chatGptClient;
    }

    private boolean iaDisponible() {
        return chatGptClient.disponible();
    }

    // -----------------------------------------------------------------
    // 1. Suggestion de compte OHADA a partir du motif de depense
    // -----------------------------------------------------------------

    /** Suggestion pour un decaissement (motif de depense -> compte de charge), usage historique. */
    @PreAuthorize("hasAnyRole('DIRECTEUR', 'CAISSIER', 'DFIN', 'DA', 'ADMIN')")
    public SuggestionCompteResponse suggererCompte(String description) {
        return suggererCompte(description, SensTransaction.DECAISSEMENT);
    }

    /**
     * Suggere le compte OHADA le plus adapte a partir d'un motif, selon le
     * sens de l'operation : DECAISSEMENT -> compte de charge (motif de
     * depense), ENCAISSEMENT -> compte de produit/passif (motif de recette,
     * ex. "Vente de minerais").
     */
    @PreAuthorize("hasAnyRole('DIRECTEUR', 'CAISSIER', 'DFIN', 'DA', 'ADMIN')")
    public SuggestionCompteResponse suggererCompte(String description, SensTransaction sens) {
        boolean estEncaissement = sens == SensTransaction.ENCAISSEMENT;
        String compteParDefaut = estEncaissement ? COMPTE_PRODUITS_DIVERS : COMPTE_CHARGES_DIVERSES;
        List<CompteOHADA> tousLesComptes = compteRepository.findAllByOrderByNumeroAsc();
        List<CompteOHADA> comptesCandidats = tousLesComptes.stream()
            .filter(c -> c.isImputable() && c.isActif())
            .filter(c -> estEncaissement
                ? (c.getType() == TypeCompte.PRODUIT || c.getType() == TypeCompte.PASSIF)
                : c.getType() == TypeCompte.CHARGE)
            .toList();

        if (iaDisponible()) {
            try {
                String definitions = definitionsReferentiel(tousLesComptes, comptesCandidats);
                String numero = appellerIaPourCompte(description, comptesCandidats, estEncaissement,
                    compteParDefaut, definitions);
                Optional<CompteOHADA> compte = compteRepository.findByNumero(numero);
                if (compte.isPresent()) {
                    return new SuggestionCompteResponse(compte.get().getNumero(), compte.get().getLibelle(), true);
                }
                log.warn("IA : compte suggere '{}' introuvable dans le plan comptable, repli sur l'heuristique", numero);
            } catch (RuntimeException e) {
                log.warn("IA indisponible pour la suggestion de compte ({}) : repli sur l'heuristique locale",
                    e.getMessage());
            }
        }
        return suggestionHeuristique(description, comptesCandidats, compteParDefaut);
    }

    private String appellerIaPourCompte(String description, List<CompteOHADA> comptes,
                                            boolean estEncaissement, String compteParDefaut,
                                            String definitions) {
        String catalogue = comptes.stream()
            .map(c -> c.getNumero() + " - " + c.getLibelle())
            .collect(Collectors.joining("\n"));

        String natureMotif = estEncaissement ? "le motif d'un encaissement (recette)" : "le motif d'une depense";
        String natureComptes = estEncaissement ? "comptes de produits/passif" : "comptes de charges";
        String systeme = "Tu es un expert-comptable specialise dans le referentiel OHADA (SYSCOHADA revise). "
            + "On te fournit " + natureMotif + " d'entreprise, les definitions officielles des familles "
            + "de comptes, et la liste des " + natureComptes + " "
            + "disponibles dans le plan comptable (format \"numero - libelle\"). "
            + "Appuie-toi sur les definitions officielles pour choisir la bonne famille, puis "
            + "sur les libelles pour choisir le compte precis. "
            + "Reponds UNIQUEMENT avec le numero du compte le plus adapte, sans aucun autre texte, "
            + "sans ponctuation ni explication. Le numero doit figurer tel quel dans la liste. "
            + "Si aucun compte ne correspond precisement, reponds \"" + compteParDefaut + "\".";
        String utilisateur = (estEncaissement ? "Motif d'encaissement : " : "Motif de depense : ") + description
            + (StringUtils.hasText(definitions)
               ? "\n\nDefinitions officielles du referentiel :\n" + definitions : "")
            + "\n\nComptes disponibles :\n" + catalogue;

        String contenu = chatGptClient.texte(systeme, utilisateur, 12);
        return nettoyerNumeroCompte(contenu, compteParDefaut);
    }

    /**
     * Definitions officielles des familles de comptes concernees par la
     * suggestion.
     *
     * <p>Les comptes candidats sont des comptes de saisie (feuilles), qui ne
     * portent pas de commentaire : c'est leur compte principal qui porte la
     * rubrique « Contenu » du referentiel. On remonte donc de chaque candidat
     * vers son ancetre commente, et on ne transmet que ces definitions, une
     * seule fois chacune et tronquees : l'objectif est d'orienter le choix de
     * la famille sans faire exploser la taille du prompt.</p>
     */
    private String definitionsReferentiel(List<CompteOHADA> tous, List<CompteOHADA> candidats) {
        Map<String, String> commentes = tous.stream()
            .filter(c -> StringUtils.hasText(c.getContenu()))
            .collect(Collectors.toMap(CompteOHADA::getNumero,
                c -> c.getNumero() + " - " + c.getLibelle() + " : " + resumer(c.getContenu()),
                (a, b) -> a, LinkedHashMap::new));

        return candidats.stream()
            .map(c -> ancetreCommente(c.getNumero(), commentes.keySet()))
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .map(commentes::get)
            .collect(Collectors.joining("\n"));
    }

    /** Plus long prefixe du numero qui corresponde a un compte commente. */
    private String ancetreCommente(String numero, Set<String> commentes) {
        for (int i = numero.length(); i >= 2; i--) {
            String prefixe = numero.substring(0, i);
            if (commentes.contains(prefixe)) {
                return prefixe;
            }
        }
        return null;
    }

    private String resumer(String texte) {
        String plat = texte.replaceAll("\\s+", " ").strip();
        return plat.length() <= LONGUEUR_DEFINITION ? plat
            : plat.substring(0, LONGUEUR_DEFINITION).trim() + "...";
    }

    private String nettoyerNumeroCompte(String contenu, String compteParDefaut) {
        if (contenu == null) return compteParDefaut;
        String nettoye = contenu.replaceAll("[^0-9]", "").trim();
        return nettoye.isEmpty() ? compteParDefaut : nettoye;
    }

    /** Repli sans IA : recoupement des mots du motif avec les libelles des comptes. */
    private SuggestionCompteResponse suggestionHeuristique(String description, List<CompteOHADA> comptes,
                                                            String compteParDefaut) {
        Set<String> motsMotif = motsSignificatifs(description);
        if (!motsMotif.isEmpty()) {
            Optional<CompteOHADA> meilleur = comptes.stream()
                .map(c -> Map.entry(c, scoreCorrespondance(motsMotif, motsSignificatifs(c.getLibelle()))))
                .filter(e -> e.getValue() > 0)
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey);
            if (meilleur.isPresent()) {
                CompteOHADA c = meilleur.get();
                return new SuggestionCompteResponse(c.getNumero(), c.getLibelle(), false);
            }
        }
        return compteRepository.findByNumero(compteParDefaut)
            .map(c -> new SuggestionCompteResponse(c.getNumero(), c.getLibelle(), false))
            .orElse(new SuggestionCompteResponse(compteParDefaut, "Compte par defaut", false));
    }

    private int scoreCorrespondance(Set<String> a, Set<String> b) {
        return (int) a.stream().filter(b::contains).count();
    }

    private Set<String> motsSignificatifs(String texte) {
        if (!StringUtils.hasText(texte)) return Set.of();
        return Arrays.stream(texte.toLowerCase(Locale.FRENCH).split("[^a-zàâçéèêëîïôûùüÿñæœ0-9]+"))
            .filter(m -> m.length() > 2 && !MOTS_VIDES.contains(m))
            .collect(Collectors.toSet());
    }

    // -----------------------------------------------------------------
    // 2. Reformulation du libelle de paiement (journal de caisse)
    // -----------------------------------------------------------------

    /**
     * Reformule le libelle d'un decaissement de caisse pour qu'il soit
     * explicite et redige avec le vocabulaire comptable usuel, a partir de
     * la reference de la piece, de l'objet de la note et du detail des
     * lignes de depense (compte + description).
     */
    public String reformulerLibellePaiement(String reference, String objet, String detailLignes) {
        String gabaritLocal = libelleParDefaut(reference, objet, detailLignes);
        if (!iaDisponible()) {
            return gabaritLocal;
        }
        try {
            String systeme = "Tu es un comptable senior. Reformule un libelle d'ecriture de tresorerie "
                + "(decaissement de caisse) pour qu'il soit clair, professionnel et explicite, en francais, "
                + "avec le vocabulaire comptable usuel (reglement, decaissement, paiement...). "
                + "Reponds UNIQUEMENT avec le libelle final sur une seule ligne, sans guillemets, "
                + "120 caracteres maximum, sans mention de montant ni de devise.";
            String utilisateur = "Reference piece : " + reference
                + "\nObjet de la note de frais : " + (StringUtils.hasText(objet) ? objet : "-")
                + "\nDetail des depenses : " + (StringUtils.hasText(detailLignes) ? detailLignes : "-");

            String reformule = chatGptClient.texte(systeme, utilisateur, 120);
            if (StringUtils.hasText(reformule)) {
                String nettoye = reformule.strip().replaceAll("^\"|\"$", "");
                return nettoye.length() > 200 ? nettoye.substring(0, 200) : nettoye;
            }
        } catch (RuntimeException e) {
            log.warn("IA indisponible pour la reformulation du libelle ({}) : repli sur le gabarit local",
                e.getMessage());
        }
        return gabaritLocal;
    }

    private String libelleParDefaut(String reference, String objet, String detailLignes) {
        String detail = StringUtils.hasText(detailLignes) ? detailLignes : objet;
        return "Reglement note de frais " + reference
            + (StringUtils.hasText(detail) ? " - " + detail : "");
    }
}
