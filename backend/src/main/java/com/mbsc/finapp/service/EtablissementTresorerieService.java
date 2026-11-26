package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EtablissementTresorerie;
import com.mbsc.finapp.domain.enums.TypeCompte;
import com.mbsc.finapp.domain.enums.TypeEtablissement;
import com.mbsc.finapp.dto.etablissement.EtablissementRequest;
import com.mbsc.finapp.dto.etablissement.EtablissementResponse;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.EtablissementTresorerieRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestion des banques et operateurs mobile money.
 *
 * <p>Chaque etablissement est adosse a un sous-compte OHADA dedie
 * (banques sous 521, mobile money sous 582) : son solde est donc lu
 * directement dans le grand livre. Un etablissement ne peut etre retire
 * que si ce solde est nul, pour ne jamais faire disparaitre de la
 * tresorerie encore engagee.</p>
 */
@Service
@RequiredArgsConstructor
public class EtablissementTresorerieService {

    private static final Logger log = LoggerFactory.getLogger(EtablissementTresorerieService.class);

    /**
     * Prefixe de compte OHADA et sequence d'allocation, par type
     * d'etablissement. Le referentiel SYSCOHADA place la monnaie
     * electronique en 552 (« telephone portable ») et non en 58, qui
     * designe les regies d'avances et accreditifs.
     */
    private static final String PREFIXE_BANQUE = "52";
    private static final String PREFIXE_MOBILE_MONEY = "55";
    private static final String SEQ_BANQUE = "seq_compte_banque";
    private static final String SEQ_MOBILE_MONEY = "seq_compte_mobile_money";
    /** Debut de l'intitule des comptes ouverts avec un etablissement : « Banque — Equity Bank ». */
    private static final String LIBELLE_BANQUE = "Banque — ";
    private static final String LIBELLE_MOBILE_MONEY = "Mobile Money — ";
    private static final Pattern DEBUT_LIBELLE = Pattern.compile("^(banque|mobile money)\\s*[—–-]\\s*",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int TAILLE_NOM = 100;

    private final EtablissementTresorerieRepository etablissementRepository;
    private final com.mbsc.finapp.repository.ReleveBancaireRepository releveRepository;
    private final CompteOHADARepository compteRepository;
    /** Fiche de documentation du compte ouvert avec l'établissement. */
    private final DocumentationCompteService documentation;
    private final EcritureGrandLivreRepository ecritureRepository;

    @PersistenceContext
    private EntityManager em;

    // ---------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------

    /** Etablissements d'un type donne (ou tous si {@code type} est null), avec leur solde. */
    @PreAuthorize("hasAnyRole('CAISSIER', 'DFIN', 'DA', 'DG', 'ADMIN')")
    @Transactional(readOnly = true)
    public List<EtablissementResponse> lister(TypeEtablissement type) {
        List<EtablissementTresorerie> etablissements = type == null
            ? etablissementRepository.findAllAvecCompte()
            : etablissementRepository.findByTypeAvecCompte(type);
        return etablissements.stream()
            .map(e -> EtablissementResponse.from(e, solde(e)))
            .toList();
    }

    /** Resout un etablissement du type attendu, ou leve une erreur metier explicite. */
    @Transactional(readOnly = true)
    public EtablissementTresorerie requireEtablissement(Long id, TypeEtablissement typeAttendu) {
        EtablissementTresorerie etablissement = etablissementRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", id));
        if (etablissement.getType() != typeAttendu) {
            throw new IllegalArgumentException(
                "L'etablissement \"" + etablissement.getNom() + "\" n'est pas de type " + typeAttendu);
        }
        if (!etablissement.isActif()) {
            throw new TransitionInvalideException(
                "L'etablissement \"" + etablissement.getNom() + "\" est desactive.");
        }
        return etablissement;
    }

    /** Solde du compte dedie (debits - credits), convention des comptes d'actif. */
    private BigDecimal solde(EtablissementTresorerie e) {
        BigDecimal s = ecritureRepository.soldePourCompte(e.getCompte().getNumero());
        return s == null ? BigDecimal.ZERO : s;
    }

    // ---------------------------------------------------------------------
    // Administration
    // ---------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public EtablissementResponse creer(EtablissementRequest req) {
        String nom = req.nom().strip();
        if (etablissementRepository.existsByNomIgnoreCaseAndType(nom, req.type())) {
            throw new TransitionInvalideException(
                "Un etablissement \"" + nom + "\" existe deja pour ce type.");
        }

        // Un compte deja ouvert a ce nom (apres un import de journal, ou un etablissement retire puis recree) est
        // repris : il garde son solde et son historique. En ouvrir un neuf laisserait ces ecritures sur un compte
        // orphelin, et le nouvel etablissement afficherait un solde nul, faux.
        CompteOHADA compte = compteOrphelin(nom, req.type()).orElse(null);
        boolean repris = compte != null;
        if (!repris) {
            compte = creerCompteDedie(nom, req.type());
        } else if (!compte.isActif()) {
            compte.setActif(true);
            compteRepository.save(compte);
        }
        EtablissementTresorerie etablissement = etablissementRepository.save(
            EtablissementTresorerie.builder()
                .nom(nom)
                .type(req.type())
                .compte(compte)
                .actif(true)
                .devise(req.devise() == null ? com.mbsc.finapp.domain.enums.Devise.USD : req.devise())
                .build());

        log.info("Etablissement de tresorerie cree [nom={}, type={}, compte={}{}]",
            nom, req.type(), compte.getNumero(), repris ? ", compte existant repris" : "");
        return EtablissementResponse.from(etablissement, repris ? solde(etablissement) : BigDecimal.ZERO);
    }

