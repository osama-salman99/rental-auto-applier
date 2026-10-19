package eu.ikwilhuren.crawler.service;

/** A step of the viewing-request flow failed in a way the operator needs to know about. */
public class PortalException extends RuntimeException {

    public PortalException(String message) {
        super(message);
    }

    public PortalException(String message, Throwable cause) {
        super(message, cause);
    }
}
