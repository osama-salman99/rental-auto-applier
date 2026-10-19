package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.repository.ListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class NotificationFlagMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NotificationFlagMigration.class);

    private final ListingRepository listingRepository;

    public NotificationFlagMigration(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        listingRepository.resetAllNotificationFlags();
        log.info("Reset notificationSent flags — will be re-set only on actual ntfy dispatch");
    }
}
