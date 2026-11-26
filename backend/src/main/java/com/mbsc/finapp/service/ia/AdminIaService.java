package com.mbsc.finapp.service.ia;

import com.mbsc.finapp.domain.ParametresIa;
import com.mbsc.finapp.dto.ia.IaEnregistrerRequest;
import com.mbsc.finapp.dto.ia.IaEtatResponse;
import com.mbsc.finapp.dto.ia.IaEtatResponse.Fournisseur;
import com.mbsc.finapp.dto.ia.IaModelesResponse;
import com.mbsc.finapp.dto.ia.IaTestResponse;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Administration de l'IA : saisie des clés OpenAI et Anthropic (vérifiées avant d'être enregistrées), choix du
 * modèle de chacun dans la liste du fournisseur, test, état. Réservé à l'administrateur.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminIaService {

    private static final Logger log = LoggerFactory.getLogger(AdminIaService.class);

    private final ParametresIaService parametres;
    private final OpenAiFournisseur openai;
    private final AnthropicFournisseur anthropic;
    private final IaRouteur routeur;
    private final CurrentUserProvider currentUser;

    public IaEtatResponse etat() {
        ConfigIa c = parametres.config();
        ParametresIa ligne = parametres.ligne();
        boolean openaiEnv = parametres.openaiViaEnvironnement();
        String finOpenai = openaiEnv ? fin(c.openaiCle()) : ligne == null ? null : ligne.getOpenaiCleFin();
        String etatAnthropic = !c.anthropicConfigure() ? "NON_CONFIGURE"
            : c.anthropicEpuiseDepuis() != null ? "CREDIT_EPUISE"
            : c.anthropicRefuseeDepuis() != null ? "CLE_REFUSEE" : "ACTIF";
        return new IaEtatResponse(!parametres.iaActive(), List.of(
            new Fournisseur("openai", "OpenAI (ChatGPT)",
                "Toutes les fonctions IA (suggestion de compte, libellés, import, lecture des relevés) et repli des analyses.",
                c.openaiConfigure(), finOpenai, openaiEnv, c.openaiModele(), ParametresIa.MODELE_OPENAI_DEFAUT,
                c.openaiConfigure() ? "ACTIF" : "NON_CONFIGURE", null),
            new Fournisseur("anthropic", "Anthropic (Claude)",
                "Analyses uniquement : états financiers, restaurant, proposition de budget. OpenAI prend le relais si le crédit est épuisé.",
                c.anthropicConfigure(), ligne == null ? null : ligne.getAnthropicCleFin(), false, c.anthropicModele(),
                ParametresIa.MODELE_ANTHROPIC_DEFAUT, etatAnthropic,
                c.anthropicEpuiseDepuis() != null ? c.anthropicEpuiseDepuis() : c.anthropicRefuseeDepuis())));
    }

    /**
     * Enregistre une nouvelle clé (vérifiée auprès du fournisseur : refusée, elle n'est pas enregistrée) et/ou un
     * nouveau modèle (vérifié par un appel minimal).
     */
    public IaEtatResponse enregistrer(String fournisseur, IaEnregistrerRequest req) {
        FournisseurIa f = fournisseur(fournisseur);
        boolean estAnthropic = f == anthropic;
        ConfigIa c = parametres.config();
        String nouvelleCle = StringUtils.hasText(req.cle()) ? req.cle().trim() : null;
        String modele = StringUtils.hasText(req.modele()) ? req.modele().trim() : null;
        if (nouvelleCle == null && modele == null) {
            throw new IllegalArgumentException("Indiquez une clé et/ou un modèle.");
        }
        String cleEffective = nouvelleCle != null ? nouvelleCle : estAnthropic ? c.anthropicCle() : c.openaiCle();
        if (!StringUtils.hasText(cleEffective)) {
            throw new IllegalArgumentException("Enregistrez d'abord la clé " + f.nom() + " : le modèle se choisit ensuite.");
        }
        if (nouvelleCle != null) {
            verifier(f, () -> f.listerModeles(nouvelleCle));
        }
        String modeleEffectif = modele != null ? modele : estAnthropic ? c.anthropicModele() : c.openaiModele();
        if (modele != null || nouvelleCle != null) {
            // Un modèle inconnu ou sans accès pour cette clé est refusé ici, pas au premier appel d'un utilisateur.
            verifier(f, () -> f.tester(cleEffective, modeleEffectif));
        }
        var auteur = currentUser.requireUser();
        parametres.modifier(p -> {
            if (estAnthropic) {
                if (nouvelleCle != null) {
                    p.setAnthropicCleChiffree(parametres.chiffrer(nouvelleCle));
                    p.setAnthropicCleFin(fin(nouvelleCle));
                    p.setAnthropicEpuiseDepuis(null);
                    p.setAnthropicRefuseeDepuis(null);
                }
                p.setAnthropicModele(modeleEffectif);
            } else {
                if (nouvelleCle != null) {
                    p.setOpenaiCleChiffree(parametres.chiffrer(nouvelleCle));
                    p.setOpenaiCleFin(fin(nouvelleCle));
                }
                p.setOpenaiModele(modeleEffectif);
            }
        }, auteur);
        if (estAnthropic) routeur.reessayerAnthropic();
        log.info("Parametres IA modifies [fournisseur={}, cle={}, modele={}, par={}]",
            f.nom(), nouvelleCle != null ? "remplacee" : "inchangee", modeleEffectif, auteur.getEmail());
        return etat();
    }

    public IaEtatResponse retirerCle(String fournisseur) {
        FournisseurIa f = fournisseur(fournisseur);
        var auteur = currentUser.requireUser();
        parametres.modifier(p -> {
            if (f == anthropic) {
                p.setAnthropicCleChiffree(null);
                p.setAnthropicCleFin(null);
                p.setAnthropicEpuiseDepuis(null);
                p.setAnthropicRefuseeDepuis(null);
            } else {
                p.setOpenaiCleChiffree(null);
                p.setOpenaiCleFin(null);
            }
        }, auteur);
        log.info("Cle IA retiree [fournisseur={}, par={}]", f.nom(), auteur.getEmail());
        return etat();
    }

    public IaModelesResponse modeles(String fournisseur) {
        FournisseurIa f = fournisseur(fournisseur);
        ConfigIa c = parametres.config();
        String cle = f == anthropic ? c.anthropicCle() : c.openaiCle();
        if (!StringUtils.hasText(cle)) {
            throw new IllegalArgumentException("Enregistrez d'abord la clé " + f.nom() + " pour choisir un modèle.");
        }
        List<String> liste = new ArrayList<>(verifier(f, () -> f.listerModeles(cle)));
        String actuel = f == anthropic ? c.anthropicModele() : c.openaiModele();
        String defaut = f == anthropic ? ParametresIa.MODELE_ANTHROPIC_DEFAUT : ParametresIa.MODELE_OPENAI_DEFAUT;
        // Le modèle retenu reste proposé même si le fournisseur ne le liste plus (retiré, alias).
        if (!liste.contains(actuel)) liste.add(0, actuel);
        if (!liste.contains(defaut)) liste.add(defaut);
        return new IaModelesResponse(actuel, defaut, liste);
    }

    /** Appel minimal avec la clé et le modèle enregistrés ; ne lève jamais d'erreur du fournisseur, la décrit. */
    public IaTestResponse tester(String fournisseur) {
        FournisseurIa f = fournisseur(fournisseur);
        ConfigIa c = parametres.config();
        String cle = f == anthropic ? c.anthropicCle() : c.openaiCle();
        String modele = f == anthropic ? c.anthropicModele() : c.openaiModele();
        if (!StringUtils.hasText(cle)) {
            return new IaTestResponse(false, "Aucune clé " + f.nom() + " enregistrée.");
        }
        try {
            f.tester(cle, modele);
        } catch (ErreurIa e) {
            if (f == anthropic) {
                if (e.genre() == ErreurIa.Genre.CREDIT_EPUISE) parametres.marquerAnthropicEpuise();
                if (e.genre() == ErreurIa.Genre.CLE_REFUSEE) parametres.marquerAnthropicRefusee();
            }
            return new IaTestResponse(false, message(f, e));
        }
        if (f == anthropic) {
            parametres.marquerAnthropicRetabli();
            routeur.reessayerAnthropic();
        }
        return new IaTestResponse(true, f.nom() + " répond correctement avec le modèle « " + modele + " ».");
    }

    // ---------------------------------------------------------------------

    private <T> T verifier(FournisseurIa f, java.util.function.Supplier<T> appel) {
        try {
            return appel.get();
        } catch (ErreurIa e) {
            throw new TransitionInvalideException(message(f, e));
        }
    }

    private void verifier(FournisseurIa f, Runnable appel) {
        verifier(f, () -> {
            appel.run();
            return null;
        });
    }

    private static String message(FournisseurIa f, ErreurIa e) {
        return switch (e.genre()) {
            case CREDIT_EPUISE -> "Le crédit " + f.nom() + " est épuisé : rechargez-le chez le fournisseur.";
            case CLE_REFUSEE -> "La clé " + f.nom() + " est refusée : vérifiez qu'elle est complète, active et autorisée.";
            case TEMPORAIRE -> f.nom() + " est momentanément injoignable ou surchargé : réessayez dans un instant.";
            case AUTRE -> e.getMessage();
        };
    }

    private FournisseurIa fournisseur(String id) {
        return switch (id == null ? "" : id.toLowerCase()) {
            case "openai" -> openai;
            case "anthropic" -> anthropic;
            default -> throw new IllegalArgumentException("Fournisseur inconnu : « " + id + " » (openai ou anthropic).");
        };
    }

    static String fin(String cle) {
        if (!StringUtils.hasText(cle)) return null;
        String c = cle.trim();
        return c.length() <= 4 ? c : c.substring(c.length() - 4);
    }
}
