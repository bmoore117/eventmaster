package net.skywall.eventmaster;

/**
 * Hard failure talking to the ScrapeCreators API: non-2xx response, network
 * error, or interrupted request. Distinct from "API returned no posts", which
 * remains an empty list. {@link #code} is the short tag used as the
 * {@code RunWarning} code (e.g. {@code "scrapecreators_402"},
 * {@code "scrapecreators_io"}).
 */
public final class ScrapeCreatorsException extends RuntimeException {
    private final String code;

    public ScrapeCreatorsException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
