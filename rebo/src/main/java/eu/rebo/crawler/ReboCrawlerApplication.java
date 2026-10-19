package eu.rebo.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReboCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReboCrawlerApplication.class, args);
    }
}
