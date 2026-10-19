package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.DocumentView;
import eu.roofz.crawler.repository.ApplicantDocumentRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApplicantDocumentServiceTest {

    private final ApplicantDocumentRepository repo = mock(ApplicantDocumentRepository.class);
    private final ApplicantDocumentService service = new ApplicantDocumentService(repo);

    private long nextId = 1;

    /**
     * Selection now runs on {@link DocumentView} metadata and only then loads the entities, so a
     * fixture needs an id and a matching {@code findById} — which also keeps these tests covering the
     * metadata → entity hop rather than just the algorithm.
     */
    private ApplicantDocument doc(String type, String name, LocalDate date) {
        ApplicantDocument doc = new ApplicantDocument(type, name, "application/pdf", new byte[] {1, 2, 3}, date);
        doc.setId(nextId++);
        when(repo.findById(doc.getId())).thenReturn(Optional.of(doc));
        return doc;
    }

    private void storeContains(List<ApplicantDocument> all, List<ApplicantDocument> salary, List<ApplicantDocument> bank) {
        when(repo.findAllProjectedByOrderByDocTypeAscDocumentDateDescUploadedAtDesc())
                .thenReturn(List.copyOf(all));
        when(repo.findProjectedByDocTypeOrderByDocumentDateDescUploadedAtDesc("salarySlip"))
                .thenReturn(List.copyOf(salary));
        when(repo.findProjectedByDocTypeOrderByDocumentDateDescUploadedAtDesc("bankStatement"))
                .thenReturn(List.copyOf(bank));
    }

    @Test
    void selectsAllUndatedPlusMostRecentDated() {
        ApplicantDocument idFront = doc("identityDocument", "id-front.jpg", null);
        ApplicantDocument idBack = doc("identityDocument", "id-back.jpg", null);
        ApplicantDocument employer = doc("employerStatement", "employer.pdf", null);
        ApplicantDocument payJan = doc("salarySlip", "pay-jan.pdf", LocalDate.of(2026, 1, 1));
        ApplicantDocument payFeb = doc("salarySlip", "pay-feb.pdf", LocalDate.of(2026, 2, 1));
        ApplicantDocument payMar = doc("salarySlip", "pay-mar.pdf", LocalDate.of(2026, 3, 1));
        ApplicantDocument payApr = doc("salarySlip", "pay-apr.pdf", LocalDate.of(2026, 4, 1));
        ApplicantDocument bankFeb = doc("bankStatement", "bank-feb.pdf", LocalDate.of(2026, 2, 1));
        ApplicantDocument bankMar = doc("bankStatement", "bank-mar.pdf", LocalDate.of(2026, 3, 1));

        storeContains(
                List.of(idFront, idBack, employer, payJan, payFeb, payMar, payApr, bankFeb, bankMar),
                List.of(payApr, payMar, payFeb, payJan),
                List.of(bankMar, bankFeb));

        List<ApplicantDocument> selected = service.selectForApplication(3);

        // All undated docs kept.
        assertThat(selected).contains(idFront, idBack, employer);
        // Only the 3 most-recent payslips (Apr, Mar, Feb) — January dropped.
        assertThat(selected).contains(payApr, payMar, payFeb).doesNotContain(payJan);
        // Both bank statements kept (fewer than the window).
        assertThat(selected).contains(bankMar, bankFeb);
        assertThat(selected).hasSize(3 + 3 + 2);
    }

    @Test
    void duplicateMonthDoesNotCrowdOutOtherMonths() {
        // Store has April twice and June (no May) — the current real situation.
        ApplicantDocument bankApr1 = doc("bankStatement", "bank-apr-1.pdf", LocalDate.of(2026, 4, 1));
        ApplicantDocument bankApr2 = doc("bankStatement", "bank-apr-2.pdf", LocalDate.of(2026, 4, 1));
        ApplicantDocument bankJun = doc("bankStatement", "bank-jun.pdf", LocalDate.of(2026, 6, 1));

        storeContains(
                List.of(bankJun, bankApr1, bankApr2), List.of(), List.of(bankJun, bankApr1, bankApr2));

        List<ApplicantDocument> selected = service.selectForApplication(3);

        // Distinct months only: June + one April (not two Aprils), and never both April copies.
        assertThat(selected).contains(bankJun);
        assertThat(selected).containsAnyOf(bankApr1, bankApr2);
        assertThat(selected).hasSize(2);
        assertThat(selected.stream().filter(d -> "bankStatement".equals(d.getDocType())).count()).isEqualTo(2);
    }

    @Test
    void windowOfOneKeepsOnlyLatestOfEachDatedType() {
        ApplicantDocument payFeb = doc("salarySlip", "pay-feb.pdf", LocalDate.of(2026, 2, 1));
        ApplicantDocument payMar = doc("salarySlip", "pay-mar.pdf", LocalDate.of(2026, 3, 1));

        storeContains(List.of(payFeb, payMar), List.of(payMar, payFeb), List.of());

        List<ApplicantDocument> selected = service.selectForApplication(1);

        assertThat(selected).containsExactly(payMar);
    }

    /** The dashboard path: metadata only, so the file bytes are never loaded. */
    @Test
    void metadataSelectionNeverLoadsFileBytes() {
        ApplicantDocument payMar = doc("salarySlip", "pay-mar.pdf", LocalDate.of(2026, 3, 1));
        ApplicantDocument employer = doc("employerStatement", "employer.pdf", null);

        storeContains(List.of(employer, payMar), List.of(payMar), List.of());

        List<DocumentView> selected = service.selectMetaForApplication(3);

        assertThat(selected).extracting(DocumentView::getFileName)
                .containsExactlyInAnyOrder("employer.pdf", "pay-mar.pdf");
        // Size is served from the persisted column, not by measuring the blob.
        assertThat(selected).allSatisfy(d -> assertThat(d.getSizeBytes()).isEqualTo(3));
    }
}
