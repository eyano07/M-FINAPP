package com.mbsc.finapp.service;

import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.EcritureGrandLivre;
import com.mbsc.finapp.domain.PieceComptable;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.Devise;
import com.mbsc.finapp.domain.enums.JournalComptable;
import com.mbsc.finapp.dto.admin.ImportJournalResponse;
import com.mbsc.finapp.dto.admin.SuggestionImport;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.PieceComptableRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Import d'un journal comptable pour peupler l'application a partir de donnees
 * existantes (reprise d'historique, migration depuis un tableur).
 *
 * <p><b>Pourquoi le journal seul suffit.</b> Le bilan, la balance, le compte de
 * resultat, le livre-journal et les flux de tresorerie ne sont pas stockes :
 * ils sont tous recalcules a la demande a partir du grand livre. Importer les
 * ecritures du journal alimente donc mecaniquement l'ensemble des etats, sans
 * qu'il y ait quoi que ce soit d'autre a saisir — et sans risque d'incoherence
 * entre un bilan importe et les ecritures qui le sous-tendent.</p>
 *
 * <p><b>Deux temps.</b> L'import se fait d'abord en simulation : le fichier est
 * analyse entierement et le compte rendu liste les anomalies, sans rien ecrire.
 * L'administrateur corrige son fichier, puis relance en mode reel. Une reprise
 * d'historique est une operation lourde a defaire : mieux vaut la verifier
 * avant qu'apres.</p>
 *
 * <p><b>Controles.</b> Chaque piece est creee via
 * {@link ComptabiliteService#creerPieceInterne} et herite donc de toutes les
 * regles deja en place : equilibre debit/credit, compte imputable et actif,
 * periode non close. Le service y ajoute les controles propres au fichier :
 * format des colonnes, dates lisibles, montants positifs, et interdiction
 * qu'une ligne porte a la fois un debit et un credit.</p>
 */
@Service
@RequiredArgsConstructor
public class ImportJournalService {

    private static final Logger log = LoggerFactory.getLogger(ImportJournalService.class);

    /** Au-dela, le fichier est probablement une erreur de manipulation. */
    private static final int MAX_LIGNES = 20_000;
    private static final long MAX_TAILLE = 10L * 1024 * 1024;

    /**
     * Colonnes attendues, dans cet ordre. Reprend exactement l'en-tete de la
     * feuille « Journal » produite par l'export des etats financiers : un
     * classeur exporte peut donc etre reimporte tel quel.
     */
    private static final String[] COLONNES = {
        "Date", "N° Pièce", "Journal", "Compte", "Intitulé du compte",
        "Libellé écriture", "Débit", "Crédit", "Débit USD", "Crédit USD"
    };

    /** Format de sortie du fichier corrige : celui attendu en relecture. */
    private static final DateTimeFormatter DATE_FR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final List<DateTimeFormatter> FORMATS_DATE = List.of(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"));

    private final CompteOHADARepository compteRepository;
    private final EcritureGrandLivreRepository ecritureRepository;
    private final PieceComptableRepository pieceRepository;
    private final ComptabiliteService comptabilite;
    private final CurrentUserProvider currentUser;
    /** Propose des corrections au lieu de se contenter de refuser le fichier. */
    private final ImportIaService assistance;
    /** Convertit un journal en devise etrangere vers la devise de base. */
    private final ConversionDeviseService conversionDevise;

    /**
     * Cle de regroupement des lignes en pieces comptables.
     *
     * <p>Un journal exporte d'un autre outil ne numerote pas toujours ses
     * pieces : beaucoup incrementent la reference a chaque ligne. Regrouper
     * par reference produit alors des pieces d'une seule ligne, forcement
     * desequilibrees. Le mode est donc laisse au choix, avec le diagnostic
     * d'equilibre correspondant.</p>
     */
    public enum ModeRegroupement {
        /** Une piece par valeur de la colonne « N° Pièce ». */
        REFERENCE,
        /** Une piece par jour : convient aux journaux numerotes ligne a ligne. */
        JOUR,
        /** Une seule piece pour tout le fichier : reprise d'a-nouveaux. */
        FICHIER
    }

    /**
     * Que faire des ecritures deja presentes.
     *
     * <p>Une reprise d'historique se rejoue souvent : on corrige le fichier,
     * on relance. Sans remplacement, chaque tentative empile un jeu complet
     * d'ecritures et double les soldes sans que rien ne le signale.</p>
     */
    public enum ModeImport {
        /** Efface les ecritures comptables existantes avant d'importer. */
        REMPLACER,
        /** Conserve l'existant et ajoute les ecritures du fichier. */
        AJOUTER
    }

    /**
     * Une ligne du fichier, deja typee.
     *
     * @param intituleCompte nom du compte tel qu'il figure dans le fichier
     *        (colonne « Intitule du compte »). Sert uniquement de repli quand
     *        le compte est absent du plan comptable : tant qu'il y existe,
     *        c'est l'intitule du referentiel qui fait foi — voir
     *        {@link #libelleCompte}.
     */
    private record LigneImport(int numeroLigne, LocalDate date, String referencePiece,
                                JournalComptable journal, String compte, String intituleCompte,
                                String libelle,
                                BigDecimal debit, BigDecimal credit,
                                /** Montant en devise etrangere, si le fichier porte des colonnes dediees. */
                                BigDecimal montantDevise) {}

    // -----------------------------------------------------------------
    // Point d'entree
    // -----------------------------------------------------------------

    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    @Transactional
    public ImportJournalResponse importer(MultipartFile fichier, boolean simulation,
                                          Map<String, String> substitutions,
                                          ModeRegroupement mode, Devise devise,
                                          ModeImport modeImport) {
        if (fichier == null || fichier.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aucun fichier fourni.");
        }
        if (fichier.getSize() > MAX_TAILLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Fichier trop volumineux (maximum 10 Mo).");
        }
        String nom = fichier.getOriginalFilename() == null ? "" : fichier.getOriginalFilename().toLowerCase();

        List<String> erreurs = new ArrayList<>();
        List<String> avertissements = new ArrayList<>();
        List<LigneImport> lignes;
        try (InputStream in = fichier.getInputStream()) {
            lignes = nom.endsWith(".csv")
                ? lireCsv(in, erreurs)
                : lireExcel(in, erreurs);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Fichier illisible : " + e.getMessage());
        }

        // Aucune correction orthographique n'est appliquee : les libelles
        // d'ecriture et les intitules de compte sont repris exactement tels
        // que le fichier les porte. Une reecriture automatique, meme limitee
        // a l'orthographe, modifiait un texte comptable sans trace ni
        // validation — et renommait au passage des comptes du plan.

        // Substitutions validees par l'administrateur (ex. 101 -> 1011),
        // appliquees avant tout controle pour que le fichier soit juge corrige.
        if (substitutions != null && !substitutions.isEmpty()) {
            lignes = lignes.stream().map(l -> {
                String remplacement = substitutions.get(l.compte());
                return remplacement == null ? l : new LigneImport(l.numeroLigne(), l.date(),
                    l.referencePiece(), l.journal(), remplacement, l.intituleCompte(), l.libelle(),
                    l.debit(), l.credit(), l.montantDevise());
            }).toList();
        }

        // Devise du fichier. Un journal deja tenu en dollars est enregistre
        // TEL QUEL : le convertir en francs puis le reafficher en dollars
        // ferait subir aux montants un aller-retour par le taux, et le moindre
        // ecart de taux entre l'import et l'affichage deformerait les valeurs.
        // Seule la devise est memorisee sur chaque ecriture, pour que les vues
        // sachent qu'aucune conversion n'est a appliquer.
        Devise deviseFichier = devise == null ? ConversionDeviseService.DEVISE_BASE : devise;
        BigDecimal tauxImport = deviseFichier == ConversionDeviseService.DEVISE_BASE
            ? null
            : conversionDevise.tauxALaDate(lignes.isEmpty() ? LocalDate.now() : lignes.get(0).date());

        if (lignes.isEmpty() && erreurs.isEmpty()) {
            erreurs.add("Aucune ligne de donnees trouvee. Verifiez que la premiere ligne contient "
                + "les en-tetes et que les ecritures suivent.");
        }

        // Regroupement par piece, dans l'ordre d'apparition du fichier.
        ModeRegroupement regroupement = mode == null ? ModeRegroupement.REFERENCE : mode;
        Map<String, List<LigneImport>> parPiece = new LinkedHashMap<>();
        for (LigneImport l : lignes) {
            parPiece.computeIfAbsent(cleRegroupement(l, regroupement), k -> new ArrayList<>()).add(l);
        }

        BigDecimal totalDebit = lignes.stream().map(LigneImport::debit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lignes.stream().map(LigneImport::credit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Controle des comptes des la simulation. Sans cela, un compte absent
        // ou non imputable ne se revelait qu'au moment de l'import reel, ce qui
        // vide la simulation de son interet : l'administrateur croyait son
        // fichier valide et decouvrait l'anomalie en tentant de l'appliquer.
        Set<String> comptesVus = new LinkedHashSet<>();
        Map<String, String> comptesFautifs = new LinkedHashMap<>();
        // Intitules portes par le fichier, pour les seuls comptes a creer :
        // un compte deja au plan comptable garde l'intitule du referentiel.
        Map<String, String> intitulesFichier = new LinkedHashMap<>();
        for (LigneImport l : lignes) {
            if (StringUtils.hasText(l.intituleCompte())) {
                intitulesFichier.putIfAbsent(l.compte(), l.intituleCompte().trim());
            }
            if (!comptesVus.add(l.compte())) {
                continue;
            }
            compteRepository.findByNumero(l.compte()).ifPresentOrElse(c -> {
                if (!c.isImputable()) {
                    erreurs.add("Compte " + c.getNumero() + " (" + c.getLibelle() + ") : compte de"
                        + " regroupement, non imputable. Utilisez un sous-compte de saisie.");
                    comptesFautifs.putIfAbsent(l.compte(), l.libelle());
                } else if (!c.isActif()) {
                    erreurs.add("Compte " + c.getNumero() + " (" + c.getLibelle() + ") : compte desactive.");
                }
            }, () -> {
                erreurs.add("Compte " + l.compte() + " absent du plan comptable"
                    + " (premiere occurrence ligne " + l.numeroLigne() + ")");
                comptesFautifs.putIfAbsent(l.compte(), l.libelle());
            });
        }

        // Controle d'equilibre par piece avant toute ecriture : une piece
        // desequilibree serait de toute facon refusee, autant le signaler ici
        // avec le detail plutot que d'interrompre l'import a mi-parcours.
        for (var e : parPiece.entrySet()) {
            BigDecimal d = e.getValue().stream().map(LigneImport::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal c = e.getValue().stream().map(LigneImport::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (d.compareTo(c) != 0) {
                erreurs.add("Piece " + e.getKey() + " desequilibree : debit " + d + " / credit " + c);
            }
            if (e.getValue().size() < 2) {
                erreurs.add("Piece " + e.getKey() + " : une ecriture doit comporter au moins deux lignes");
            }
        }

        // Diagnostic : si presque toutes les pieces sont isolees, la colonne
        // de reference numerote les lignes et non les pieces. On oriente vers
        // un autre regroupement plutot que de laisser des centaines d'erreurs
        // sans explication.
        long isolees = parPiece.values().stream().filter(v -> v.size() < 2).count();
        if (regroupement == ModeRegroupement.REFERENCE && parPiece.size() > 2
            && isolees > parPiece.size() * 0.8) {
            avertissements.add("La colonne « N° Pièce » semble numeroter chaque ligne plutot que"
                + " chaque piece (" + isolees + " pieces d'une seule ligne sur " + parPiece.size()
                + "). Essayez le regroupement par jour, ou en une seule piece pour une reprise"
                + " d'a-nouveaux.");
        }

        List<String> references = new ArrayList<>();
        int importees = 0;

        int ecrituresRemplacees = 0;
        if (erreurs.isEmpty() && !simulation && modeImport == ModeImport.REMPLACER) {
            ecrituresRemplacees = effacerEcrituresRemplacables();
        }

        if (erreurs.isEmpty() && !simulation) {
            User auteur = currentUser.requireUser();
            for (var e : parPiece.entrySet()) {
                try {
                    PieceComptable piece = creerPiece(e.getKey(), e.getValue(), auteur,
                        deviseFichier, tauxImport);
                    references.add(piece.getReference());
                    importees++;
                } catch (RuntimeException ex) {
                    // Remonte l'anomalie et annule tout le lot : un import
                    // partiel laisserait une comptabilite incoherente, plus
                    // difficile a rattraper qu'un import refuse en bloc.
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Import interrompu sur la piece " + e.getKey() + " : " + ex.getMessage()
                        + ". Aucune ecriture n'a ete conservee.");
                }
            }
            log.info("Import de journal termine : {} piece(s), {} ligne(s), debit={} credit={}",
                importees, lignes.size(), totalDebit, totalCredit);
        } else if (erreurs.isEmpty()) {
            references.addAll(parPiece.keySet());
        }

        if (!simulation && !erreurs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Le fichier comporte " + erreurs.size() + " anomalie(s) : corrigez-les puis relancez. "
                + "Premiere anomalie — " + erreurs.get(0));
        }

        // Assistance : uniquement en simulation et seulement si le fichier
        // coince, pour ne pas appeler le modele quand tout va bien.
        List<SuggestionImport> suggestions = (simulation && !comptesFautifs.isEmpty())
            ? assistance.suggererComptes(comptesFautifs, intitulesFichier)
            : List.of();

        return new ImportJournalResponse(simulation, lignes.size(), parPiece.size(), importees,
            totalDebit, totalCredit, erreurs, avertissements, references, suggestions);
    }

    /** Cle sous laquelle une ligne est rattachee a une piece. */
    private String cleRegroupement(LigneImport l, ModeRegroupement mode) {
        return switch (mode) {
            case REFERENCE -> l.referencePiece();
            case JOUR -> "JOUR-" + l.date();
            case FICHIER -> "IMPORT";
        };
    }

    /**
     * Efface les ecritures comptables remplacables avant un import.
     *
     * <p><b>Perimetre volontairement restreint.</b> Seules les pieces sans
     * transaction de tresorerie sont supprimees : imports precedents, pieces
     * saisies a la main, soldes d'ouverture. Les ecritures adossees a un
     * encaissement ou un decaissement reel (caisse, banque, mobile money) sont
     * conservees — les effacer laisserait des transactions sans contrepartie
     * comptable et fausserait durablement les soldes de tresorerie, alors que
     * ces mouvements ne proviennent pas du fichier importe.</p>
     *
     * @return nombre d'ecritures supprimees
     */
    private int effacerEcrituresRemplacables() {
        int supprimees = ecritureRepository.supprimerEcrituresSansTransaction();
        int pieces = pieceRepository.supprimerPiecesSansEcriture();
        log.warn("Import en mode REMPLACER : {} ecriture(s) et {} piece(s) effacees",
            supprimees, pieces);
        return supprimees;
    }

    private PieceComptable creerPiece(String reference, List<LigneImport> lignes, User auteur,
                                       Devise devise, BigDecimal taux) {
        LigneImport premiere = lignes.get(0);
        String libelle = StringUtils.hasText(premiere.libelle())
            ? premiere.libelle() : "Import journal " + reference;

        List<EcritureGrandLivre> ecritures = new ArrayList<>();
        for (LigneImport l : lignes) {
            CompteOHADA compte = compteRepository.findByNumero(l.compte())
                .orElseThrow(() -> new IllegalArgumentException(
                    "compte " + l.compte() + " absent du plan comptable (ligne " + l.numeroLigne() + ")"));
            // Le Grand Livre est tenu en devise de base : un fichier libelle
            // dans une autre devise est converti ici. Un fichier deja dans la
            // devise de base est enregistre tel quel — c'est le cas d'un
            // journal en dollars maintenant que la base est l'USD.
            boolean aConvertir = devise != null
                && devise != ConversionDeviseService.DEVISE_BASE
                && taux != null && taux.signum() > 0;

            BigDecimal debit = aConvertir
                ? conversionDevise.enDeviseBase(l.debit(), devise, taux).montantBase() : l.debit();
            BigDecimal credit = aConvertir
                ? conversionDevise.enDeviseBase(l.credit(), devise, taux).montantBase() : l.credit();

            // Libelle repris du PLAN COMPTABLE, pas du fichier : le compte est
            // le meme, son intitule fait donc autorite. Un journal exporte d'un
            // autre outil porte des libelles saisis a la main, souvent fautifs
            // (« CAARBURANT ») ou propres a une operation ; les afficher tels
            // quels faisait ressortir ces erreurs dans le grand livre et les
            // etats financiers. Le libelle du fichier ne sert plus que de repli
            // si le compte du plan n'a pas d'intitule.
            String libelleLigne = StringUtils.hasText(compte.getLibelle())
                ? compte.getLibelle()
                : (StringUtils.hasText(l.libelle()) ? l.libelle() : libelle);

            var ligne = EcritureGrandLivre.builder()
                .compte(compte)
                .debit(debit)
                .credit(credit)
                .libelle(libelleLigne)
                .dateEcriture(l.date());
            // Devise d'origine, montant d'origine et taux conserves : la
            // contre-valeur reste verifiable apres coup.
            if (aConvertir) {
                ligne.devise(devise.name())
                    .montantDevise(l.montantDevise() != null
                        ? l.montantDevise() : l.debit().add(l.credit()))
                    .tauxApplique(taux);
            }
            ecritures.add(ligne.build());
        }
        return comptabilite.creerPieceInterne(
            premiere.journal(), libelle, premiere.date(), ecritures, auteur);
    }

    /**
     * Position de chaque champ dans le fichier, resolue d'apres les en-tetes.
     *
     * <p>Les positions etaient auparavant figees (colonne 3 = compte, 6 =
     * debit...). Un journal exporte depuis un autre outil n'a pas forcement
     * les memes colonnes ni le meme ordre : un fichier sans colonne
     * « Journal » decale tout le reste d'un cran, et l'import lisait alors
     * l'intitule du compte comme un numero et le lettrage comme un credit —
     * d'ou un credit total a zero et des centaines de lignes rejetees, sans
     * que rien n'indique la vraie cause.</p>
     *
     * @param journal -1 si la colonne est absente (valeur par defaut appliquee)
     */
    private record Mapping(int date, int piece, int journal, int compte, int intituleCompte,
                            int libelle, int debit, int credit, int debitUsd, int creditUsd) {
        boolean complet() {
            return date >= 0 && compte >= 0 && debit >= 0 && credit >= 0;
        }
    }

    /** Rapproche les en-tetes du fichier des champs attendus, accents et casse ignores. */
    private Mapping resoudreMapping(List<String> enTetes) {
        return new Mapping(
            chercher(enTetes, List.of("date"), List.of()),
            chercher(enTetes, List.of("npiece", "nopiece", "numeropiece", "piece", "reference", "ref"), List.of()),
            chercher(enTetes, List.of("journal", "codejournal"), List.of()),
            // « Intitule du compte » contient « compte » : on l'exclut
            // explicitement, sinon il serait pris pour la colonne du numero.
            chercher(enTetes, List.of("compte", "comptegeneral", "numerocompte", "ncompte"),
                     List.of("intitule", "libelle", "nom")),
            // Intitule du compte porte par le fichier. Repli seulement : il ne
            // sert qu'aux comptes absents du plan comptable (creation), jamais
            // a renommer un compte du referentiel.
            chercher(enTetes, List.of("intitulecompte", "intituleducompte", "libelleducompte",
                                      "libellecompte", "nomducompte", "nomcompte", "intitule"),
                     List.of("ecriture")),
            chercher(enTetes, List.of("libelleecriture", "libelle", "designation", "objet"),
                     List.of("compte")),
            // Colonnes en devise de base : on exclut celles marquees USD/devise,
            // sinon « Debit USD » serait pris pour la colonne des francs.
            chercher(enTetes, List.of("debit"), List.of("usd", "dollar", "devise")),
            chercher(enTetes, List.of("credit"), List.of("usd", "dollar", "devise")),
            // Colonnes facultatives portant le montant en dollars.
            chercher(enTetes, List.of("debitusd", "debitdollar", "debitdevise", "debit$"), List.of()),
            chercher(enTetes, List.of("creditusd", "creditdollar", "creditdevise", "credit$"), List.of()));
    }

    /**
     * @param motsCles  candidats, du plus precis au plus general
     * @param exclusions en-tete ignore s'il contient l'un de ces fragments
     */
    private int chercher(List<String> enTetes, List<String> motsCles, List<String> exclusions) {
        // Egalite exacte d'abord : elle l'emporte sur une correspondance partielle.
        for (String cle : motsCles) {
            for (int i = 0; i < enTetes.size(); i++) {
                String n = normaliserEntete(enTetes.get(i));
                if (n.equals(cle) && exclusions.stream().noneMatch(n::contains)) {
                    return i;
                }
            }
        }
        for (String cle : motsCles) {
            for (int i = 0; i < enTetes.size(); i++) {
                String n = normaliserEntete(enTetes.get(i));
                if (n.contains(cle) && exclusions.stream().noneMatch(n::contains)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Valeur d'une colonne, chaine vide si la colonne est absente du fichier. */
    private String lire(List<String> v, int index) {
        return (index < 0 || index >= v.size()) ? "" : v.get(index);
    }

    /**
     * Reconnait une ligne de total : aucun compte, mais un libelle du type
     * « TOTAL », « TOTAUX », « CUMUL » ou « SOUS-TOTAL ».
     */
    private boolean estLigneTotal(List<String> valeurs) {
        for (String cellule : valeurs) {
            String n = normaliserEntete(cellule);
            if (n.startsWith("total") || n.startsWith("totaux")
                || n.startsWith("soustotal") || n.startsWith("cumul")
                || n.startsWith("totalgeneral")) {
                return true;
            }
        }
        return false;
    }

    private String normaliserEntete(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(java.util.Locale.FRENCH)
            .replaceAll("[^a-z0-9]", "");
    }

    // -----------------------------------------------------------------
    // Lecture Excel
    // -----------------------------------------------------------------

    private List<LigneImport> lireExcel(InputStream in, List<String> erreurs) throws IOException {
        List<LigneImport> resultat = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = trouverFeuille(wb);
            if (sh == null) {
                erreurs.add("Aucune feuille exploitable dans le classeur.");
                return resultat;
            }
            int ligneEntete = trouverEntete(sh);
            if (ligneEntete < 0) {
                erreurs.add("En-tetes introuvables. La feuille doit comporter une ligne avec les colonnes : "
                    + String.join(", ", COLONNES));
                return resultat;
            }
            DataFormatter fmt = new DataFormatter();
            Mapping map = resoudreMapping(valeursLigne(sh.getRow(ligneEntete), fmt));
            if (!map.complet()) {
                erreurs.add("Colonnes introuvables : il faut au minimum Date, Compte, Débit et Crédit."
                    + " En-têtes lus : " + String.join(" | ", valeursLigne(sh.getRow(ligneEntete), fmt)));
                return resultat;
            }
            for (int i = ligneEntete + 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                // Une ligne est ignoree si les champs utiles sont tous vides,
                // meme si une colonne annexe reste renseignee : la recopie vers
                // le bas d'un tableur laisse souvent des centaines de lignes
                // ne portant plus qu'une periode ou un centre de cout, qui
                // etaient comptees comme des ecritures a importer.
                if (row == null || estLigneVide(row, fmt) || estLigneResiduelle(row, fmt, map)) {
                    continue;
                }
                if (resultat.size() >= MAX_LIGNES) {
                    erreurs.add("Fichier trop long : maximum " + MAX_LIGNES + " lignes.");
                    break;
                }
                lireLigne(i + 1, valeursLigne(row, fmt), row, map, erreurs).ifPresent(resultat::add);
            }
        }
        return resultat;
    }

    /** Feuille nommee « Journal » si elle existe (classeur exporte), sinon la premiere. */
    private Sheet trouverFeuille(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if ("Journal".equalsIgnoreCase(wb.getSheetName(i))) {
                return wb.getSheetAt(i);
            }
        }
        return wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : null;
    }

    /**
     * Localise la ligne d'en-tete. Le classeur exporte porte un titre et un
     * sous-titre avant les colonnes : on cherche donc la premiere ligne
     * contenant « Compte » et « Débit » plutot que de supposer la ligne 1.
     */
    private int trouverEntete(Sheet sh) {
        DataFormatter fmt = new DataFormatter();
        for (int i = 0; i <= Math.min(sh.getLastRowNum(), 30); i++) {
            Row row = sh.getRow(i);
            if (row == null) continue;
            String ligne = String.join("|", valeursLigne(row, fmt)).toLowerCase();
            if (ligne.contains("compte") && (ligne.contains("débit") || ligne.contains("debit"))) {
                return i;
            }
        }
        return -1;
    }

    private List<String> valeursLigne(Row row, DataFormatter fmt) {
        List<String> v = new ArrayList<>();
        for (int c = 0; c < 40; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            v.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
        }
        return v;
    }

    /** Vide sur compte, debit et credit : rien d'exploitable, quoi que portent les autres colonnes. */
    private boolean estLigneResiduelle(Row row, DataFormatter fmt, Mapping map) {
        List<String> v = valeursLigne(row, fmt);
        return lire(v, map.compte()).isBlank()
            && lire(v, map.debit()).isBlank()
            && lire(v, map.credit()).isBlank();
    }

    private boolean estLigneVide(Row row, DataFormatter fmt) {
        return valeursLigne(row, fmt).stream().allMatch(String::isBlank);
    }

    // -----------------------------------------------------------------
    // Lecture CSV
    // -----------------------------------------------------------------

    private List<LigneImport> lireCsv(InputStream in, List<String> erreurs) throws IOException {
        List<LigneImport> resultat = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String ligne;
            int numero = 0;
            boolean enteteVue = false;
            // Positions canoniques par defaut si le fichier n'a pas d'en-tete.
            Mapping map = new Mapping(0, 1, 2, 3, 4, 5, 6, 7, -1, -1);
            while ((ligne = r.readLine()) != null) {
                numero++;
                if (ligne.isBlank()) continue;
                List<String> champs = decouper(ligne);
                String concat = String.join("|", champs).toLowerCase();
                if (!enteteVue) {
                    if (concat.contains("compte") && (concat.contains("débit") || concat.contains("debit"))) {
                        map = resoudreMapping(champs);
                        enteteVue = true;
                        if (!map.complet()) {
                            erreurs.add("Colonnes introuvables : il faut au minimum Date, Compte,"
                                + " Débit et Crédit. En-têtes lus : " + String.join(" | ", champs));
                            return resultat;
                        }
                        continue;
                    }
                    enteteVue = true;
                }
                if (resultat.size() >= MAX_LIGNES) {
                    erreurs.add("Fichier trop long : maximum " + MAX_LIGNES + " lignes.");
                    break;
                }
                lireLigne(numero, champs, null, map, erreurs).ifPresent(resultat::add);
            }
        }
        return resultat;
    }

    /** Decoupage CSV tolerant : separateur ; ou , et guillemets optionnels. */
    private List<String> decouper(String ligne) {
        char sep = ligne.chars().filter(c -> c == ';').count() >= ligne.chars().filter(c -> c == ',').count()
            ? ';' : ',';
        List<String> champs = new ArrayList<>();
        StringBuilder courant = new StringBuilder();
        boolean guillemets = false;
        for (char c : ligne.toCharArray()) {
            if (c == '"') {
                guillemets = !guillemets;
            } else if (c == sep && !guillemets) {
                champs.add(courant.toString().trim());
                courant.setLength(0);
            } else {
                courant.append(c);
            }
        }
        champs.add(courant.toString().trim());
        while (champs.size() < 40) champs.add("");
        return champs;
    }

    // -----------------------------------------------------------------
    // Typage d'une ligne
    // -----------------------------------------------------------------

    private Optional<LigneImport> lireLigne(int numero, List<String> v, Row row, Mapping map,
                                            List<String> erreurs) {
        String dateTexte = lire(v, map.date());
        String reference = lire(v, map.piece());
        String journalTexte = lire(v, map.journal());
        String compte = lire(v, map.compte());
        String intituleCompte = lire(v, map.intituleCompte());
        String libelle = lire(v, map.libelle());
        String debitTexte = lire(v, map.debit());
        String creditTexte = lire(v, map.credit());

        // Une ligne sans compte NI montant n'est pas une erreur : c'est une
        // ligne decorative, un sous-total ou un reliquat de mise en forme.
        // La signaler noyait les vraies anomalies sous des centaines de
        // messages sans objet.
        boolean sansMontant = !StringUtils.hasText(debitTexte) && !StringUtils.hasText(creditTexte);
        if (!StringUtils.hasText(compte) && sansMontant) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(compte)) {
            // Ligne de total en pied de tableau : des montants, aucun compte,
            // et un libelle qui l'annonce. Ce n'est pas une ecriture — la
            // signaler comme anomalie, ou pire l'importer, doublerait les
            // montants du journal.
            if (estLigneTotal(v)) {
                return Optional.empty();
            }
            erreurs.add("Ligne " + numero + " : montant renseigne sans numero de compte");
            return Optional.empty();
        }
        if (sansMontant) {
            erreurs.add("Ligne " + numero + " : compte " + compte + " sans debit ni credit");
            return Optional.empty();
        }

        LocalDate date = lireDate(dateTexte, row, map.date());
        if (date == null) {
            erreurs.add("Ligne " + numero + " : date illisible (" + dateTexte + ")."
                + " Formats acceptes : JJ/MM/AAAA ou AAAA-MM-JJ.");
            return Optional.empty();
        }

        BigDecimal debit = lireMontant(debitTexte, row, map.debit());
        BigDecimal credit = lireMontant(creditTexte, row, map.credit());
        if (debit == null || credit == null) {
            erreurs.add("Ligne " + numero + " : montant illisible (debit=" + debitTexte
                + ", credit=" + creditTexte + ")");
            return Optional.empty();
        }
        if (debit.signum() < 0 || credit.signum() < 0) {
            erreurs.add("Ligne " + numero + " : montant negatif interdit."
                + " Inversez le sens debit/credit plutot que d'utiliser un signe moins.");
            return Optional.empty();
        }
        if (debit.signum() > 0 && credit.signum() > 0) {
            erreurs.add("Ligne " + numero + " : une ecriture ne peut pas porter"
                + " a la fois un debit et un credit");
            return Optional.empty();
        }
        if (debit.signum() == 0 && credit.signum() == 0) {
            erreurs.add("Ligne " + numero + " : compte " + compte + " avec un montant nul");
            return Optional.empty();
        }

        // Sans reference de piece, chaque ligne serait une piece isolee — donc
        // desequilibree. On regroupe alors par date + libelle, ce qui reconstitue
        // les pieces d'un fichier ou la colonne n'a pas ete remplie.
        String refPiece = StringUtils.hasText(reference)
            ? reference : "IMPORT-" + date + "-" + (StringUtils.hasText(libelle) ? libelle.hashCode() : 0);

        BigDecimal dUsd = lireMontant(lire(v, map.debitUsd()), row, map.debitUsd());
        BigDecimal cUsd = lireMontant(lire(v, map.creditUsd()), row, map.creditUsd());
        BigDecimal enDevise = (dUsd == null ? BigDecimal.ZERO : dUsd)
            .add(cUsd == null ? BigDecimal.ZERO : cUsd);

        return Optional.of(new LigneImport(numero, date, refPiece, lireJournal(journalTexte),
            compte.trim(), intituleCompte, libelle, debit, credit,
            enDevise.signum() > 0 ? enDevise : null));
    }

    private LocalDate lireDate(String texte, Row row, int colonne) {
        // Excel stocke les dates en numerique : on privilegie la valeur typee
        // de la cellule, le texte formate n'etant qu'un repli.
        if (row != null) {
            Cell cell = row.getCell(colonne, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
        }
        if (!StringUtils.hasText(texte)) {
            return null;
        }
        for (DateTimeFormatter f : FORMATS_DATE) {
            try {
                return LocalDate.parse(texte.trim(), f);
            } catch (DateTimeParseException ignored) {
                // format suivant
            }
        }
        return null;
    }

    /**
     * Montant d'une cellule.
     *
     * <p>Pour un classeur Excel, la valeur numerique de la cellule fait foi :
     * le texte affiche depend du format et induit en erreur. Un montant de
     * 1000 formate en {@code #,##0} s'affiche « 1,000 », que l'analyse
     * textuelle prenait pour un decimal francais valant 1 — les totaux etaient
     * alors divises par mille sans qu'aucune anomalie ne soit signalee.</p>
     */
    private BigDecimal lireMontant(String texte, Row row, int colonne) {
        if (row != null && colonne >= 0) {
            Cell cell = row.getCell(colonne, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) {
                CellType type = cell.getCellType() == CellType.FORMULA
                    ? cell.getCachedFormulaResultType() : cell.getCellType();
                if (type == CellType.NUMERIC) {
                    return BigDecimal.valueOf(cell.getNumericCellValue())
                        .setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return lireMontant(texte);
    }

    /** Analyse textuelle, pour le CSV et les cellules Excel de type texte. */
    private BigDecimal lireMontant(String texte) {
        if (!StringUtils.hasText(texte) || "-".equals(texte.trim())) {
            return BigDecimal.ZERO;
        }
        String nettoye = texte.replace(" ", "").replace(" ", "").trim();
        int derniereVirgule = nettoye.lastIndexOf(',');
        int dernierPoint = nettoye.lastIndexOf('.');
        int dernier = Math.max(derniereVirgule, dernierPoint);
        // Un unique separateur suivi d'exactement trois chiffres separe les
        // milliers, pas les decimales : « 1,000 » vaut mille, pas un.
        boolean separateurUnique = (derniereVirgule < 0) != (dernierPoint < 0)
            && nettoye.indexOf(derniereVirgule < 0 ? '.' : ',') == dernier;
        if (separateurUnique && nettoye.length() - dernier - 1 == 3) {
            nettoye = nettoye.replace(",", "").replace(".", "");
        } else if (derniereVirgule > dernierPoint) {
            nettoye = nettoye.replace(".", "").replace(',', '.');   // format francais
        } else {
            nettoye = nettoye.replace(",", "");                     // format anglo-saxon
        }
        try {
            return new BigDecimal(nettoye);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JournalComptable lireJournal(String texte) {
        if (!StringUtils.hasText(texte)) {
            return JournalComptable.OPERATIONS_DIVERSES;
        }
        String normalise = texte.trim().toUpperCase().replace(' ', '_').replace('É', 'E');
        for (JournalComptable j : JournalComptable.values()) {
            if (j.name().equals(normalise)) {
                return j;
            }
        }
        return JournalComptable.OPERATIONS_DIVERSES;
    }

    // -----------------------------------------------------------------
    // Correction automatique
    // -----------------------------------------------------------------

    /**
     * Applique d'un coup toutes les corrections de comptes proposees : cree
     * les comptes absents du plan et retient les sous-comptes de saisie pour
     * les comptes de regroupement.
     *
     * <p>Reste une action <b>explicite</b> de l'administrateur, et n'importe
     * rien : elle ne fait que preparer le plan comptable et renvoyer les
     * substitutions a appliquer. Creer des comptes modifie durablement le
     * referentiel — cela ne doit pas arriver comme effet de bord d'une simple
     * analyse de fichier.</p>
     *
     * @return substitutions « ancien:nouveau » a transmettre a l'import
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    @Transactional
    public Map<String, String> corrigerAutomatiquement(MultipartFile fichier, ModeRegroupement mode) {
        ImportJournalResponse analyse = importer(fichier, true, Map.of(), mode, null, ModeImport.AJOUTER);
        Map<String, String> substitutions = new LinkedHashMap<>();

        for (SuggestionImport s : analyse.suggestions()) {
            if (s.valeurProposee() == null) {
                continue;   // ambigu : laisse a l'arbitrage de l'utilisateur
            }
            if ("CREATION".equals(s.type())) {
                creerCompteManquant(s).ifPresent(
                    numero -> substitutions.put(s.valeurActuelle(), numero));
            } else if ("COMPTE".equals(s.type())) {
                substitutions.put(s.valeurActuelle(), s.valeurProposee());
            }
        }
        log.info("Correction automatique : {} substitution(s) preparee(s)", substitutions.size());
        return substitutions;
    }

    /**
     * Cree le sous-compte propose sous son parent, dont il herite du type et
     * de la classe. Sans effet si le compte existe deja — la correction doit
     * pouvoir etre relancee sans creer de doublon.
     *
     * <p><b>Aucun compte existant n'est jamais renomme depuis le fichier</b>,
     * qu'il vienne du referentiel OHADA ou d'un import precedent : le plan
     * comptable fait autorite sur les intitules. Une version anterieure
     * reactualisait le nom des comptes crees par import, au motif que leur nom
     * venait de toute facon du fichier ; mais cela reinjectait a chaque import
     * les fautes du fichier source (« CAARBURANT ») dans le plan comptable, et
     * donc dans le bilan et tous les etats financiers. Un intitule errone se
     * corrige desormais une fois pour toutes dans l'ecran Plan comptable, sans
     * qu'un import ulterieur ne le recouvre.</p>
     */
    private Optional<String> creerCompteManquant(SuggestionImport s) {
        String numero = s.valeurProposee();
        Optional<CompteOHADA> existant = compteRepository.findByNumero(numero);
        if (existant.isPresent()) {
            // Le compte existe : on s'y rattache tel quel, intitule du plan compris.
            return Optional.of(numero);
        }
        int sep = numero.lastIndexOf('.');
        if (sep <= 0) {
            return Optional.empty();
        }
        CompteOHADA parent = compteRepository.findByNumero(numero.substring(0, sep)).orElse(null);
        if (parent == null) {
            return Optional.empty();
        }
        CompteOHADA cree = compteRepository.save(CompteOHADA.builder()
            .numero(numero)
            // A defaut d'intitule propose, le compte herite de celui de son
            // parent — jamais un texte reconstruit a partir du fichier.
            .libelle(StringUtils.hasText(s.libelle()) ? s.libelle() : parent.getLibelle())
            .type(parent.getType())
            .classe(parent.getClasse())
            .parent(parent)
            .manuel(true)
            .build());
        log.info("Compte cree automatiquement : {} ({}) sous {}",
            cree.getNumero(), cree.getLibelle(), parent.getNumero());
        return Optional.of(cree.getNumero());
    }

    // -----------------------------------------------------------------
    // Fichier corrige
    // -----------------------------------------------------------------

    /**
     * Reconstruit le fichier avec les corrections appliquees, pret a etre
     * reimporte tel quel.
     *
     * <p>Appliquer les substitutions en memoire suffit a debloquer l'import,
     * mais ne laisse aucune trace : le comptable garde un fichier source qui
     * ne correspond plus a ce qui a ete comptabilise. Le fichier produit ici
     * est archivable et rejouable, et porte en derniere colonne le compte
     * d'origine de chaque ligne modifiee — la correction reste donc verifiable
     * apres coup. Cette colonne se trouve au-dela des huit colonnes lues par
     * l'import : elle ne gene pas une reimportation.</p>
     *
     * <p>La sortie est normalisee (dates en JJ/MM/AAAA, montants numeriques,
     * reference de piece toujours renseignee) : les approximations tolerees en
     * lecture disparaissent, ce qui evite qu'un second import bute sur un
     * detail de format.</p>
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    public byte[] genererFichierCorrige(MultipartFile fichier, Map<String, String> substitutions) {
        if (fichier == null || fichier.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aucun fichier fourni.");
        }
        String nom = fichier.getOriginalFilename() == null ? "" : fichier.getOriginalFilename().toLowerCase();

        List<String> erreurs = new ArrayList<>();
        List<LigneImport> lignes;
        try (InputStream in = fichier.getInputStream()) {
            lignes = nom.endsWith(".csv") ? lireCsv(in, erreurs) : lireExcel(in, erreurs);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Fichier illisible : " + e.getMessage());
        }
        if (lignes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Aucune ligne exploitable : impossible de produire un fichier corrige.");
        }

        Map<String, String> subs = substitutions == null ? Map.of() : substitutions;

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Journal");

            CellStyle enTete = wb.createCellStyle();
            Font gras = wb.createFont();
            gras.setBold(true);
            enTete.setFont(gras);
            enTete.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            enTete.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Ligne corrigee mise en evidence, pour une relecture rapide.
            CellStyle corrige = wb.createCellStyle();
            corrige.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            corrige.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row r0 = sh.createRow(0);
            for (int c = 0; c < COLONNES.length; c++) {
                Cell cell = r0.createCell(c);
                cell.setCellValue(COLONNES[c]);
                cell.setCellStyle(enTete);
            }
            Cell suivi = r0.createCell(COLONNES.length);
            suivi.setCellValue("Compte d'origine (corrigé)");
            suivi.setCellStyle(enTete);

            int ligne = 1;
            int nbCorrigees = 0;
            for (LigneImport l : lignes) {
                String remplacement = subs.get(l.compte());
                boolean modifiee = remplacement != null && !remplacement.equals(l.compte());
                String compteFinal = modifiee ? remplacement : l.compte();

                Row row = sh.createRow(ligne++);
                ecrireTexte(row, 0, l.date().format(DATE_FR), modifiee ? corrige : null);
                ecrireTexte(row, 1, l.referencePiece(), modifiee ? corrige : null);
                ecrireTexte(row, 2, l.journal().name(), modifiee ? corrige : null);
                ecrireTexte(row, 3, compteFinal, modifiee ? corrige : null);
                ecrireTexte(row, 4, libelleCompte(compteFinal, l.intituleCompte()), modifiee ? corrige : null);
                ecrireTexte(row, 5, l.libelle() == null ? "" : l.libelle(), modifiee ? corrige : null);
                ecrireNombre(row, 6, l.debit(), modifiee ? corrige : null);
                ecrireNombre(row, 7, l.credit(), modifiee ? corrige : null);
                // Colonnes en devise : reportees telles quelles, le fichier
                // corrige devant rester reimportable a l'identique.
                ecrireNombre(row, 8, l.montantDevise() != null && l.debit().signum() > 0
                    ? l.montantDevise() : null, modifiee ? corrige : null);
                ecrireNombre(row, 9, l.montantDevise() != null && l.credit().signum() > 0
                    ? l.montantDevise() : null, modifiee ? corrige : null);
                if (modifiee) {
                    ecrireTexte(row, COLONNES.length, l.compte(), corrige);
                    nbCorrigees++;
                }
            }

            for (int c = 0; c <= COLONNES.length; c++) {
                sh.setColumnWidth(c, 4800);
            }
            sh.createFreezePane(0, 1);

            log.info("Fichier corrige genere : {} ligne(s), {} correction(s) appliquee(s)",
                lignes.size(), nbCorrigees);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de generer le fichier corrige", e);
        }
    }

    /**
     * Intitule du compte, pour que le fichier corrige reste lisible.
     *
     * <p>Le plan comptable fait autorite : son intitule ecrase celui du
     * fichier des que le compte y figure. Celui du fichier n'est repris que
     * pour un compte encore inconnu du referentiel, ou il est la seule
     * information disponible.</p>
     */
    private String libelleCompte(String numero, String intituleFichier) {
        return compteRepository.findByNumero(numero)
            .map(c -> c.getLibelle() == null ? "" : c.getLibelle())
            .filter(StringUtils::hasText)
            .orElseGet(() -> intituleFichier == null ? "" : intituleFichier.trim());
    }

    private void ecrireTexte(Row row, int col, String valeur, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(valeur == null ? "" : valeur);
        if (style != null) c.setCellStyle(style);
    }

    private void ecrireNombre(Row row, int col, BigDecimal valeur, CellStyle style) {
        Cell c = row.createCell(col);
        if (valeur != null && valeur.signum() != 0) {
            c.setCellValue(valeur.doubleValue());
        } else {
            c.setCellValue("");
        }
        if (style != null) c.setCellStyle(style);
    }

    // -----------------------------------------------------------------
    // Modele de fichier
    // -----------------------------------------------------------------

    /** Classeur vierge aux bonnes colonnes, avec une ecriture d'exemple. */
    @PreAuthorize("hasAnyRole('ADMIN', 'DFIN')")
    public byte[] modele() {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Journal");

            CellStyle entete = wb.createCellStyle();
            Font gras = wb.createFont();
            gras.setBold(true);
            entete.setFont(gras);
            entete.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row r0 = sh.createRow(0);
            for (int c = 0; c < COLONNES.length; c++) {
                Cell cell = r0.createCell(c);
                cell.setCellValue(COLONNES[c]);
                cell.setCellStyle(entete);
            }

            // Exemple : une piece equilibree de deux lignes.
            Object[][] exemple = {
                {"15/01/2026", "OD-2026-001", "OPERATIONS_DIVERSES", "6047", "Fournitures de bureau", "Achat papier", 12500, ""},
                {"15/01/2026", "OD-2026-001", "OPERATIONS_DIVERSES", "401", "Fournisseurs", "Achat papier", "", 12500},
            };
            for (int i = 0; i < exemple.length; i++) {
                Row r = sh.createRow(i + 1);
                for (int c = 0; c < exemple[i].length; c++) {
                    Cell cell = r.createCell(c);
                    Object val = exemple[i][c];
                    if (val instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(val));
                    }
                }
            }
            for (int c = 0; c < COLONNES.length; c++) {
                sh.setColumnWidth(c, 4500);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de generer le modele", e);
        }
    }
}