    /**
     * Cree un etablissement pour chaque compte de banque ou de mobile money qui porte des ecritures sans en avoir —
     * cas d'un journal importe. Aucun montant n'est saisi : le solde d'un etablissement se lit dans le grand livre,
     * il est donc d'emblee celui des ecritures deja passees sur son compte.
     *
     * @param numeros    comptes a examiner (ceux d'un fichier importe) ; les autres comptes sont ignores
     * @param simulation true : n'ecrit rien, decrit seulement ce qui serait cree
     * @return un libelle par etablissement cree (ou a creer), ex. « Banque « Equity Bank » — compte 5215 »
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public List<String> rattacherComptesOrphelins(Collection<String> numeros, boolean simulation) {
        List<String> rattaches = new ArrayList<>();
        Set<String> nomsRetenus = new HashSet<>();
        for (String numero : new LinkedHashSet<>(numeros)) {
            CompteOHADA compte = compteRepository.findByNumero(numero).orElse(null);
            TypeEtablissement type = typePourCompte(compte);
            if (type == null || !compte.isActif() || !compte.isImputable()
                || etablissementRepository.existsByCompteId(compte.getId())) {
                continue;
            }
            String nom = nomDepuisLibelle(compte);
            if (!nomsRetenus.add(type + "|" + nom.toLowerCase(Locale.ROOT))
                || etablissementRepository.existsByNomIgnoreCaseAndType(nom, type)) {
                // Nom deja pris (par un autre compte) : le numero de compte les distingue.
                String suffixe = " (" + numero + ")";
                nom = nom.substring(0, Math.min(nom.length(), TAILLE_NOM - suffixe.length())).strip() + suffixe;
                nomsRetenus.add(type + "|" + nom.toLowerCase(Locale.ROOT));
            }
            if (!simulation) {
                etablissementRepository.save(EtablissementTresorerie.builder()
                    .nom(nom)
                    .type(type)
                    .compte(compte)
                    .actif(true)
                    .devise(com.mbsc.finapp.domain.enums.Devise.USD)
                    .build());
                log.info("Etablissement de tresorerie cree depuis le grand livre [nom={}, type={}, compte={}]",
                    nom, type, numero);
            }
            rattaches.add((type == TypeEtablissement.BANQUE ? "Banque « " : "Mobile money « ") + nom
                + " » — compte " + numero);
        }
        return rattaches;
    }

    /**
     * Type d'etablissement que represente un compte, ou null. Banque : sous-comptes de 521 (banques locales du
     * referentiel) et comptes « Banque — … » ouverts par l'application sous 52. Mobile money : sous-comptes de 552
     * (telephone portable) et comptes « Mobile Money — … » sous 55. La caisse (57), les virements internes (585) et
     * les autres comptes de 52 (interets courus, depots a terme...) ne sont jamais des etablissements.
     */
    static TypeEtablissement typePourCompte(CompteOHADA compte) {
        if (compte == null || compte.getNumero() == null || !Integer.valueOf(5).equals(compte.getClasse())) {
            return null;
        }
        String numero = compte.getNumero();
        String libelle = compte.getLibelle() == null ? "" : compte.getLibelle();
        if (numero.startsWith(EcritureComptableService.PREFIXE_COMPTES_BANQUE)
            || (numero.startsWith(PREFIXE_BANQUE) && libelle.startsWith(LIBELLE_BANQUE))) {
            return TypeEtablissement.BANQUE;
        }
        if (numero.startsWith(EcritureComptableService.PREFIXE_COMPTES_MOBILE_MONEY)
            || (numero.startsWith(PREFIXE_MOBILE_MONEY) && libelle.startsWith(LIBELLE_MOBILE_MONEY))) {
            return TypeEtablissement.MOBILE_MONEY;
        }
        return null;
    }

