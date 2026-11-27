package com.mbsc.finapp.service.projet;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.repository.CompteOHADARepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Sous-comptes des projets (V167, V168) : sous un compte parent (6588 pour les achats divers, 6058 ou 21-24 pour les
 * acquisitions), un compte de regroupement « Projets » (parent + 2 chiffres, non imputable, partagé), puis un compte
 * imputable par besoin (+ 2 chiffres, « Projet — Ciment »), sans le nom du projet. Le libellé commence toujours par
 * « Projet » : c'est par lui que la fiche de stock projet reconnaît les achats (ProjetStockService).
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

    /** Libellé des comptes de regroupement, partagés par tous les projets. */
    public static final String REGROUPEMENT = "Projets";

    /**
     * Sous-compte imputable d'un besoin, « Projet — <désignation> » : sans le nom du projet, car un bien acheté pour un
     * projet peut être transféré à un autre (le rattachement est porté par la fiche de stock projet, pas par le compte).
     * Il est rangé sous un compte de regroupement « Projets » du parent (parent + NN, non imputable), partagé par tous les
     * projets ; un nouveau regroupement est ouvert quand le précédent a ses 99 sous-comptes.
     */
    public CompteOHADA compteDuBesoin(String parentNumero, String designation) {
        CompteOHADA parent = comptes.findByNumero(parentNumero)
            .orElseThrow(() -> new IllegalArgumentException("Compte " + parentNumero + " absent du plan comptable."));
        String libelle = tronquer(PREFIXE + " — " + designation.trim());
        for (int i = 1; i <= 99; i++) {
            String numeroGroupe = parentNumero + "%02d".formatted(i);
            var existant = comptes.findByNumero(numeroGroupe);
            CompteOHADA groupe;
            if (existant.isPresent()) {
                // Regroupement de projets (« Projets », ou un ancien « Projet <nom> ») ; tout autre compte est ignoré.
                if (existant.get().isImputable() || !estCompteProjet(existant.get().getLibelle())) continue;
                groupe = existant.get();
            } else {
                groupe = comptes.saveAndFlush(nouveau(numeroGroupe, REGROUPEMENT, parent, false));
            }
            for (int j = 1; j <= 99; j++) {
                String numero = groupe.getNumero() + "%02d".formatted(j);
                if (!comptes.existsByNumero(numero)) {
                    return comptes.saveAndFlush(nouveau(numero, libelle, groupe, true));
                }
            }
        }
        throw new IllegalStateException("Plus de sous-compte de projet libre sous " + parentNumero + ".");
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
