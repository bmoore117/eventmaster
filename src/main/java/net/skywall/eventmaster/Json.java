package net.skywall.eventmaster;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared Jackson configuration. We expose two writers:
 * <ul>
 *   <li>{@link #PRETTY} — 2-space indented, matches Python's {@code json.dumps(..., indent=2)}
 *       for human-readable output files.
 *   <li>{@link #COMPACT} — no whitespace, matches Python's {@code separators=(",", ":")}
 *       used to compute the Hermes webhook HMAC.
 * </ul>
 * Nulls are deliberately preserved (Python serialises {@code None} as {@code null}).
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static final ObjectWriter PRETTY =
            MAPPER.writerWithDefaultPrettyPrinter();

    public static final ObjectWriter COMPACT = MAPPER.writer();

    private Json() {}
}
