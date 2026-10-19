package eu.roofz.crawler.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Everything about a stored document <em>except</em> its bytes.
 *
 * <p>{@link ApplicantDocument} keeps the file in an eagerly-fetched {@code @Lob byte[]}, so loading a
 * list of entities drags every payslip, bank statement and ID scan into heap — on a VPS where the
 * crawler already runs close to its ceiling, rendering /documents was a multi-megabyte spike for data
 * the page never shows. The dashboard and the month-reminder watchdog only ever need this metadata,
 * so they read a projection instead; only the portal upload path, which genuinely needs the bytes,
 * loads whole entities.
 */
public interface DocumentView {

    Long getId();

    String getDocType();

    String getFileName();

    LocalDate getDocumentDate();

    Instant getUploadedAt();

    long getSizeBytes();
}
