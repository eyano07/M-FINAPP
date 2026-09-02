package com.mbsc.finapp.config;

import com.mbsc.finapp.domain.Role;
import com.mbsc.finapp.domain.User;
import com.mbsc.finapp.domain.enums.RoleType;
import com.mbsc.finapp.repository.RoleRepository;
import com.mbsc.finapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Cree les comptes par defaut (un par role) si la base est vide,
 * puis insere les donnees de demonstration (idempotent).
 *
 * <p>Les donnees de demo dependent des users, d'ou leur insertion ici
 * (apres que les users existent) plutot que dans un script Flyway qui
 * s'execute avant le demarrage de l'application.</p>
 *
 * <p>Desactivable via {@code APP_SEED_ENABLED=false} en production.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private static final String DEFAULT_PASSWORD = "Mbsc@2026";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Seeder desactive (app.seed.enabled=false)");
            return;
        }
        log.warn("=== Seeder actif : comptes de demonstration. Definissez APP_SEED_ENABLED=false en production. ===");

        // 1. Creer les utilisateurs
        seedUser("admin@mbsc.cd",     "Admin",     "Systeme",       RoleType.ADMIN);
        seedUser("dg@mbsc.cd",        "Directeur", "General",       RoleType.DG);
        seedUser("da@mbsc.cd",        "Directeur", "Administratif", RoleType.DA);
        seedUser("dfin@mbsc.cd",      "Directeur", "Financier",     RoleType.DFIN);
        seedUser("directeur@mbsc.cd", "Directeur", "Metier",        RoleType.DIRECTEUR);
        seedUser("caissier@mbsc.cd",  "Agent",     "Caisse",        RoleType.CAISSIER);
        seedUser("logistique@mbsc.cd","Agent",     "Logistique",     RoleType.LOGISTIQUE);

        // 2. Inserer les donnees de demo (idempotent)
        seedDemoData();
    }

    // -------------------------------------------------------------------------

    private void seedUser(String email, String nom, String prenom, RoleType roleType) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        Role role = roleRepository.findByNom(roleType)
            .orElseThrow(() -> new IllegalStateException("Role manquant: " + roleType));

        userRepository.save(User.builder()
            .email(email)
            .motDePasse(passwordEncoder.encode(DEFAULT_PASSWORD))
            .nom(nom)
            .prenom(prenom)
            .actif(true)
            .roles(Set.of(role))
            .build());
        log.info("Compte cree: {} [{}]", email, roleType);
    }

    /**
     * Insere les donnees de demonstration en se basant sur les IDs des users
     * et des comptes OHADA via des sous-requetes (idempotent grace aux ON CONFLICT).
     */
    private void seedDemoData() {
        // Taux de change
        jdbc.update("""
            INSERT INTO taux_change (taux, date_effet, note)
            VALUES (2840.00, CURRENT_DATE, 'Taux initial de demonstration')
            ON CONFLICT DO NOTHING
        """);

        // Notes de frais (l'imputation comptable se fait desormais ligne par
        // ligne dans lignes_note_frais, plus au niveau de l'entete).
        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, priorite, date_creation, date_maj)
            SELECT 'NF-2026-000001', 'Achat fournitures bureau',
                   'Stylos, ramettes papier A4 et enveloppes pour le departement administratif.',
                   45000.00, 'CDF', 'PAYEE',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')), 'HAUTE',
                   NOW() - INTERVAL '12 days', NOW() - INTERVAL '2 days'
            FROM users u
            WHERE u.email = 'directeur@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, priorite, date_creation, date_maj)
            SELECT 'NF-2026-000002', 'Transport mission terrain Kinshasa',
                   'Frais de deplacement pour supervision des chantiers — 3 jours.',
                   120000.00, 'CDF', 'TRANSMISE_CAISSE',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')), 'HAUTE',
                   NOW() - INTERVAL '7 days', NOW() - INTERVAL '1 day'
            FROM users u
            WHERE u.email = 'directeur@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, priorite, date_creation, date_maj)
            SELECT 'NF-2026-000003', 'Abonnement logiciels SaaS',
                   'Renouvellement annuel Microsoft 365 et Zoom — 5 licences.',
                   284000.00, 'CDF', 'TRANSMISE_CAISSE',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')), 'MOYENNE',
                   NOW() - INTERVAL '5 days', NOW() - INTERVAL '12 hours'
            FROM users u
            WHERE u.email = 'dfin@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, date_creation, date_maj)
            SELECT 'NF-2026-000004', 'Reparation vehicule de service',
                   'Remplacement plaquettes de frein et vidange — 4x4 terrain.',
                   67500.00, 'CDF', 'VERIFIEE_DFIN',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')),
                   NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day'
            FROM users u
            WHERE u.email = 'directeur@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, date_creation, date_maj)
            SELECT 'NF-2026-000005', 'Frais de reception clients',
                   'Dejeuner de travail avec partenaires — 8 personnes.',
                   52000.00, 'CDF', 'SOUMISE',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')),
                   NOW() - INTERVAL '1 day', NOW() - INTERVAL '10 hours'
            FROM users u
            WHERE u.email = 'directeur@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, date_creation, date_maj)
            SELECT 'NF-2026-000006', 'Formation Excel avance',
                   'Session pour 3 agents comptables, 2 jours.',
                   38000.00, 'CDF', 'BROUILLON',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')),
                   NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'
            FROM users u
            WHERE u.email = 'dfin@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO notes_frais
                (reference, objet, description, montant, devise, statut,
                 createur_id, beneficiaire, date_creation, date_maj)
            SELECT 'NF-2026-000007', 'Achat mobilier de bureau',
                   'Chaises ergonomiques — 10 unites open space.',
                   230000.00, 'CDF', 'REJETEE_DA',
                   u.id, TRIM(COALESCE(u.prenom, '') || ' ' || COALESCE(u.nom, '')),
                   NOW() - INTERVAL '14 days', NOW() - INTERVAL '9 days'
            FROM users u
            WHERE u.email = 'directeur@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        // Lignes de depense (une par note de demo, montant = montant de l'entete)
        seedLigneNote("NF-2026-000001", "6047", "Stylos, ramettes papier A4 et enveloppes");
        seedLigneNote("NF-2026-000002", "6181", "Deplacement supervision chantiers — 3 jours");
        seedLigneNote("NF-2026-000003", "6281", "Microsoft 365 et Zoom — 5 licences");
        seedLigneNote("NF-2026-000004", "6241", "Plaquettes de frein et vidange");
        seedLigneNote("NF-2026-000005", "6588", "Dejeuner de travail avec partenaires");
        seedLigneNote("NF-2026-000006", null, "Formation Excel avance — 3 agents");
        seedLigneNote("NF-2026-000007", "6047", "Chaises ergonomiques — 10 unites");

        // Observations
        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Soumis pour validation', NOW() - INTERVAL '11 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000001' AND u.email = 'directeur@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);
        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'VERIFIEE_DFIN', 'Montant verifie, conforme', NOW() - INTERVAL '8 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000001' AND u.email = 'dfin@mbsc.cd'
              AND (SELECT COUNT(*) FROM observations_note WHERE note_id = nf.id) = 1
        """);
        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'VALIDEE_DA', 'Valide, priorite haute urgente.', NOW() - INTERVAL '6 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000001' AND u.email = 'da@mbsc.cd'
              AND (SELECT COUNT(*) FROM observations_note WHERE note_id = nf.id) = 2
        """);
        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'PAYEE', 'Paiement effectue en caisse.', NOW() - INTERVAL '2 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000001' AND u.email = 'caissier@mbsc.cd'
              AND (SELECT COUNT(*) FROM observations_note WHERE note_id = nf.id) = 3
        """);

        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Deplacement urgent approuve DG.', NOW() - INTERVAL '6 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000002' AND u.email = 'directeur@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);

        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Besoin metier confirme.', NOW() - INTERVAL '4 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000003' AND u.email = 'dfin@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);

        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Vehicule immobilise.', NOW() - INTERVAL '2 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000004' AND u.email = 'directeur@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);
        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'VERIFIEE_DFIN', 'Devis verifie conforme.', NOW() - INTERVAL '1 day'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000004' AND u.email = 'dfin@mbsc.cd'
              AND (SELECT COUNT(*) FROM observations_note WHERE note_id = nf.id) = 1
        """);

        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Reception importante pour le contrat.', NOW() - INTERVAL '10 hours'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000005' AND u.email = 'directeur@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);

        jdbc.update("""
            INSERT INTO observations_note (note_id, auteur_id, statut_au_moment, commentaire, date_action)
            SELECT nf.id, u.id, 'SOUMISE', 'Urgence, mobilier vetuste.', NOW() - INTERVAL '13 days'
            FROM notes_frais nf, users u
            WHERE nf.reference = 'NF-2026-000007' AND u.email = 'directeur@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM observations_note WHERE note_id = nf.id)
        """);

        // Avancer les sequences
        jdbc.execute("SELECT setval('seq_note_frais', GREATEST(nextval('seq_note_frais') + 3, 10))");

        // Transactions de caisse
        jdbc.update("""
            INSERT INTO transactions_caisse
                (uuid, reference, note_id, montant, sens,
                 caissier_id, numero_recu, libelle, date_operation, date_enregistrement)
            SELECT gen_random_uuid(), 'TRX-2026-000001', NULL, 500000.00, 'ENCAISSEMENT',
                   u.id, 'RECU-2026-000001', 'Dotation initiale de caisse — Ordre DG',
                   NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days'
            FROM users u WHERE u.email = 'caissier@mbsc.cd'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.update("""
            INSERT INTO transactions_caisse
                (uuid, reference, note_id, montant, sens,
                 caissier_id, numero_recu, libelle, date_operation, date_enregistrement)
            SELECT gen_random_uuid(), 'TRX-2026-000002', nf.id, 45000.00, 'DECAISSEMENT',
                   u.id, 'RECU-2026-000002', 'Paiement NF-2026-000001 — Fournitures bureau',
                   NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days'
            FROM users u, notes_frais nf
            WHERE u.email = 'caissier@mbsc.cd' AND nf.reference = 'NF-2026-000001'
            ON CONFLICT (reference) DO NOTHING
        """);

        jdbc.execute("SELECT setval('seq_transaction_caisse', GREATEST(nextval('seq_transaction_caisse') + 1, 5))");
        jdbc.execute("SELECT setval('seq_recu_caisse', GREATEST(nextval('seq_recu_caisse') + 1, 5))");

        // Grand livre (seulement si les transactions existent et gl vide pour elles)
        jdbc.update("""
            INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture)
            SELECT tx.id, c.id, 500000.00, 0.00, 'Dotation initiale caisse', NOW() - INTERVAL '15 days'
            FROM transactions_caisse tx, comptes_ohada c
            WHERE tx.reference = 'TRX-2026-000001' AND c.numero = '571'
              AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.transaction_id = tx.id AND g.compte_id = c.id)
        """);
        jdbc.update("""
            INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture)
            SELECT tx.id, c.id, 0.00, 500000.00, 'Dotation initiale caisse', NOW() - INTERVAL '15 days'
            FROM transactions_caisse tx, comptes_ohada c
            WHERE tx.reference = 'TRX-2026-000001' AND c.numero = '7588'
              AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.transaction_id = tx.id AND g.compte_id = c.id)
        """);

        jdbc.update("""
            INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture)
            SELECT tx.id, c.id, 45000.00, 0.00, 'Fournitures bureau NF-2026-000001', NOW() - INTERVAL '2 days'
            FROM transactions_caisse tx, comptes_ohada c
            WHERE tx.reference = 'TRX-2026-000002' AND c.numero = '6011'
              AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.transaction_id = tx.id AND g.compte_id = c.id)
        """);
        jdbc.update("""
            INSERT INTO grand_livre (transaction_id, compte_id, debit, credit, libelle, date_ecriture)
            SELECT tx.id, c.id, 0.00, 45000.00, 'Fournitures bureau NF-2026-000001', NOW() - INTERVAL '2 days'
            FROM transactions_caisse tx, comptes_ohada c
            WHERE tx.reference = 'TRX-2026-000002' AND c.numero = '571'
              AND NOT EXISTS (SELECT 1 FROM grand_livre g WHERE g.transaction_id = tx.id AND g.compte_id = c.id)
        """);

        // Budgets
        jdbc.update("""
            INSERT INTO budgets (intitule, exercice, statut, elabore_par_id, approuve_par_id,
                observation, date_creation)
            SELECT 'Budget Exploitation Q2 2026', 2026, 'EN_EXECUTION',
                   dfin.id, da.id,
                   'Approuve en reunion du 01/04/2026. Couvre charges courantes et deplacements.',
                   NOW() - INTERVAL '30 days'
            FROM users dfin, users da
            WHERE dfin.email = 'dfin@mbsc.cd' AND da.email = 'da@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM budgets WHERE intitule = 'Budget Exploitation Q2 2026')
        """);

        jdbc.update("""
            INSERT INTO lignes_budget (budget_id, compte_id, montant_prevu, montant_realise)
            SELECT b.id, c.id,
                CASE c.numero
                    WHEN '6011' THEN 300000.00
                    WHEN '6181' THEN 400000.00
                    WHEN '6221' THEN 500000.00
                    WHEN '6281' THEN 350000.00
                    WHEN '6241' THEN 200000.00
                    WHEN '6588' THEN 250000.00
                END,
                CASE c.numero
                    WHEN '6011' THEN 45000.00
                    WHEN '6181' THEN 120000.00
                    WHEN '6221' THEN 0.00
                    WHEN '6281' THEN 284000.00
                    WHEN '6241' THEN 0.00
                    WHEN '6588' THEN 0.00
                END
            FROM budgets b, comptes_ohada c
            WHERE b.intitule = 'Budget Exploitation Q2 2026'
              AND c.numero IN ('6011','6181','6221','6281','6241','6588')
              AND NOT EXISTS (SELECT 1 FROM lignes_budget lb WHERE lb.budget_id = b.id AND lb.compte_id = c.id)
        """);

        jdbc.update("""
            INSERT INTO budgets (intitule, exercice, statut, elabore_par_id, approuve_par_id,
                observation, date_creation)
            SELECT 'Budget Formation & Developpement 2026', 2026, 'APPROUVE',
                   dfin.id, da.id,
                   'Renforcement de capacites : formations, seminaires, abonnements outils metier.',
                   NOW() - INTERVAL '20 days'
            FROM users dfin, users da
            WHERE dfin.email = 'dfin@mbsc.cd' AND da.email = 'da@mbsc.cd'
              AND NOT EXISTS (SELECT 1 FROM budgets WHERE intitule = 'Budget Formation & Developpement 2026')
        """);

        jdbc.execute("SELECT setval('seq_budget', GREATEST(nextval('seq_budget') + 1, 3))");

        log.info("Donnees de demonstration inserees.");
    }

    /**
     * Insere une ligne de depense unique reprenant le montant de l'entete,
     * imputee au compte donne (ou sans compte si {@code numeroCompte} est
     * {@code null} : fallback 6588 applique au paiement).
     */
    private void seedLigneNote(String reference, String numeroCompte, String description) {
        if (numeroCompte == null) {
            jdbc.update("""
                INSERT INTO lignes_note_frais (note_id, montant, compte_imputation_id, description, ordre)
                SELECT nf.id, nf.montant, NULL, ?, 1
                FROM notes_frais nf
                WHERE nf.reference = ?
                  AND NOT EXISTS (SELECT 1 FROM lignes_note_frais WHERE note_id = nf.id)
            """, description, reference);
        } else {
            jdbc.update("""
                INSERT INTO lignes_note_frais (note_id, montant, compte_imputation_id, description, ordre)
                SELECT nf.id, nf.montant, c.id, ?, 1
                FROM notes_frais nf, comptes_ohada c
                WHERE nf.reference = ? AND c.numero = ?
                  AND NOT EXISTS (SELECT 1 FROM lignes_note_frais WHERE note_id = nf.id)
            """, description, reference, numeroCompte);
        }
    }
}
