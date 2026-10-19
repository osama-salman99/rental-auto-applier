package eu.roofz.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.ColumnDefault;

/**
 * A document the applicant uploads once via the dashboard and the pipeline re-uploads to each new
 * ReletPortal full application (the portal exposes no way to download previously-uploaded files, so
 * we must keep our own copy of the bytes). Stored in the DB so it survives redeploys.
 *
 * <p>{@link #docType} is the portal's attachment type ({@code identityDocument}, {@code salarySlip},
 * {@code employerStatement}, {@code bankStatement}, …). {@link #documentDate} is the month a dated
 * document covers (payslips / bank statements); it drives "use the most recent N months at apply
 * time" selection and is null for undated documents (ID, employer statement).
 */
@Entity
@Table(name = "applicant_document")
public class ApplicantDocument implements DocumentView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String docType;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    @Lob
    @Column(nullable = false)
    private byte[] content;

    /** Month the document covers (payslip / bank statement); null for undated docs (ID, employer). */
    private LocalDate documentDate;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    /**
     * Persisted so the dashboard can show a file's size without loading the file. Derived from {@link
     * #content} on every write, so it cannot drift.
     *
     * <p>The {@code @ColumnDefault} is load-bearing, not decoration: a primitive maps to {@code NOT
     * NULL}, and H2 rejects adding a NOT NULL column to a table that already holds rows unless it
     * carries a default. Hibernate only logs a failed schema update, so without this the column is
     * silently never created and every read of it blows up at runtime.
     */
    @ColumnDefault("0")
    @Column(nullable = false)
    private long sizeBytes;

    public ApplicantDocument() {}

    public ApplicantDocument(
            String docType, String fileName, String contentType, byte[] content, LocalDate documentDate) {
        this.docType = docType;
        this.fileName = fileName;
        this.contentType = contentType;
        setContent(content);
        this.documentDate = documentDate;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
        this.sizeBytes = content == null ? 0 : content.length;
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }

    public void setDocumentDate(LocalDate documentDate) {
        this.documentDate = documentDate;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    @Override
    public long getSizeBytes() {
        // Rows written before size_bytes existed have 0 until they are next saved; fall back to the
        // bytes we already hold rather than showing "0 KB".
        if (sizeBytes == 0 && content != null) {
            return content.length;
        }
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
