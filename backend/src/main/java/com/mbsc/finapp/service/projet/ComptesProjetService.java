package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Sous-comptes des projets (V167) : sous un compte parent (6588 pour les achats divers, 6058 ou 21-24 pour les
 * acquisitions), un compte de regroupement par projet (parent + 2 chiffres, non imputable, « Projet Puits »), puis un
 * compte imputable par besoin (+ 2 chiffres, « Projet Puits — Ciment »). Le libellé commence toujours par « Projet » :
 * c'est par lui que la fiche de stock projet reconnaît les achats (ProjetStockService).
 */
@Service
@RequiredArgsConstructor
public class ComptesProjetService {

    public static final String PREFIXE = "Projet";
    /** Achats divers des projets. */
    public static final String PARENT_ACHAT = "6588";
    /** Acquisitions des projets, par défaut (le comptable peut choisir un compte 21 à 24). */
    public static final String PARENT_ACQUISITION = "6058";

    private final CompteOHADARepository comptes;

    /** Le libellé désigne-t-il un compte de projet (« Projet … ») ? */
    public static boolean estCompteProjet(String libelle) {
        return libelle != null && libelle.trim().toLowerCase(Locale.ROOT).startsWith(PREFIXE.toLowerCase(Locale.ROOT));
    }

    /** Parent admis pour une acquisition : 6058, ou un compte d'immobilisation 21 à 24. */
    public static boolean parentAcquisitionAdmis(String numero) {
        return numero != null && (numero.equals(PARENT_ACQUISITION) || numero.matches("2[1-4]\\d*"));
    }

    /** Compte de regroupement du projet sous ce parent : retrouvé (même libellé) ou créé (parent + NN, non imputable). */
    public CompteOHADA compteDuProjet(String parentNumero, String nomProjet) {
        CompteOHADA parent = comptes.findByNumero(parentNumero)
            .orElseThrow(() -> new IllegalArgumentException("Compte " + parentNumero + " absent du plan comptable."));
        String libelle = tronquer(PREFIXE + " " + nomProjet.trim());
        for (int i = 1; i <= 99; i++) {
            String numero = parentNumero + "%02d".formatted(i);
            var existant = comptes.findByNumero(numero);
            if (existant.isPresent()) {
                if (existant.get().getLibelle().equalsIgnoreCase(libelle)) {
                    return existant.get();
                }
                continue;
            }
            return comptes.saveAndFlush(nouveau(numero, libelle, parent, false));
        }
        throw new IllegalStateException("Plus de sous-compte libre sous " + parentNumero + " (99 au plus).");
    }

    /** Sous-compte imputable d'un besoin : compte du projet + NN, « Projet <nom> — <désignation> ». */
    public CompteOHADA compteDuBesoin(CompteOHADA compteProjet, String nomProjet, String designation) {
        String libelle = tronquer(PREFIXE + " " + nomProjet.trim() + " — " + designation.trim());
        for (int i = 1; i <= 99; i++) {
            String numero = compteProjet.getNumero() + "%02d".formatted(i);
            if (!comptes.existsByNumero(numero)) {
                return comptes.saveAndFlush(nouveau(numero, libelle, compteProjet, true));
            }
        }
        throw new IllegalStateException("Plus de sous-compte libre sous " + compteProjet.getNumero() + " (99 au plus).");
    }

    private static CompteOHADA nouveau(String numero, String libelle, CompteOHADA parent, boolean imputable) {
        return CompteOHADA.builder()
            .numero(numero)
            .libelle(libelle)
            .type(parent.getType())
            .classe(parent.getClasse())
            .parent(parent)
            .manuel(true)
            .imputable(imputable)
            .actif(true)
            .build();
    }

    private static String tronquer(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
