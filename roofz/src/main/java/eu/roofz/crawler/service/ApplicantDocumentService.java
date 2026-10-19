package eu.roofz.crawler.service;

import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.DocumentView;
import eu.roofz.crawler.repository.ApplicantDocumentRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Chooses which stored {@link ApplicantDocument}s to attach to a full application. Undated documents
 * (ID, employer statement) are always included; dated documents (payslips, bank statements) are
 * trimmed to the {@code monthsWindow} most recent by {@link ApplicantDocument#getDocumentDate()} so
 * an application submitted months from now still carries the latest three months, not stale files.
 *
 * <p>The selection runs entirely on {@link DocumentView} metadata. Callers that only display the
 * choice (the dashboard) never touch the file bytes; only {@link #selectForApplication(int)}, which
 * feeds the portal upload, loads whole entities.
 */
@Service
public class ApplicantDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ApplicantDocumentService.class);

    /** Portal attachment types that carry a month and should be limited to the most recent N. */
    public static final List<String> DATED_TYPES = List.of("salarySlip", "bankStatement");

    private final ApplicantDocumentRepository repository;

    public ApplicantDocumentService(ApplicantDocumentRepository repository) {
        this.repository = repository;
    }

    /** Every stored document, metadata only — what the dashboard lists. */
    public List<DocumentView> findAllMeta() {
        return repository.findAllProjectedByOrderByDocTypeAscDocumentDateDescUploadedAtDesc();
    }

    public ApplicantDocument save(ApplicantDocument doc) {
        return repository.save(doc);
    }

    public void delete(long id) {
        repository.deleteById(id);
    }

    /**
     * The documents to attach to a full application: every undated document, plus — for each dated
     * type — one document per calendar month for the {@code monthsWindow} most recent <b>distinct</b>
     * months. Collapsing per month first means a duplicate upload of one month (e.g. April twice)
     * never crowds out another month; you always get the latest N distinct months. A dated document
     * missing its date is treated as its own newest entry, so an un-dated upload is never dropped.
     */
    public List<DocumentView> selectMetaForApplication(int monthsWindow) {
        int window = Math.max(1, monthsWindow);
        List<DocumentView> selected = new ArrayList<>();
        for (DocumentView doc : findAllMeta()) {
            if (!DATED_TYPES.contains(doc.getDocType())) {
                selected.add(doc);
            }
        }
        for (String type : DATED_TYPES) {
            repository.findProjectedByDocTypeOrderByDocumentDateDescUploadedAtDesc(type).stream()
                    .sorted(mostRecentFirst())
                    // one document per month (the sort put the newest upload for each month first, and
                    // the merge keeps that first-seen one), preserving newest-month-first order.
                    .collect(
                            Collectors.toMap(
                                    ApplicantDocumentService::monthKey,
                                    doc -> doc,
                                    (newest, duplicate) -> newest,
                                    LinkedHashMap::new))
                    .values()
                    .stream()
                    .limit(window)
                    .forEach(selected::add);
        }
        return selected;
    }

    /**
     * The same selection with the file bytes loaded — for the portal upload path, the only caller that
     * actually needs them.
     */
    public List<ApplicantDocument> selectForApplication(int monthsWindow) {
        return selectMetaForApplication(monthsWindow).stream()
                .map(meta -> repository.findById(meta.getId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * One-time backfill for documents stored before {@code size_bytes} existed, so the dashboard can
     * show their size without reading the blob. After this, every write keeps the column current.
     *
     * <p>Never allowed to be fatal. This listener runs after the context is up and Tomcat is already
     * serving, so an exception here does not fail startup — it kills an app that has just reported
     * itself healthy, which is how a cosmetic backfill once took the whole crawler down and defeated
     * the deployer's health check. A missing size is a wrong number on one page; a dead crawler is
     * missed apartments.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingSizes() {
        try {
            List<Long> ids = repository.findIdsMissingSize();
            if (ids.isEmpty()) {
                return;
            }
            // One at a time — these are the very blobs we are trying to keep out of heap.
            for (Long id : ids) {
                repository
                        .findById(id)
                        .ifPresent(
                                doc -> {
                                    doc.setContent(doc.getContent()); // recomputes sizeBytes
                                    repository.save(doc);
                                });
            }
            log.info("Backfilled size_bytes for {} stored document(s)", ids.size());
        } catch (Exception e) {
            log.warn("size_bytes backfill skipped: {}", e.toString());
        }
    }

    /** Groups dated documents by calendar month; an undated one is unique so it is never merged away. */
    private static String monthKey(DocumentView doc) {
        LocalDate date = doc.getDocumentDate();
        return date == null ? "undated-" + System.identityHashCode(doc) : YearMonth.from(date).toString();
    }

    /** Newest first; a null date sorts as newest (nullsFirst) so an un-dated file is never dropped. */
    private static Comparator<DocumentView> mostRecentFirst() {
        return Comparator.comparing(
                        DocumentView::getDocumentDate,
                        Comparator.nullsFirst(Comparator.<LocalDate>reverseOrder()))
                .thenComparing(DocumentView::getUploadedAt, Comparator.reverseOrder());
    }
}
