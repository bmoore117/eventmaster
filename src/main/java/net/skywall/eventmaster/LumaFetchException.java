package net.skywall.eventmaster;

/**
 * Hard failure fetching the top-level Luma calendar/group page: non-2xx
 * response, network error, or interrupted request. Per-event-page failures
 * inside a calendar fetch are still soft (the stub is kept) — only the
 * calendar page itself raises this. {@link #code} is the short tag used as
 * the {@code RunWarning} code (e.g. {@code "luma_http_502"},
 * {@code "luma_io"}).
 */
public final class LumaFetchException extends RuntimeException {
    private final String code;

    public LumaFetchException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
