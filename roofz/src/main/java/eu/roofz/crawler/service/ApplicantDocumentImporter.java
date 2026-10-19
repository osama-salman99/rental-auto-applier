package eu.roofz.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.repository.ApplicantDocumentRepository;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot importer that seeds the {@link ApplicantDocument} store from a previous ReletPortal
 * application, so the applicant's already-uploaded documents can be reused without re-uploading them
 * by hand. Enabled by setting {@code documents.import.application-id} to a source application; it runs
 * only when the store is empty, so it is safe to leave configured (it won't re-import on later boots).
 *
 * <p>The portal has no per-file download, but {@code file/files/download?fileIds[]=…} returns a ZIP of
 * the requested files (entries named {@code {type}-{attachmentId}-{fileId8}.{ext}}). We map each entry
 * back to the application's attachment metadata (by the {@code fileId8} suffix) to recover the original
 * file name — and, for payslips / bank statements, the month embedded in it.
 */
@Component
public class ApplicantDocumentImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicantDocumentImporter.class);

    private static final Pattern DATE_IN_NAME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern ENTRY = Pattern.compile("^(?<type>[^-]+)-.*-(?<fid>[0-9a-fA-F]+)\\.[^.]+$");

    private final ReletPortalClient portal;
    private final ApplicantDocumentRepository repository;

    @Value("${documents.import.application-id:}")
    private String sourceApplicationId;

    public ApplicantDocumentImporter(ReletPortalClient portal, ApplicantDocumentRepository repository) {
        this.portal = portal;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (sourceApplicationId == null || sourceApplicationId.isBlank()) {
            return;
        }
        if (repository.count() > 0) {
            log.info("Document store already populated — skipping import from {}", sourceApplicationId);
            return;
        }
        if (!portal.isConfigured()) {
            log.warn("ReletPortal not configured — cannot import documents from {}", sourceApplicationId);
            return;
        }
        try {
            importFrom(sourceApplicationId.trim());
        } catch (Exception e) {
            log.error("Failed to import documents from application {}: {}", sourceApplicationId, e.getMessage(), e);
        }
    }

    private void importFrom(String applicationId) {
        JsonNode attachments =
                portal.getApplication(applicationId).path("data").path("attributes").path("attachments");
        if (!attachments.isArray() || attachments.isEmpty()) {
            log.warn("No attachments on application {} — nothing to import", applicationId);
            return;
        }
        // Map fileId → its CDN base name (carries the date) and declared type.
        Map<String, String> nameByFileId = new HashMap<>();
        Map<String, String> typeByFileId = new HashMap<>();
        List<String> fileIds = new ArrayList<>();
        for (JsonNode a : attachments) {
            String fileId = a.path("fileId").asText(null);
            if (fileId == null) {
                continue;
            }
            fileIds.add(fileId);
            nameByFileId.put(fileId, baseName(a.path("fileName").asText("")));
            typeByFileId.put(fileId, a.path("type").asText(null));
        }

        byte[] zip = portal.downloadFilesArchive(fileIds);
        int imported = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] content = zis.readAllBytes();
                Matcher m = ENTRY.matcher(entry.getName());
                if (!m.matches()) {
                    log.warn("Skipping unrecognised archive entry {}", entry.getName());
                    continue;
                }
                String fileId = matchFileId(m.group("fid"), fileIds);
                String type =
                        fileId != null && typeByFileId.get(fileId) != null
                                ? typeByFileId.get(fileId)
                                : m.group("type");
                String name =
                        fileId != null && nameByFileId.get(fileId) != null
                                ? nameByFileId.get(fileId)
                                : entry.getName();
                LocalDate date = parseDate(name);
                repository.save(
                        new ApplicantDocument(type, name, contentTypeOf(name), content, date));
                imported++;
            }
        } catch (Exception e) {
            throw new RuntimeException("failed reading document archive: " + e.getMessage(), e);
        }
        log.info("Imported {} document(s) from application {} into the document store", imported, applicationId);
    }

    /** Resolves the archive entry's short fileId (a prefix) back to the full fileId. */
    private static String matchFileId(String shortId, List<String> fileIds) {
        for (String f : fileIds) {
            if (f.replace("-", "").startsWith(shortId) || f.startsWith(shortId)) {
                return f;
            }
        }
        return null;
    }

    private static String baseName(String url) {
        if (url == null || url.isBlank()) {
            return "document";
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static LocalDate parseDate(String name) {
        Matcher m = DATE_IN_NAME.matcher(name == null ? "" : name);
        if (m.find()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            } catch (Exception ignore) {
                // fall through
            }
        }
        return null;
    }

    private static String contentTypeOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".jpeg") || n.endsWith(".jpg")) {
            return "image/jpeg";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
