package eu.osmosis.crawler.core.http;

public class CurlFetchException extends RuntimeException {
    private final int exitCode;

    public CurlFetchException(String message) {
        super(message);
        this.exitCode = -1;
    }

    public CurlFetchException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public CurlFetchException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
    }

    /** curl exit 28 = timeout; worth retrying. exit 22 = HTTP 4xx/5xx; not transient. */
    public boolean isTransient() {
        return exitCode == 28;
    }
}
