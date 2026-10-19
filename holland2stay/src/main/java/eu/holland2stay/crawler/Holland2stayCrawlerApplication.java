package eu.holland2stay.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Holland2stayCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(Holland2stayCrawlerApplication.class, args);
    }
}
