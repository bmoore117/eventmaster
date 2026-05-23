package net.skywall.eventmaster;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persists {@code .processed_ids} (deduplication of seen Gmail message IDs) and
 * {@code .connector-state.json} ({@code consecutive_failures} counter for the
 * alerting backoff).
 */
public final class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final Path processedIdsPath;
    private final Path connectorStatePath;

    public StateStore(Path processedIdsPath, Path connectorStatePath) {
        this.processedIdsPath = processedIdsPath;
        this.connectorStatePath = connectorStatePath;
    }

    public Set<String> loadProcessedIds() {
        if (!Files.exists(processedIdsPath)) {
            return new HashSet<>();
        }
        try {
            String content = Files.readString(processedIdsPath, StandardCharsets.UTF_8);
            Set<String> ids = new HashSet<>();
            for (String line : content.split("\\R")) {
                if (!line.isEmpty()) {
                    ids.add(line);
                }
            }
            return ids;
        } catch (IOException e) {
            log.warn("Could not read {} — starting fresh", processedIdsPath.getFileName());
            return new HashSet<>();
        }
    }

    public void saveProcessedIds(Set<String> ids) throws IOException {
        Files.createDirectories(processedIdsPath.getParent());
        String body = String.join("\n", new TreeSet<>(ids != null ? ids : Collections.emptySet()));
        Files.writeString(processedIdsPath, body, StandardCharsets.UTF_8);
    }

    public int loadConsecutiveFailures() {
        if (!Files.exists(connectorStatePath)) {
            return 0;
        }
        try {
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(connectorStatePath));
            return root.path("consecutive_failures").asInt(0);
        } catch (IOException e) {
            log.warn("Could not parse {} — assuming 0 consecutive failures", connectorStatePath.getFileName());
            return 0;
        }
    }

    public void saveConsecutiveFailures(int n) throws IOException {
        Files.createDirectories(connectorStatePath.getParent());
        byte[] body = Json.PRETTY.writeValueAsBytes(Map.of("consecutive_failures", n));
        Files.write(connectorStatePath, body);
    }
}
