package eu.vbt.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VbtCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VbtCrawlerApplication.class, args);
    }
}