    /** Nom de l'etablissement tire de l'intitule du compte : « Banque — Equity Bank » donne « Equity Bank ». */
    static String nomDepuisLibelle(CompteOHADA compte) {
        String libelle = compte.getLibelle() == null ? "" : compte.getLibelle().strip();
        String nom = DEBUT_LIBELLE.matcher(libelle).replaceFirst("").strip();
        if (nom.isEmpty()) {
            nom = "Compte " + compte.getNumero();
        }
        return nom.length() > TAILLE_NOM ? nom.substring(0, TAILLE_NOM).strip() : nom;
    }

    /** Compte « Banque — <nom> » (ou « Mobile Money — <nom> ») deja ouvert et rattache a aucun etablissement. */
    private Optional<CompteOHADA> compteOrphelin(String nom, TypeEtablissement type) {
        String libelle = (type == TypeEtablissement.BANQUE ? LIBELLE_BANQUE : LIBELLE_MOBILE_MONEY) + nom;
        return compteRepository.findByLibelleIgnoreCaseOrderByNumeroAsc(libelle).stream()
            .filter(c -> typePourCompte(c) == type && c.isImputable())
            .filter(c -> !etablissementRepository.existsByCompteId(c.getId()))
            .findFirst();
    }

    /**
     * Change la devise de tenue d'un compte (celle de ses relevés). Refusé dès qu'un relevé a été rapproché :
     * les montants déjà pointés sont exprimés dans l'ancienne devise.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public EtablissementResponse changerDevise(Long id, com.mbsc.finapp.domain.enums.Devise devise) {
        EtablissementTresorerie e = etablissementRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", id));
        if (devise == null) {
            throw new IllegalArgumentException("Indiquez la devise du compte.");
        }
        if (releveRepository.existsByEtablissementId(id)) {
            throw new TransitionInvalideException("Des relevés de " + e.getNom() + " sont déjà rapprochés : "
                + "la devise du compte ne peut plus changer.");
        }
        e.setDevise(devise);
        log.info("Devise de l'etablissement {} : {}", e.getNom(), devise);
        return EtablissementResponse.from(e, null);
    }

    /**
     * Retire un etablissement. Refuse tant que son compte presente un solde :
     * la tresorerie doit d'abord etre transferee ou soldee.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void supprimer(Long id) {
        EtablissementTresorerie etablissement = etablissementRepository.findById(id)
            .orElseThrow(() -> RessourceIntrouvableException.of("EtablissementTresorerie", id));

        BigDecimal soldeActuel = solde(etablissement);
        if (soldeActuel.signum() != 0) {
            throw new TransitionInvalideException(
                "Impossible de retirer \"" + etablissement.getNom() + "\" : son solde est de "
                + soldeActuel.toPlainString() + " " + ConversionDeviseService.DEVISE_BASE
                + ". Seul un etablissement au solde nul peut etre retire ; "
                + "transferez ou soldez d'abord la tresorerie.");
        }

        // Le compte OHADA est conserve s'il a deja porte des ecritures
        // (historique comptable), simplement desactive.
        CompteOHADA compte = etablissement.getCompte();
        etablissementRepository.delete(etablissement);
        if (ecritureRepository.existsByCompteId(compte.getId())) {
            compte.setActif(false);
            compteRepository.save(compte);
        } else {
            compteRepository.delete(compte);
        }

        log.info("Etablissement de tresorerie retire [nom={}, type={}]",
            etablissement.getNom(), etablissement.getType());
    }

    /**
     * Alloue le prochain numero de compte libre du plan comptable pour le
     * type demande et cree le compte correspondant.
     */
    private CompteOHADA creerCompteDedie(String nom, TypeEtablissement type) {
        boolean estBanque = type == TypeEtablissement.BANQUE;
        String prefixe = estBanque ? PREFIXE_BANQUE : PREFIXE_MOBILE_MONEY;
        String sequence = estBanque ? SEQ_BANQUE : SEQ_MOBILE_MONEY;

        // La sequence peut retomber sur un numero deja utilise par le plan
        // comptable OHADA standard (5221 par exemple) : on avance jusqu'au
        // premier numero reellement libre.
        String numero;
        do {
            Number suffixe = (Number) em.createNativeQuery("SELECT nextval('" + sequence + "')")
                .getSingleResult();
            numero = prefixe + suffixe.longValue();
        } while (compteRepository.existsByNumero(numero));

        String libelle = (estBanque ? LIBELLE_BANQUE : LIBELLE_MOBILE_MONEY) + nom;
        CompteOHADA compte = CompteOHADA.builder()
            .numero(numero)
            .libelle(libelle.length() > 200 ? libelle.substring(0, 200) : libelle)
            .type(TypeCompte.ACTIF)
            .classe(5)
            .manuel(true)
            .imputable(true)
            .actif(true)
            .build();
        documentation.documenterEtablissement(compte, nom, estBanque);
        return compteRepository.save(compte);
    }
}
