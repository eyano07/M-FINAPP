package com.mbsc.finapp.service.patrimoine;

import com.mbsc.finapp.audit.AuditService;
import com.mbsc.finapp.exception.RessourceIntrouvableException;
import com.mbsc.finapp.exception.TransitionInvalideException;
import com.mbsc.finapp.security.CurrentUserProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Emplacements (affectations) du patrimoine, tenus par la logistique : bureaux, salles, magasins… et, pour
 * l'hôtellerie, les chambres, unités de motel et appartements, liés à l'unité correspondante de l'application
 * d'hôtellerie (recopiée dans {@code hotellerie_unites}). Un bien d'inventaire y est affecté au besoin.
 */
@Service
@RequiredArgsConstructor
public class EmplacementPatrimoineService {

    private final JdbcTemplate jdbc;
    private final CurrentUserProvider currentUser;

    public record Emplacement(Long id, String nom, String type, String description, Long pmsUniteId, String uniteLibelle,
                              boolean actif, int nbBiens) { }

    public record UniteHotellerie(Long pmsId, String numero, String typeNom, String etablissementNom, String etablissementType,
                                  String ameublement, boolean actif, Long emplacementId) { }

    private static final RowMapper<Emplacement> LIGNE = (rs, i) -> new Emplacement(rs.getLong("id"), rs.getString("nom"),
        rs.getString("type"), rs.getString("description"), (Long) rs.getObject("pms_unite_id"), rs.getString("unite_libelle"),
        rs.getBoolean("actif"), rs.getInt("nb_biens"));

    private static final String SELECT = """
        select e.*, (select count(*) from inventaire_biens b where b.emplacement_id = e.id) as nb_biens,
               case when u.pms_id is null then null else u.etablissement_nom || ' · ' || u.numero
                    || coalesce(' (' || u.type_nom || ')', '') end as unite_libelle
        from emplacements e left join hotellerie_unites u on u.pms_id = e.pms_unite_id
        """;

    @PreAuthorize(InventairePatrimoineService.LECTURE)
    @Transactional(readOnly = true)
    public List<Emplacement> lister() {
        return jdbc.query(SELECT + " order by e.actif desc, e.type, lower(e.nom)", LIGNE);
    }

    Emplacement emplacement(Long id) {
        return jdbc.query(SELECT + " where e.id = ?", LIGNE, id).stream().findFirst()
            .orElseThrow(() -> RessourceIntrouvableException.of("Emplacement", id));
    }

    /** Unités connues de l'hôtellerie, avec l'emplacement qui leur est déjà lié. */
    @PreAuthorize(InventairePatrimoineService.LECTURE)
    @Transactional(readOnly = true)
    public List<UniteHotellerie> unitesHotellerie() {
        return jdbc.query("""
            select u.*, e.id as emplacement_id from hotellerie_unites u left join emplacements e on e.pms_unite_id = u.pms_id
            where u.actif order by u.etablissement_nom, u.numero
            """, (rs, i) -> new UniteHotellerie(rs.getLong("pms_id"), rs.getString("numero"), rs.getString("type_nom"),
            rs.getString("etablissement_nom"), rs.getString("etablissement_type"), rs.getString("ameublement"), rs.getBoolean("actif"),
            (Long) rs.getObject("emplacement_id")));
    }

    public record EmplacementRequest(@NotBlank @Size(max = 120) String nom,
                                     @Pattern(regexp = "BUREAU|SALLE|MAGASIN|ATELIER|CHAMBRE|APPARTEMENT|UNITE_MOTEL|PARTIE_COMMUNE|EXTERIEUR|AUTRE") String type,
                                     @Size(max = 255) String description, Long pmsUniteId, Boolean actif) { }

    @PreAuthorize(InventairePatrimoineService.ECRITURE)
    @Transactional
    public Emplacement enregistrer(Long id, EmplacementRequest r) {
        String type = r.type() == null ? "BUREAU" : r.type();
        if (r.pmsUniteId() != null && jdbc.queryForList("select 1 from hotellerie_unites where pms_id = ?", Integer.class, r.pmsUniteId()).isEmpty()) {
            throw new IllegalArgumentException("Unité de l'hôtellerie inconnue : l'hôtellerie ne l'a pas encore transmise.");
        }
        boolean actif = r.actif() == null || r.actif();
        String auteur = currentUser.requireUser().getEmail();
        // Contrôles AVANT l'écriture : après un refus de la base, la transaction est interrompue et plus aucune requête
        // ne passe (le message clair se perdait dans une erreur interne).
        if (r.pmsUniteId() != null && !jdbc.queryForList("select 1 from emplacements where pms_unite_id = ? and id is distinct from ?",
            Integer.class, r.pmsUniteId(), id).isEmpty()) {
            throw new IllegalArgumentException("Cette unité de l'hôtellerie est déjà liée à un autre emplacement.");
        }
        if (!jdbc.queryForList("select 1 from emplacements where lower(nom) = lower(?) and id is distinct from ?", Integer.class,
            r.nom().strip(), id).isEmpty()) {
            throw new IllegalArgumentException("Un emplacement porte déjà ce nom.");
        }
        try {
            if (id == null) {
                id = jdbc.queryForObject("""
                    insert into emplacements (nom, type, description, pms_unite_id, actif, cree_par) values (?, ?, ?, ?, ?, ?) returning id
                    """, Long.class, r.nom().strip(), type, vide(r.description()), r.pmsUniteId(), actif, auteur);
            } else if (jdbc.update("""
                update emplacements set nom = ?, type = ?, description = ?, pms_unite_id = ?, actif = ?, maj_le = now() where id = ?
                """, r.nom().strip(), type, vide(r.description()), r.pmsUniteId(), actif, id) == 0) {
                throw RessourceIntrouvableException.of("Emplacement", id);
            }
        } catch (DuplicateKeyException e) {
            // Enregistrement simultané : même nom ou même unité, sans nouvelle requête (transaction interrompue).
            throw new IllegalArgumentException("Ce nom ou cette unité de l'hôtellerie est déjà pris par un autre emplacement.");
        }
        AuditService.detailler("Emplacement « " + r.nom().strip() + " » (" + type + ")"
            + (r.pmsUniteId() != null ? " lié à l'unité " + r.pmsUniteId() + " de l'hôtellerie" : "") + " enregistré.");
        return emplacement(id);
    }

