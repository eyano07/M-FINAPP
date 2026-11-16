package com.mbsc.finapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Conversion d'un classeur Excel en PDF par LibreOffice (demande du 15/11/2026 : le bouton PDF des états
 * financiers rend TOUTES les feuilles du classeur — page de garde, fiches, notes, graphiques).
 *
 * <p>LibreOffice est lancé en ligne de commande, sans interface, dans un dossier temporaire propre à chaque
 * conversion (profil utilisateur compris) puis effacé. Une seule conversion à la fois : LibreOffice est lourd
 * et la machine de l'entreprise ne doit pas s'effondrer si deux personnes cliquent ensemble.</p>
 */
@Service
public class ConversionPdfService {

    private static final Logger log = LoggerFactory.getLogger(ConversionPdfService.class);

    private final Semaphore uneALaFois = new Semaphore(1);
    private final String soffice;
    private final long delaiSecondes;

    public ConversionPdfService(@Value("${app.pdf.soffice:soffice}") String soffice,
                                @Value("${app.pdf.delai-secondes:240}") long delaiSecondes) {
        this.soffice = soffice;
        this.delaiSecondes = delaiSecondes;
    }

    public byte[] classeurEnPdf(byte[] xlsx) {
        boolean obtenu = false;
        Path dossier = null;
        try {
            obtenu = uneALaFois.tryAcquire(delaiSecondes, TimeUnit.SECONDS);
            if (!obtenu) {
                throw indisponible("Une autre conversion PDF est en cours, réessayez dans un instant.");
            }
            dossier = Files.createTempDirectory("conversion-pdf-");
            Path entree = dossier.resolve("classeur.xlsx");
            Files.write(entree, xlsx);
            Process p = new ProcessBuilder(List.of(soffice,
                "-env:UserInstallation=" + dossier.resolve("profil").toUri(),
                "--headless", "--norestore", "--nologo", "--nodefault",
                "--convert-to", "pdf", "--outdir", dossier.toString(), entree.toString()))
                .redirectErrorStream(true)
                .redirectOutput(dossier.resolve("soffice.log").toFile())
                .start();
            if (!p.waitFor(delaiSecondes, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw indisponible("La conversion PDF a pris trop de temps.");
            }
            Path sortie = dossier.resolve("classeur.pdf");
            if (p.exitValue() != 0 || !Files.exists(sortie)) {
                log.warn("Conversion PDF en échec (code {}) : {}", p.exitValue(),
                    Files.readString(dossier.resolve("soffice.log"), StandardCharsets.UTF_8).strip());
                throw indisponible("La conversion PDF a échoué.");
            }
            return Files.readAllBytes(sortie);
        } catch (IOException e) {
            // Le plus souvent : LibreOffice absent de la machine (lancement hors de l'image Docker).
            log.warn("Conversion PDF impossible : {}", e.getMessage());
            throw indisponible("La conversion PDF n'est pas disponible sur ce serveur.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw indisponible("La conversion PDF a été interrompue.");
        } finally {
            if (obtenu) {
                uneALaFois.release();
            }
            effacer(dossier);
        }
    }

    private static ResponseStatusException indisponible(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private static void effacer(Path dossier) {
        if (dossier == null) {
            return;
        }
        try (Stream<Path> chemins = Files.walk(dossier)) {
            chemins.sorted(Comparator.reverseOrder()).forEach(c -> c.toFile().delete());
        } catch (IOException e) {
            log.debug("Dossier temporaire non effacé {} : {}", dossier, e.getMessage());
        }
    }
}
