package com.mbsc.finapp.service.patrimoine;

import com.mbsc.finapp.domain.Article;
import com.mbsc.finapp.domain.CompteOHADA;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.TypeArticle;
import com.mbsc.finapp.dto.notes.LigneNoteFraisRequest;
import com.mbsc.finapp.dto.notes.NoteFraisDetailResponse;
import com.mbsc.finapp.dto.notes.NoteFraisRequest;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.repository.ArticleRepository;
import com.mbsc.finapp.repository.CompteOHADARepository;
import com.mbsc.finapp.repository.EntrepotRepository;
import com.mbsc.finapp.domain.Entrepot;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.service.ConversionDeviseService;
import com.mbsc.finapp.service.NoteFraisService;
import com.mbsc.finapp.service.StorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consommables (fournitures consommées en interne) : création simplifiée et achat groupé.
 *
 * <ul>
 *   <li><b>Création</b> : nom, prix d'achat, unité, seuil et type suffisent. Le système attribue le code (CONS-000001)
 *       et crée les sous-comptes propres au consommable : stock (sous le compte 33x du type) et achat (sous le 604x du
 *       type) ; la variation de stock passe en 6033. Une facture proforma (image ou PDF) peut y être jointe.</li>
 *   <li><b>Achat</b> : plusieurs consommables et leurs quantités en une fois ; une note de frais est créée et soumise
 *       au DFIN (circuit DFIN, DA, DFIN, caisse). Au paiement, les quantités entrent en stock (règle commune des achats
 *       de marchandise) ; un retour de fonds sur la note ressort du stock la quantité rendue.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsommableService {

    static final String ECRITURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'DFIN', 'ADMIN')";
    static final String LECTURE = "hasAnyRole('LOGISTIQUE', 'GEST_PATRIMOINE', 'DFIN', 'DA', 'DG', 'COMPTABLE', 'CAISSIER', 'ADMIN')";
    private static final Set<String> TYPES_PROFORMA = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");
    private static final String COMPTE_VARIATION = "6033";
    private static final String CODE_MAGASIN = "MAG-CONS";

    /** Type d'un consommable : il fixe les comptes de rattachement (stock 33x, achat 604x). */
    public enum TypeConsommable {
        FOURNITURES_BUREAU("Fournitures de bureau", "334", "6047"),
        PRODUITS_ENTRETIEN("Produits d'entretien", "338", "6043"),
        CARBURANT("Carburant et combustibles", "338", "6042"),
        FOURNITURES_ATELIER("Fournitures d'atelier", "331", "6044"),
        FOURNITURES_MAGASIN("Fournitures de magasin", "333", "6046"),
        MATIERES_CONSOMMABLES("Matières consommables", "331", "6041");

        public final String libelle;
        public final String compteStock;
        public final String compteAchat;

        TypeConsommable(String libelle, String compteStock, String compteAchat) {
            this.libelle = libelle;
            this.compteStock = compteStock;
            this.compteAchat = compteAchat;
        }
    }

    public record ConsommableRequest(@NotBlank @Size(max = 150) String libelle, @NotNull TypeConsommable type,
                                     @Size(max = 20) String uniteMesure, @NotNull @Positive BigDecimal prixAchat,
                                     @PositiveOrZero BigDecimal stockMin) { }

    public record Consommable(Long id, String code, String libelle, String type, String typeLibelle, String uniteMesure,
                              BigDecimal prixAchat, BigDecimal stockMin, boolean actif, String compteStock,
                              String compteAchat, String proformaNom, String proformaType, BigDecimal quantite,
                              BigDecimal valeur, boolean sousSeuil) { }

    public record LigneAchat(@NotNull Long consommableId, @NotNull @Positive BigDecimal quantite,
                             @Size(max = 300) String observation) { }

    public record AchatRequest(@NotEmpty @Valid List<LigneAchat> lignes, @Size(max = 200) String objet,
                               @Size(max = 1000) String justificationBudget) { }

    public record AchatResponse(Long noteId, String reference, String statut, BigDecimal montant) { }

    public record Fichier(org.springframework.core.io.Resource ressource, String typeContenu, String nom) { }

    private final JdbcTemplate jdbc;
    private final ArticleRepository articleRepository;
    private final CompteOHADARepository compteRepository;
    private final CurrentUserProvider currentUser;
    private final StorageService stockage;
    private final NoteFraisService notesFrais;
    private final EntrepotRepository entrepotRepository;

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public List<Consommable> lister() {
        return jdbc.query("""
            select a.id, a.code, a.libelle, a.type_consommable, a.unite_mesure, a.prix_achat, a.stock_min, a.actif,
                   cs.numero cs, ca.numero ca, a.proforma_nom, a.proforma_type,
                   coalesce((select sum(n.quantite) from stock_niveaux n where n.article_id = a.id), 0) qte,
                   coalesce((select sum(n.valeur_totale) from stock_niveaux n where n.article_id = a.id), 0) val
            from articles a
            left join comptes_ohada cs on cs.id = a.compte_stock_id
            left join comptes_ohada ca on ca.id = a.compte_achat_id
            where a.type = 'CONSOMMABLE'
            order by a.libelle
            """, (rs, i) -> {
            String type = rs.getString("type_consommable");
            BigDecimal qte = rs.getBigDecimal("qte");
            BigDecimal min = rs.getBigDecimal("stock_min");
            return new Consommable(rs.getLong("id"), rs.getString("code"), rs.getString("libelle"), type,
                type == null ? null : TypeConsommable.valueOf(type).libelle, rs.getString("unite_mesure"),
                rs.getBigDecimal("prix_achat"), min, rs.getBoolean("actif"), rs.getString("cs"), rs.getString("ca"),
                rs.getString("proforma_nom"), rs.getString("proforma_type"), qte,
                rs.getBigDecimal("val").setScale(2, RoundingMode.HALF_UP), min != null && min.signum() > 0 && qte.compareTo(min) <= 0);
        });
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Consommable creer(ConsommableRequest r) {
        String libelle = r.libelle().trim();
        Number n = jdbc.queryForObject("select nextval('seq_consommable')", Number.class);
        String code = "CONS-%06d".formatted(n.longValue());
        while (articleRepository.existsByCode(code)) {
            code = "CONS-%06d".formatted(jdbc.queryForObject("select nextval('seq_consommable')", Number.class).longValue());
        }
        CompteOHADA stock = sousCompte(r.type().compteStock, libelle);
        CompteOHADA achat = sousCompte(r.type().compteAchat, "Achat " + libelle);
        CompteOHADA variation = compteRepository.findByNumero(COMPTE_VARIATION)
            .orElseThrow(() -> new IllegalStateException("Compte " + COMPTE_VARIATION + " absent du plan comptable"));

        Article a = new Article();
        a.setCode(code);
        a.setLibelle(libelle);
        a.setType(TypeArticle.CONSOMMABLE);
        a.setUniteMesure(StringUtils.hasText(r.uniteMesure()) ? r.uniteMesure().trim() : null);
        a.setPrixAchat(r.prixAchat());
        a.setStockMin(r.stockMin() == null ? BigDecimal.ZERO : r.stockMin());
        a.setSoumisTva(false);
        a.setActif(true);
        a.setCompteStock(stock);
        a.setCompteAchat(achat);
        a.setCompteCharge(variation);
        Article saved = articleRepository.save(a);
        articleRepository.flush();
        jdbc.update("update articles set type_consommable = ? where id = ?", r.type().name(), saved.getId());
        AuditService.detailler("Consommable " + code + " « " + libelle + " » créé (" + r.type().libelle + ") : comptes "
            + stock.getNumero() + " (stock) et " + achat.getNumero() + " (achat).");
        return un(saved.getId());
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Consommable modifier(Long id, ConsommableRequest r) {
        Article a = consommable(id);
        String libelle = r.libelle().trim();
        a.setLibelle(libelle);
        a.setUniteMesure(StringUtils.hasText(r.uniteMesure()) ? r.uniteMesure().trim() : null);
        a.setPrixAchat(r.prixAchat());
        a.setStockMin(r.stockMin() == null ? BigDecimal.ZERO : r.stockMin());
        articleRepository.save(a);
        AuditService.detailler("Consommable " + a.getCode() + " modifié.");
        return un(id);
    }

    @PreAuthorize(ECRITURE)
    @Transactional
    public Consommable joindreProforma(Long id, MultipartFile fichier) {
        Article a = consommable(id);
        if (fichier == null || fichier.isEmpty()) throw new IllegalArgumentException("Proforma vide.");
        String type = fichier.getContentType() == null ? "" : fichier.getContentType();
        if (!TYPES_PROFORMA.contains(type)) throw new IllegalArgumentException("Proforma : image (JPEG, PNG, WebP) ou PDF seulement.");
        if (fichier.getSize() > 15L * 1024 * 1024) throw new IllegalArgumentException("Proforma trop lourde (15 Mo au plus).");
        String ancien = jdbc.query("select proforma_chemin from articles where id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        String chemin = stockage.enregistrer("consommables/proformas", fichier);
        jdbc.update("update articles set proforma_chemin = ?, proforma_nom = ?, proforma_type = ? where id = ?",
            chemin, fichier.getOriginalFilename(), type, id);
        if (ancien != null) {
            try { stockage.supprimer(ancien); } catch (RuntimeException ignore) { /* référence retirée : sans conséquence */ }
        }
        AuditService.detailler("Facture proforma jointe au consommable " + a.getCode() + ".");
        return un(id);
    }

    @PreAuthorize(LECTURE)
    @Transactional(readOnly = true)
    public Fichier proforma(Long id) {
        return jdbc.query("select proforma_chemin, proforma_type, proforma_nom from articles where id = ? and proforma_chemin is not null",
            rs -> rs.next() ? new Fichier(stockage.charger(rs.getString(1)), rs.getString(2), rs.getString(3)) : null, id);
    }

    /**
     * Achat de plusieurs consommables : une note de frais (une ligne d'achat par consommable, au prix d'achat
     * enregistré) est créée au nom de l'utilisateur et soumise au DFIN.
     */
    @PreAuthorize(ECRITURE)
    @Transactional
    public AchatResponse acheter(AchatRequest r) {
        User auteur = currentUser.requireUser();
        Map<Long, LigneAchat> parConsommable = new LinkedHashMap<>();
        for (LigneAchat l : r.lignes()) {
            if (parConsommable.putIfAbsent(l.consommableId(), l) != null) {
                throw new IllegalArgumentException("Un même consommable figure deux fois dans l'achat : regroupez les quantités.");
            }
        }
        Long entrepotId = entrepotDesConsommables().getId();
        List<LigneNoteFraisRequest> lignes = new ArrayList<>();
        List<String> noms = new ArrayList<>();
        for (LigneAchat l : parConsommable.values()) {
            Article a = consommable(l.consommableId());
            if (!a.isActif()) throw new IllegalArgumentException("Le consommable « " + a.getLibelle() + " » est désactivé.");
            if (a.getPrixAchat() == null || a.getPrixAchat().signum() <= 0) {
                throw new IllegalArgumentException("Le consommable « " + a.getLibelle() + " » n'a pas de prix d'achat.");
            }
            // Ligne d'achat : le montant est le PRIX UNITAIRE, la note le multiplie par la quantité (LigneNoteFrais#montantHtTotal).
            BigDecimal montant = a.getPrixAchat().setScale(2, RoundingMode.HALF_UP);
            String unite = a.getUniteMesure() == null ? "" : " " + a.getUniteMesure();
            String description = a.getLibelle() + " — " + l.quantite().stripTrailingZeros().toPlainString() + unite
                + (StringUtils.hasText(l.observation()) ? " (" + l.observation().trim() + ")" : "");
            lignes.add(new LigneNoteFraisRequest(montant, null, description.length() > 500 ? description.substring(0, 500) : description,
                true, l.quantite(), a.getId(), null, entrepotId, false, null, false, false));
            noms.add(a.getLibelle());
        }
        String objet = StringUtils.hasText(r.objet()) ? r.objet().trim()
            : "Achat de consommables : " + String.join(", ", noms);
        if (objet.length() > 200) objet = objet.substring(0, 197) + "...";
        String beneficiaire = ((auteur.getPrenom() == null ? "" : auteur.getPrenom() + " ") + (auteur.getNom() == null ? "" : auteur.getNom())).trim();
        NoteFraisDetailResponse note = notesFrais.creerEtSoumettreAchatInterne(new NoteFraisRequest(objet,
            beneficiaire.isEmpty() ? auteur.getEmail() : beneficiaire, null, ConversionDeviseService.DEVISE_BASE,
            SensTransaction.DECAISSEMENT, lignes,
            StringUtils.hasText(r.justificationBudget()) ? r.justificationBudget().trim() : null, null),
            "Achat de consommables", "Demande d'achat de consommables");
        AuditService.detailler("Achat de consommables : note " + note.reference() + " (" + note.montant().toPlainString()
            + " " + note.devise() + ") soumise au DFIN.");
        return new AchatResponse(note.id(), note.reference(), note.statut().name(), note.montant());
    }

    /**
     * Entrepôt où entrent les consommables achetés : le magasin des consommables s'il existe, sinon le premier entrepôt
     * actif ; aucun entrepôt du tout : le magasin des consommables est créé.
     */
    private Entrepot entrepotDesConsommables() {
        return entrepotRepository.findByCode(CODE_MAGASIN).filter(Entrepot::isActif)
            .or(entrepotRepository::findFirstByActifTrueOrderByIdAsc)
            .orElseGet(() -> {
                Entrepot e = new Entrepot();
                e.setCode(CODE_MAGASIN);
                e.setNom("Magasin des consommables");
                e.setActif(true);
                Entrepot cree = entrepotRepository.save(e);
                AuditService.detailler("Entrepôt « Magasin des consommables » créé pour recevoir les achats de consommables.");
                return cree;
            });
    }

    private Consommable un(Long id) {
        return lister().stream().filter(c -> c.id().equals(id)).findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Consommable", id));
    }

    private Article consommable(Long id) {
        Article a = articleRepository.findById(id).orElseThrow(() -> RessourceIntrouvableException.of("Consommable", id));
        if (a.getType() != TypeArticle.CONSOMMABLE) throw RessourceIntrouvableException.of("Consommable", id);
        return a;
    }

    /** Sous-compte propre au consommable : parent + numéro d'ordre sur 2 chiffres (334 -> 33401, 33402...). */
    private CompteOHADA sousCompte(String parentNumero, String libelle) {
        CompteOHADA parent = compteRepository.findByNumero(parentNumero)
            .orElseThrow(() -> new IllegalStateException("Compte " + parentNumero + " absent du plan comptable"));
        for (int i = 1; i <= 99; i++) {
            String numero = parentNumero + "%02d".formatted(i);
            if (!compteRepository.existsByNumero(numero)) {
                CompteOHADA c = CompteOHADA.builder()
                    .numero(numero)
                    .libelle(libelle.length() > 200 ? libelle.substring(0, 200) : libelle)
                    .type(parent.getType())
                    .classe(parent.getClasse())
                    .parent(parent)
                    .manuel(true)
                    .imputable(true)
                    .actif(true)
                    .build();
                return compteRepository.save(c);
            }
        }
        throw new IllegalStateException("Plus de sous-compte libre sous " + parentNumero + " (99 au plus).");
    }
}
