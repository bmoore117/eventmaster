package net.skywall.eventmaster;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

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

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static final ObjectWriter PRETTY =
            MAPPER.writerWithDefaultPrettyPrinter();

    public static final ObjectWriter COMPACT = MAPPER.writer();

    private Json() {}
}
