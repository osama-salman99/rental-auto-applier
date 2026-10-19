package eu.ikwilhuren.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IkwilhurenCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IkwilhurenCrawlerApplication.class, args);
    }
}
