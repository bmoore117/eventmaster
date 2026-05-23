package net.skywall.eventmaster;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared, thread-safe {@link HttpClient}. Reuse keeps connection pooling intact across scrapes. */
public final class Http {

    public static final String USER_AGENT = "Mozilla/5.0 (compatible; email-connector/1.0)";

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}
}
