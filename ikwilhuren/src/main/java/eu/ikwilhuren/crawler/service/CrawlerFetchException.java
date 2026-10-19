package eu.ikwilhuren.crawler.service;

public class CrawlerFetchException extends RuntimeException {
    public CrawlerFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
