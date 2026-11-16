package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.Fournisseur;
import com.mbsc.finapp.dto.fournisseur.FournisseurRequest;
import com.mbsc.finapp.dto.fournisseur.FournisseurResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.FournisseurRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Répertoire des fournisseurs (V141).
 *
 * <p>Lecture : les rôles qui achètent (logistique, responsables du restaurant, de la cuisine et du bar, gestionnaire
 * du patrimoine), la comptabilité et la direction. Écriture : les mêmes, sans le DA ni le DG. Le caissier n'y a pas
 * accès : il paie, il ne choisit pas les fournisseurs.</p>
 *
 * <p>Les achats gardent le nom du fournisseur en texte libre (lots de stock, immobilisations) : une fiche à laquelle
 * des achats renvoient s'archive ; seule une fiche sans achat se supprime.</p>
 *
 * <p>Chaque fournisseur reçoit son propre compte, ouvert automatiquement sous le collectif 4011 au premier numéro
 * libre (40111, 40112… : le numéro du parent suivi du rang, sans point ni zéro, comme les comptes des articles du
 * restaurant). Il n'est pas saisi : l'intitulé suit le nom du fournisseur.</p>
 */
@Service
@RequiredArgsConstructor
public class FournisseurService {

    private static final Logger log = LoggerFactory.getLogger(FournisseurService.class);

    private final FournisseurRepository repository;
    private final CompteOHADARepository compteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final DocumentationCompteService documentation;

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<FournisseurResponse> lister() {
        return repository.findAllByOrderByNomAsc().stream().map(this::versReponse).toList();
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public FournisseurResponse creer(FournisseurRequest req) {
        Fournisseur f = new Fournisseur();
        appliquer(f, req, null);
        CompteOHADA compte = ouvrirCompte(f.getNom());
        f.setCompteComptable(compte.getNumero());
        f = repository.save(f);
        f.setCode(String.format("FRN-%04d", f.getId()));
        documentation.documenterFournisseur(compte, f.getNom(), f.getCode());
        log.info("Fournisseur cree [code={}, nom={}]", f.getCode(), f.getNom());
        return versReponse(f);
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public FournisseurResponse modifier(Long id, FournisseurRequest req) {
        Fournisseur f = trouver(id);
        String ancienNom = f.getNom();
        if (!ancienNom.strip().equalsIgnoreCase(req.nom().strip()) && nombreAchats(ancienNom) > 0) {
            // Les achats renvoient au fournisseur par son nom : le changer couperait le lien.
            throw new TransitionInvalideException("Des achats sont enregistrés au nom de \"" + ancienNom
                + "\" : son nom ne peut plus changer. Archivez cette fiche et créez-en une nouvelle.");
        }
        appliquer(f, req, id);
        if (!ancienNom.equals(f.getNom())) {
            compteRepository.findByNumero(f.getCompteComptable())
                .filter(c -> c.getParent() != null && Fournisseur.COMPTE_PAR_DEFAUT.equals(c.getParent().getNumero()))
                .ifPresent(c -> c.setLibelle(libelleCompte(f.getNom())));
        }
        return versReponse(f);
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public FournisseurResponse archiver(Long id) {
        Fournisseur f = trouver(id);
        f.setActif(false);
        return versReponse(f);
    }

    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public FournisseurResponse reactiver(Long id) {
        Fournisseur f = trouver(id);
        f.setActif(true);
        return versReponse(f);
    }

    /** Supprime une fiche sans achat ; une fiche à laquelle des achats renvoient s'archive. */
    @PreAuthorize("hasAnyRole('LOGISTIQUE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'GEST_PATRIMOINE', "
        + "'COMPTABLE', 'DFIN', 'ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        Fournisseur f = trouver(id);
        long achats = nombreAchats(f.getNom());
        if (achats > 0) {
            throw new TransitionInvalideException("\"" + f.getNom() + "\" a " + achats
                + (achats > 1 ? " achats enregistrés" : " achat enregistré") + " : archivez la fiche au lieu de la supprimer.");
        }
        CompteOHADA compte = compteRepository.findByNumero(f.getCompteComptable())
            .filter(c -> c.getParent() != null && Fournisseur.COMPTE_PAR_DEFAUT.equals(c.getParent().getNumero()))
            .orElse(null);
        if (compte != null && ecritureRepository.existsByCompteId(compte.getId())) {
            throw new TransitionInvalideException("Le compte " + compte.getNumero() + " de \"" + f.getNom()
                + "\" porte des écritures : archivez la fiche au lieu de la supprimer.");
        }
        repository.delete(f);
        if (compte != null) {
            compteRepository.delete(compte);
        }
        log.info("Fournisseur supprime [code={}, nom={}]", f.getCode(), f.getNom());
    }

    private void appliquer(Fournisseur f, FournisseurRequest req, Long id) {
        String nom = req.nom().strip();
        if (repository.nomDejaPris(nom, id)) {
            throw new TransitionInvalideException("Un fournisseur porte déjà le nom \"" + nom + "\".");
        }
        f.setNom(nom);
        f.setContact(vide(req.contact()));
        f.setTelephone(vide(req.telephone()));
        f.setEmail(vide(req.email()));
        f.setAdresse(vide(req.adresse()));
        f.setNif(majuscules(req.nif()));
        f.setRccm(majuscules(req.rccm()));
    }

    /**
     * Ouvre le compte propre du fournisseur au premier numéro libre sous 4011 (40111, 40112…), avec le nom du
     * fournisseur pour intitulé.
     */
    private CompteOHADA ouvrirCompte(String nom) {
        CompteOHADA collectif = compteRepository.findByNumero(Fournisseur.COMPTE_PAR_DEFAUT)
            .orElseThrow(() -> new TransitionInvalideException("Le compte " + Fournisseur.COMPTE_PAR_DEFAUT
                + " (Fournisseurs) manque au plan comptable : impossible d'ouvrir le compte du fournisseur."));
        for (int rang = 1; rang <= 99_999; rang++) {
            String numero = Fournisseur.COMPTE_PAR_DEFAUT + rang;
            if (compteRepository.existsByNumero(numero)) {
                continue;
            }
            return compteRepository.save(CompteOHADA.builder()
                .numero(numero)
                .libelle(libelleCompte(nom))
                .type(collectif.getType())
                .classe(collectif.getClasse())
                .parent(collectif)
                .manuel(true)
                .imputable(true)
                .actif(true)
                .build());
        }
        throw new IllegalStateException("Plus de numéro libre sous " + Fournisseur.COMPTE_PAR_DEFAUT);
    }

    private static String libelleCompte(String nom) {
        String libelle = "Fournisseurs : " + nom;
        return libelle.length() > 200 ? libelle.substring(0, 200) : libelle;
    }

    private Fournisseur trouver(Long id) {
        return repository.findById(id).orElseThrow(() -> RessourceIntrouvableException.of("Fournisseur", id));
    }

    private long nombreAchats(String nom) {
        return repository.compterLots(nom) + repository.compterImmobilisations(nom);
    }

    private FournisseurResponse versReponse(Fournisseur f) {
        return FournisseurResponse.from(f, nombreAchats(f.getNom()));
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String majuscules(String s) {
        String v = vide(s);
        return v == null ? null : v.toUpperCase();
    }
}