    /** Suppression d'un emplacement vide ; un emplacement qui porte des biens se désactive. */
    @PreAuthorize(InventairePatrimoineService.ECRITURE)
    @Transactional
    public void supprimer(Long id) {
        Emplacement e = emplacement(id);
        if (e.nbBiens() > 0) {
            throw new TransitionInvalideException("L'emplacement « " + e.nom() + "» porte " + e.nbBiens()
                + " bien(s) : réaffectez-les ou désactivez-le.");
        }
        jdbc.update("delete from emplacements where id = ?", id);
        AuditService.detailler("Emplacement « " + e.nom() + " » supprimé.");
    }

    public record Import(int crees, int dejaLies) { }

    /**
     * Crée un emplacement pour chaque unité de l'hôtellerie qui n'en a pas encore : chambre (hôtel), unité de motel,
     * appartement ; nom « établissement · numéro ».
     */
    @PreAuthorize(InventairePatrimoineService.ECRITURE)
    @Transactional
    public Import importerUnitesHotellerie() {
        String auteur = currentUser.requireUser().getEmail();
        List<UniteHotellerie> unites = unitesHotellerie();
        int crees = 0;
        for (UniteHotellerie u : unites) {
            if (u.emplacementId() != null) continue;
            String type = switch (u.etablissementType() == null ? "" : u.etablissementType()) {
                case "MOTEL" -> "UNITE_MOTEL";
                case "APPARTEMENTS" -> "APPARTEMENT";
                default -> "CHAMBRE";
            };
            String nom = (u.etablissementNom() == null ? "" : u.etablissementNom() + " · ") + u.numero();
            if (!jdbc.queryForList("select 1 from emplacements where lower(nom) = lower(?)", Integer.class, nom).isEmpty()) {
                nom = nom + " (" + u.pmsId() + ")";
            }
            jdbc.update("insert into emplacements (nom, type, description, pms_unite_id, cree_par) values (?, ?, ?, ?, ?)",
                nom, type, u.typeNom(), u.pmsId(), auteur);
            crees++;
        }
        AuditService.detailler(crees + " emplacement(s) créé(s) depuis les unités de l'hôtellerie.");
        return new Import(crees, unites.size() - crees);
    }

    // ── Miroir des unités de l'hôtellerie (appel système signé) ───────────────────────────────────────────────

    /** Unité telle que l'hôtellerie la transmet (identifiant et numéro contrôlés dans {@link #recevoirUnites}). */
    public record UniteTransmise(Long id, String numero, String typeNom, String etablissementNom, String etablissementType,
                                 String ameublement, Boolean actif) { }

    /**
     * Liste complète des unités envoyée par l'hôtellerie : chaque unité est créée ou mise à jour ; une unité absente de
     * la liste est marquée inactive (ses emplacements et biens restent, rien n'est perdu).
     */
    @PreAuthorize("hasRole('PMS')")
    @Transactional
    public int recevoirUnites(List<UniteTransmise> unites) {
        jdbc.update("update hotellerie_unites set actif = false, maj_le = now()");
        int n = 0;
        for (UniteTransmise u : unites) {
            if (u.id() == null || u.numero() == null || u.numero().isBlank()) continue;
            jdbc.update("""
                insert into hotellerie_unites (pms_id, numero, type_nom, etablissement_nom, etablissement_type, ameublement, actif, maj_le)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (pms_id) do update set numero = excluded.numero, type_nom = excluded.type_nom,
                  etablissement_nom = excluded.etablissement_nom, etablissement_type = excluded.etablissement_type,
                  ameublement = excluded.ameublement, actif = excluded.actif, maj_le = now()
                """, u.id(), tronquer(u.numero(), 30), tronquer(u.typeNom(), 100), tronquer(u.etablissementNom(), 150),
                tronquer(u.etablissementType(), 20), tronquer(u.ameublement(), 12), u.actif() == null || u.actif());
            n++;
        }
        return n;
    }

    public record BienUnite(Long id, String code, String libelle, String categorie, String etat, String statut, int nbPhotos) { }

    /** Biens affectés à l'emplacement d'une unité de l'hôtellerie (inventaire type de l'appartement, de la chambre). */
    @PreAuthorize("hasAnyRole('RECEPTIONNISTE', 'DIR_HEBERGEMENT', 'GOUVERNANTE', 'TECHNICIEN', 'DG', 'DFIN', 'DA', 'COMPTABLE', 'ADMIN', 'LOGISTIQUE', 'GEST_PATRIMOINE')")
    @Transactional(readOnly = true)
    public List<BienUnite> biensDeLUnite(Long pmsUniteId) {
        return jdbc.query("""
            select b.id, b.code, b.libelle, b.categorie, b.etat, b.statut,
                   (select count(*) from inventaire_photos p where p.bien_id = b.id) as nb_photos
            from inventaire_biens b join emplacements e on e.id = b.emplacement_id where e.pms_unite_id = ? order by b.libelle, b.code
            """, (rs, i) -> new BienUnite(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getInt(7)), pmsUniteId);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String tronquer(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
