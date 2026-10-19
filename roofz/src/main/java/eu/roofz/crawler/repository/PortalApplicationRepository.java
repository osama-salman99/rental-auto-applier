package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalApplicationRepository extends JpaRepository<PortalApplication, Long> {

    Optional<PortalApplication> findByInvitationId(String invitationId);

    Optional<PortalApplication> findByApplicationId(String applicationId);

    boolean existsBySourceEmailMessageId(String sourceEmailMessageId);

    boolean existsByCreatedAtGreaterThanEqual(java.time.Instant createdAt);

    List<PortalApplication> findByStateIn(List<PortalApplicationState> states);

    List<PortalApplication> findAllByOrderByCreatedAtDesc();

    /** Pre-application submitted but no confirmation email seen yet, and not yet alerted (W2). */
    List<PortalApplication>
            findByPreAppSubmittedAtIsNotNullAndPreAppConfirmedAtIsNullAndPreAppConfirmWatchdogAlertedFalse();

    /** Full application finalized but no confirmation email seen yet, and not yet alerted (W3). */
    List<PortalApplication>
            findByFinalizedAtIsNotNullAndFullAppConfirmedAtIsNullAndFullAppConfirmWatchdogAlertedFalse();
}
