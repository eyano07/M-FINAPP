package com.mbsc.finapp.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Le journal d'audit est « la bible » du système, de sa création à aujourd'hui : il ne se supprime JAMAIS, survit aux redémarrages et aux
 * mises à jour, et vit dans le volume du backend. Ces garde-fous font échouer le build si l'une de ces règles est retirée :
 * rotation sans aucune limite de conservation, dossier du journal fixé et monté sur un volume nommé dans les configurations de déploiement.
 */
class JournalAuditPersistanceTest {

    private static String lire(String chemin) throws IOException {
        return Files.readString(Path.of(chemin));
    }

    @Test
    void laRotationDuJournalNeSupprimeJamaisRien() throws IOException {
        String logback = lire("src/main/resources/logback-spring.xml");
        // Les commentaires (qui expliquent la règle et citent ces mots) ne comptent pas : seules les balises actives.
        String actif = logback.replaceAll("(?s)<!--.*?-->", "");
        assertTrue(actif.contains("audit-%d{yyyy-MM-dd}.log.gz"), "les archives quotidiennes du journal d'audit doivent exister");
        assertFalse(actif.contains("<maxHistory>"), "maxHistory ferait supprimer les archives anciennes du journal d'audit");
        assertFalse(actif.contains("<totalSizeCap>"), "totalSizeCap ferait supprimer les archives du journal d'audit");
        assertFalse(actif.contains("<cleanHistoryOnStart>true"), "le journal d'audit ne se nettoie pas au démarrage");
    }

    @Test
    void lesConfigurationsDeDeploiementMontentLeJournalSurUnVolumeDuBackend() throws IOException {
        for (String fichier : new String[] { "../docker-compose.yml", "../docker-compose.prod.yml" }) {
            // Ces fichiers sont à la racine du dépôt : absents d'une copie du seul dossier backend (construction en conteneur).
            assumeTrue(Files.exists(Path.of(fichier)), fichier + " absent de cette copie");
            String compose = lire(fichier);
            assertTrue(compose.contains("APP_AUDIT_DIR: /app/data/logs"), fichier + " : le dossier du journal d'audit doit être fixé");
            assertTrue(compose.contains("backend_logs:/app/data/logs"), fichier + " : le journal d'audit doit être sur un volume nommé du backend");
            assertTrue(compose.matches("(?s).*\\nvolumes:\\n(?:[^\\n]*\\n)*?\\s+backend_logs:.*"), fichier + " : le volume backend_logs doit être déclaré");
        }
    }

    @Test
    void lesScriptsDeLivraisonNeSupprimentJamaisLeJournal() throws IOException {
        assumeTrue(Files.exists(Path.of("../deploiement/sauvegarder.sh")), "scripts de livraison absents de cette copie");
        for (String script : new String[] { "sauvegarder.sh", "mettre-a-jour.sh", "restaurer-base.sh", "installer.sh" }) {
            String texte = lire("../deploiement/" + script);
            assertFalse(texte.matches("(?s).*(volume\\s+rm|down\\s+(-v|--volumes)|volume\\s+prune|system\\s+prune).*"),
                script + " : ne doit jamais supprimer de volume (le journal d'audit y vit)");
            // Aucune commande de suppression (-delete, rm) ne vise les archives du journal d'audit.
            for (String ligne : texte.split("\n")) {
                boolean suppression = ligne.contains("-delete") || ligne.contains("rm -") || ligne.contains("rm \"");
                assertFalse(!ligne.trim().startsWith("#") && ligne.contains("journal-audit") && suppression,
                    script + " : les archives du journal d'audit ne doivent jamais être supprimées : " + ligne.trim());
            }
        }
        String sauvegarde = lire("../deploiement/sauvegarder.sh");
        assertTrue(sauvegarde.contains("journal-audit_"), "la sauvegarde doit archiver le journal d'audit");
        assertTrue(sauvegarde.contains("backend:/app/data/logs"), "la sauvegarde lit le journal dans le volume du backend");
        // La commande de suppression par ancienneté ne cible que ces deux motifs.
        assertTrue(sauvegarde.contains("-name 'base_*.dump' -o -name 'fichiers_*.tar.gz'"), "la rétention ne doit viser que la base et les fichiers");
        assertFalse(sauvegarde.contains("-name 'journal-audit"), "aucune règle de suppression ne doit viser le journal d'audit");
    }
}
