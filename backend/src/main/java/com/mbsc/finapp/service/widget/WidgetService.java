package com.mbsc.finapp.service.widget;

import com.mbsc.finapp.domain.JetonWidget;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.SensTransaction;
import com.mbsc.finapp.domain.enums.StatutNote;
import com.mbsc.finapp.dto.caisse.LigneBalanceResponse;
import com.mbsc.finapp.dto.notes.NoteFraisResponse;
import com.mbsc.finapp.dto.widget.WidgetResume;
import com.mbsc.finapp.repository.JetonWidgetRepository;
import com.mbsc.finapp.repository.NoteFraisRepository;
import com.mbsc.finapp.repository.EcritureGrandLivreRepository;
import com.mbsc.finapp.repository.PreferenceUtilisateurRepository;
import com.mbsc.finapp.security.CurrentUserProvider;
import com.mbsc.finapp.service.CaisseService;
import com.mbsc.finapp.service.NoteFraisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Widgets de l'écran d'accueil Android (18/11/2026) : jeton propre au téléphone et résumé du compte.
 * <p>
 * Le résumé suit les droits du compte comme le site : les notes viennent de {@link NoteFraisService#lister} (même règle
 * de visibilité), la trésorerie de {@link CaisseService#soldesTresorerie()} pour les rôles {@link #ROLES_TRESORERIE},
 * seulement si le compte a activé le widget Trésorerie dans « Mon profil » (préférence {@value #PREFERENCE}).
 */
@Service
public class WidgetService {

    private static final Logger log = LoggerFactory.getLogger(WidgetService.class);
    private static final SecureRandom HASARD = new SecureRandom();
    /** Lignes de notes montrées par le widget (les plus récentes, celles à traiter d'abord). */
    static final int LIGNES_MAX = 6;
    /** Rôles qui ont le widget Trésorerie (mini tableau de bord) : caissier, DA, DFIN, comptable, DG, administrateur. */
    public static final Set<String> ROLES_TRESORERIE = Set.of("CAISSIER", "DA", "DFIN", "COMPTABLE", "DG", "ADMIN");
    /** Rôles qui voient en plus le résultat net de l'exercice sur le widget : DFIN, DA, administrateur. */
    public static final Set<String> ROLES_RESULTAT = Set.of("DFIN", "DA", "ADMIN");
    /** Préférence du compte (ProfilService) : ACTIF pour afficher la trésorerie sur son téléphone. */
    public static final String PREFERENCE = "widget.tresorerie";
    private static final Set<StatutNote> TERMINEES = EnumSet.of(StatutNote.PAYEE, StatutNote.ANNULEE);

    private final JetonWidgetRepository jetons;
    private final NoteFraisService notes;
    private final NoteFraisRepository notesRepository;
    private final CaisseService caisse;
    private final EcritureGrandLivreRepository ecritures;
    private final PreferenceUtilisateurRepository preferences;
    private final CurrentUserProvider currentUser;
    private final com.mbsc.finapp.repository.NotificationRepository notifications;

    public WidgetService(JetonWidgetRepository jetons, NoteFraisService notes, NoteFraisRepository notesRepository,
                         CaisseService caisse, EcritureGrandLivreRepository ecritures,
                         PreferenceUtilisateurRepository preferences, CurrentUserProvider currentUser,
                         com.mbsc.finapp.repository.NotificationRepository notifications) {
        this.notifications = notifications;
        this.jetons = jetons;
        this.notes = notes;
        this.notesRepository = notesRepository;
        this.caisse = caisse;
        this.ecritures = ecritures;
        this.preferences = preferences;
        this.currentUser = currentUser;
    }

    // ── Jeton du téléphone ────────────────────────────────────────────────────────

    /** Nouveau jeton pour le compte connecté (l'application le garde pour ses widgets) ; rendu une seule fois. */
    @Transactional
    public String emettre(String ancien) {
        if (ancien != null && !ancien.isBlank()) jetons.supprimerEmpreinte(empreinte(ancien));
        byte[] octets = new byte[32];
        HASARD.nextBytes(octets);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        jetons.save(JetonWidget.builder().utilisateur(currentUser.requireUser()).empreinte(empreinte(jeton)).build());
        return jeton;
    }

    /** Déconnexion dans l'application : le widget de ce téléphone ne lit plus rien. */
    @Transactional
    public void retirer(String jeton) {
        if (jeton != null && !jeton.isBlank()) jetons.supprimerEmpreinte(empreinte(jeton));
    }

    /** Compte d'un jeton présenté par un widget (filtre {@code WidgetJetonFilter}). */
    @Transactional
    public Optional<User> utilisateurDe(String jeton) {
        if (jeton == null || jeton.isBlank() || jeton.length() > 200) return Optional.empty();
        return jetons.findByEmpreinte(empreinte(jeton)).map(j -> {
            j.setDateVue(Instant.now());
            User u = j.getUtilisateur();
            org.hibernate.Hibernate.initialize(u.getRoles());   // rôles lus hors de la transaction par le filtre
            return u;
        });
    }

    static String empreinte(String jeton) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(jeton.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Abonnés au widget Trésorerie ──────────────────────────────────────────────

    /** Comptes à prévenir d'un mouvement de trésorerie : widget activé et rôle qui y a droit. */
    @Transactional(readOnly = true)
    public List<Long> abonnesTresorerie() {
        return preferences.findByCleAndValeur(PREFERENCE, "ACTIF").stream()
            .map(p -> p.getUser())
            .filter(u -> u.isActif() && u.getRoles().stream().anyMatch(r -> ROLES_TRESORERIE.contains(r.getNom().name())))
            .map(User::getId)
            .distinct()
            .toList();
    }

    // ── Résumé ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WidgetResume resume() {
        User moi = currentUser.requireUser();
        Set<String> roles = new java.util.HashSet<>();
        moi.getRoles().forEach(r -> roles.add(r.getNom().name()));

        // Notes à traiter : celles que le compte voit et dont l'étape lui revient.
        Map<Long, WidgetResume.Ligne> lignes = new LinkedHashMap<>();
        List<NoteFraisResponse> aTraiter = new ArrayList<>();
        for (NoteFraisResponse n : notes.lister(null, null, null, null, null, null, null)) {
            String action = actionPour(n.statut(), n.sens(), roles);
            if (action != null) {
                aTraiter.add(n);
                lignes.putIfAbsent(n.id(), ligne(n, action, true));
            }
        }
        // Ses propres notes encore en circuit.
        List<NoteFraisResponse> miennes = notesRepository.findByCreateurId(moi.getId()).stream()
            .filter(n -> !TERMINEES.contains(n.getStatut()))
            .map(NoteFraisResponse::from)
            .sorted(Comparator.comparing(NoteFraisResponse::dateMaj, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        int rejetees = (int) miennes.stream().filter(n -> n.statut() == StatutNote.REJETEE_DA).count();

        List<WidgetResume.Ligne> affichees = new ArrayList<>(lignes.values().stream()
            .sorted(Comparator.comparing(WidgetResume.Ligne::id, Comparator.reverseOrder()))
            .limit(LIGNES_MAX).toList());
        for (NoteFraisResponse n : miennes) {
            if (affichees.size() >= LIGNES_MAX) break;
            if (!lignes.containsKey(n.id())) affichees.add(ligne(n, etatDeMaNote(n.statut()), false));
        }

        WidgetResume.Tresorerie tresorerie = null;
        String message = null;
        if (roles.stream().noneMatch(ROLES_TRESORERIE::contains)) {
            message = "Le widget Trésorerie est réservé au caissier, au DA, au DFIN, au comptable, au DG et à l'administrateur.";
        } else if (!preferences.findByUserIdAndCle(moi.getId(), PREFERENCE)
                .map(p -> "ACTIF".equals(p.getValeur())).orElse(false)) {
            message = "Activez le widget Trésorerie dans l'application : Mon profil › Widget Trésorerie.";
        } else {
            tresorerie = tresorerie(caisse.soldesTresorerie(), ecritures.mouvementsTresorerieDu(java.time.LocalDate.now()));
            if (roles.stream().anyMatch(ROLES_RESULTAT::contains)) {
                java.time.LocalDate aujourdhui = java.time.LocalDate.now(com.mbsc.finapp.config.FuseauEntreprise.ZONE);
                java.time.LocalDate debut = aujourdhui.withDayOfYear(1);
                tresorerie = tresorerie.avecResultat(resultatNet(ecritures.mouvementsParCompte(debut, aujourdhui)),
                    "Résultat net " + aujourdhui.getYear());
            }
        }

        String nom = ((moi.getPrenom() == null ? "" : moi.getPrenom() + " ") + (moi.getNom() == null ? "" : moi.getNom())).trim();
        return new WidgetResume(nom.isEmpty() ? moi.getEmail() : nom, Instant.now(),
            new WidgetResume.Notes(aTraiter.size(), miennes.size(), rejetees, affichees), tresorerie, message,
            notifications.countByDestinataireIdAndLueFalse(moi.getId()));
    }

    /**
     * Étape du circuit qui revient au compte, d'après ses rôles (mêmes contrôles que NoteFraisService et
     * CaisseService) ; null si la note n'attend rien de lui.
     */
    static String actionPour(StatutNote statut, SensTransaction sens, Set<String> roles) {
        if (statut == null) return null;
        return switch (statut) {
            case SOUMISE -> roles.contains("DFIN") ? "À vérifier" : null;
            case VERIFIEE_DFIN -> roles.contains("DA") ? "À valider" : null;
            case VALIDEE_DA -> roles.contains("DFIN") ? "À transmettre à la caisse" : null;
            case REJETEE_DA -> roles.contains("DFIN") ? "Rejetée par le DA" : null;
            case TRANSMISE_CAISSE -> roles.contains("CAISSIER")
                ? (sens == SensTransaction.ENCAISSEMENT ? "À encaisser" : "À payer") : null;
            default -> null;
        };
    }

    /** Où en est une note créée par le compte. */
    static String etatDeMaNote(StatutNote statut) {
        return switch (statut) {
            case BROUILLON -> "Brouillon, à soumettre";
            case SOUMISE -> "Chez le DFIN";
            case VERIFIEE_DFIN -> "Chez le DA";
            case VALIDEE_DA -> "Validée, vers la caisse";
            case REJETEE_DA -> "Rejetée";
            case TRANSMISE_CAISSE -> "À la caisse";
            default -> statut.name();
        };
    }

    private static WidgetResume.Ligne ligne(NoteFraisResponse n, String etat, boolean aTraiter) {
        return new WidgetResume.Ligne(n.id(), n.reference(), n.objet(), n.montant(),
            n.devise() == null ? null : n.devise().name(), etat, aTraiter, n.statut() == null ? null : n.statut().name());
    }

    /**
     * Résultat net de la période, comme le compte de résultat du site (ComptabiliteService#compteResultat) : produits
     * (solde créditeur des comptes PRODUIT) moins charges (solde débiteur des comptes CHARGE). Lignes
     * [numero, libelle, type, classe, debit, credit].
     */
    static BigDecimal resultatNet(List<Object[]> mouvements) {
        BigDecimal resultat = BigDecimal.ZERO;
        for (Object[] m : mouvements) {
            BigDecimal debit = m[4] == null ? BigDecimal.ZERO : (BigDecimal) m[4];
            BigDecimal credit = m[5] == null ? BigDecimal.ZERO : (BigDecimal) m[5];
            if (m[2] == com.mbsc.finapp.domain.enums.TypeCompte.PRODUIT) resultat = resultat.add(credit.subtract(debit));
            else if (m[2] == com.mbsc.finapp.domain.enums.TypeCompte.CHARGE) resultat = resultat.subtract(debit.subtract(credit));
        }
        return resultat;
    }

    /**
     * Caisse 571, banques 521x, mobile money 552x (comme le tableau de bord), et les mouvements du jour sur la classe 5
     * ([numero, debit = entrées, credit = sorties]).
     */
    static WidgetResume.Tresorerie tresorerie(List<LigneBalanceResponse> soldes, List<Object[]> mouvementsDuJour) {
        BigDecimal caisse = BigDecimal.ZERO, banque = BigDecimal.ZERO, mobile = BigDecimal.ZERO;
        boolean aBanque = false, aMobile = false;
        for (LigneBalanceResponse l : soldes) {
            String c = l.compteNumero();
            BigDecimal s = l.solde() == null ? BigDecimal.ZERO : l.solde();
            if (c == null) continue;
            if (c.startsWith("571")) caisse = caisse.add(s);
            else if (c.startsWith("521")) { banque = banque.add(s); aBanque = true; }
            else if (c.startsWith("552")) { mobile = mobile.add(s); aMobile = true; }
        }
        List<WidgetResume.Canal> canaux = new ArrayList<>();
        canaux.add(new WidgetResume.Canal("Caisse", caisse));
        if (aBanque) canaux.add(new WidgetResume.Canal("Banque", banque));
        if (aMobile) canaux.add(new WidgetResume.Canal("Mobile money", mobile));
        BigDecimal entrees = BigDecimal.ZERO, sorties = BigDecimal.ZERO;
        for (Object[] m : mouvementsDuJour) {
            String c = (String) m[0];
            // Virements internes entre deux comptes de trésorerie : comptés des deux côtés, comme sur le journal.
            if (c == null || !(c.startsWith("571") || c.startsWith("521") || c.startsWith("552"))) continue;
            entrees = entrees.add((BigDecimal) m[1]);
            sorties = sorties.add((BigDecimal) m[2]);
        }
        return new WidgetResume.Tresorerie("USD", caisse.add(banque).add(mobile), canaux, entrees, sorties);
    }
}
