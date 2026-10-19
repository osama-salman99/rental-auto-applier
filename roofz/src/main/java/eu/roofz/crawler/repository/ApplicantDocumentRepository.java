package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.DocumentView;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApplicantDocumentRepository extends JpaRepository<ApplicantDocument, Long> {

    List<ApplicantDocument> findByDocTypeOrderByDocumentDateDescUploadedAtDesc(String docType);

    List<ApplicantDocument> findAllByOrderByDocTypeAscDocumentDateDescUploadedAtDesc();

    /**
     * Metadata-only reads. Spring Data derives a projection from the {@link DocumentView} return type,
     * so the {@code @Lob} content column is never selected and the file bytes never reach heap.
     */
    List<DocumentView> findAllProjectedByOrderByDocTypeAscDocumentDateDescUploadedAtDesc();

    List<DocumentView> findProjectedByDocTypeOrderByDocumentDateDescUploadedAtDesc(String docType);

    /** Rows written before {@code size_bytes} existed, so it can be backfilled once on startup. */
    @Query("SELECT d.id FROM ApplicantDocument d WHERE d.sizeBytes = 0")
    List<Long> findIdsMissingSize();
}
