package com.mbsc.finapp.domain;

import com.mbsc.finapp.domain.enums.StatutCamionMinerai;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un chargement de minerais, identifie par sa plaque et son jour de reception.
 *
 * <p>Les marchandises ordinaires se suivent en quantite : dix sacs de ciment
 * sont interchangeables. Un minerais, non — chaque camion se revend a son
 * propre prix selon sa teneur et le cours du jour, alors qu'ils partagent
 * tous le meme prix d'achat et les memes comptes d'imputation. D'ou ce suivi
 * unitaire, qui ne remplace pas le stock mais s'y superpose : un camion vaut
 * une unite de l'article ({@code minerais = true}).</p>
 *
 * <p><b>Trois cycles independants :</b></p>
 * <ul>
 *   <li><b>Reception</b> (logistique) : cree le camion {@link
 *       com.mbsc.finapp.domain.enums.StatutCamionMinerai#A_VALIDER}, sans
 *       aucune ecriture ni entree en stock. La logistique constate l'arrivee
 *       physique, pas l'achat. Voir {@code MineraiService.receptionner}.</li>
 *   <li><b>Validation de l'achat</b> (caisse) : D 601x Achats / C 4011
 *       Fournisseurs puis D 311x Stock / C 6031 Variation — c'est cette
 *       etape, et elle seule, qui fait naitre la dette fournisseur et entrer
 *       la marchandise en stock. Voir {@code MineraiService.validerAchat}.</li>
 *   <li><b>Reglement</b> (caisse) : D 4011 Fournisseurs / C 571 Caisse, qui
 *       solde la dette. Independant de la vente : un camion peut etre vendu
 *       avant d'etre paye, ou l'inverse — d'ou {@link #regle} distinct de
 *       {@link #statut}.</li>
 * </ul>
 */
@Entity
@Table(name = "camions_minerai")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CamionMinerai {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entrepot_id", nullable = false)
    private Entrepot entrepot;

    /** Plaque d'immatriculation du camion, telle que relevee a la reception. */
    @Column(nullable = false, length = 40)
    private String plaque;

    /**
     * Jour de reception du chargement, constate par la logistique. C'est
     * aussi cette date, et non celle de la validation par le caissier, qui
     * datera l'ecriture d'achat une fois validee — meme convention que
     * VenteService (date de la vente, pas de sa validation).
     */
    @Column(name = "date_reception", nullable = false)
    private LocalDate dateReception;

    /** Prix HORS TAXES propose par la logistique. Devient le prix d'achat effectif a la validation. */
    @Column(name = "prix_achat", nullable = false, precision = 15, scale = 2)
    private BigDecimal prixAchat;

    /**
     * TVA recuperable (4452) sur ce chargement, nulle si le minerais n'y est
     * pas soumis. Exclue du cout d'acquisition — c'est une creance sur l'Etat
     * — mais incluse dans la dette fournisseur, que la caisse solde en TTC.
     */
    @Column(name = "montant_tva", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal montantTva = BigDecimal.ZERO;

    /** Dette envers le fournisseur : prix hors taxes plus TVA. */
    public BigDecimal detteFournisseur() {
        return prixAchat.add(montantTva == null ? BigDecimal.ZERO : montantTva);
    }

    /**
     * Cout d'acquisition = {@link #prixAchat} + frais accessoires incorpores
     * ({@link ChargeCamionMinerai}). C'est ce montant, et non le cout moyen
     * pondere de l'article, qui sort du stock a la vente : deux chargements
     * n'ont ni la meme teneur ni les memes frais de route, le SYSCOHADA admet
     * donc ici l'identification specifique.
     */
    @Column(name = "cout_acquisition", nullable = false, precision = 15, scale = 2)
    private BigDecimal coutAcquisition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutCamionMinerai statut = StatutCamionMinerai.A_VALIDER;

    /** true une fois la dette fournisseur soldee par la caisse. */
    @Column(nullable = false)
    @Builder.Default
    private boolean regle = false;

    /** Mouvement d'entree en stock genere a la reception. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mouvement_id")
    private MouvementStock mouvement;

    /** Piece d'achat (D 601 / C 4011) generee a la reception. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "piece_reception_id")
    private PieceComptable pieceReception;

    /** Decaissement de caisse qui a solde la dette fournisseur. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_reglement_id")
    private TransactionCaisse transactionReglement;

    /**
     * Note de frais en cours demandant le reglement de la dette de ce camion
     * via le circuit DFIN/DA/Tresorerie — distinct du reglement direct en
     * caisse ({@code CaisseService.reglerCamionsMinerai}). Nul tant
     * qu'aucune note n'a ete creee, remis a nul si elle est annulee (voir
     * {@code NoteFraisService.annuler}), et {@link #regle} passe a true
     * quand elle est payee (voir {@code MineraiService
     * .marquerRegleParNoteInterne}). Empeche qu'un meme camion soit inclus
     * dans deux notes a la fois.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_frais_reglement_id")
    private NoteFrais noteFraisReglement;

    @CreationTimestamp
    @Column(name = "date_creation", updatable = false)
    private Instant dateCreation;

    @UpdateTimestamp
    @Column(name = "date_maj")
    private Instant dateMaj;

    /** "AB 1234 CD — 05/09/2026", pour les libelles d'ecriture et les listes. */
    public String designation() {
        return plaque + " — " + dateReception;
    }
}
