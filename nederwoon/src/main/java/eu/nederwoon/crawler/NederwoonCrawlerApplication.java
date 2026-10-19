package eu.nederwoon.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NederwoonCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NederwoonCrawlerApplication.class, args);
    }
}
