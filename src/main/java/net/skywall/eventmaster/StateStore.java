package net.skywall.eventmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persists deduplication state and the {@code consecutive_failures} counter.
 */
public final class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final Path processedIdsPath;
    private final Path connectorStatePath;

    public StateStore(Path processedIdsPath, Path connectorStatePath) {
        this.processedIdsPath = processedIdsPath;
        this.connectorStatePath = connectorStatePath;
    }

    /** Gmail message IDs and Instagram post IDs share one dedup file. */
    public Set<String> loadProcessedIds() {
        if (Files.exists(processedIdsPath)) {
            return loadIdSet(processedIdsPath);
        }

        Set<String> merged = new HashSet<>();
        Path parent = processedIdsPath.getParent();
        merged.addAll(loadIdSet(parent.resolve(".processed_ids")));
        merged.addAll(loadIdSet(parent.resolve(".processed_instagram_ids")));
        if (!merged.isEmpty()) {
            log.info("Migrated {} processed id(s) from legacy .processed_* files", merged.size());
        }
        return merged;
    }

    public void saveProcessedIds(Set<String> ids) throws IOException {
        saveIdSet(processedIdsPath, ids);
    }

    public int loadConsecutiveFailures() {
        return loadConnectorState().consecutiveFailures();
    }

    public Set<String> loadInstagramBootstrapped() {
        return loadConnectorState().instagramBootstrapped();
    }

    public Set<String> loadLastWarningCodes() {
        return loadConnectorState().lastWarningCodes();
    }

    public String loadInstagramLastFetchedAt() {
        return loadConnectorState().instagramLastFetchedAt();
    }

    public void saveConsecutiveFailures(int n) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(n, current.instagramBootstrapped(),
                current.lastWarningCodes(), current.instagramLastFetchedAt()));
    }

    public void saveInstagramBootstrapped(Set<String> accounts) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(current.consecutiveFailures(), accounts,
                current.lastWarningCodes(), current.instagramLastFetchedAt()));
    }

    public void saveLastWarningCodes(Set<String> codes) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(current.consecutiveFailures(),
                current.instagramBootstrapped(), codes, current.instagramLastFetchedAt()));
    }

    public void saveInstagramLastFetchedAt(String fetchedAt) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(current.consecutiveFailures(),
                current.instagramBootstrapped(), current.lastWarningCodes(), fetchedAt));
    }

    private ConnectorState loadConnectorState() {
        if (!Files.exists(connectorStatePath)) {
            return new ConnectorState(0, Set.of(), Set.of(), null);
        }
        try {
            return Json.MAPPER.readValue(Files.readAllBytes(connectorStatePath), ConnectorState.class);
        } catch (IOException | JacksonException e) {
            log.warn("Could not parse {} — assuming fresh connector state", connectorStatePath.getFileName());
            return new ConnectorState(0, Set.of(), Set.of(), null);
        }
    }

    private void saveConnectorState(ConnectorState state) throws IOException {
        Files.createDirectories(connectorStatePath.getParent());
        Files.write(connectorStatePath, Json.PRETTY.writeValueAsBytes(state));
    }

    private static Set<String> loadIdSet(Path path) {
        if (!Files.exists(path)) {
            return new HashSet<>();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Set<String> ids = new HashSet<>();
            for (String line : content.split("\\R")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    ids.add(trimmed);
                }
            }
            return ids;
        } catch (IOException e) {
            log.warn("Could not read {} — starting fresh", path.getFileName());
            return new HashSet<>();
        }
    }

    private static void saveIdSet(Path path, Set<String> ids) throws IOException {
        Files.createDirectories(path.getParent());
        String body = String.join("\n", new TreeSet<>(ids != null ? ids : Collections.emptySet()));
        Files.writeString(path, body, StandardCharsets.UTF_8);
    }

    private record ConnectorState(
            int consecutiveFailures,
            Set<String> instagramBootstrapped,
            Set<String> lastWarningCodes,
            String instagramLastFetchedAt
    ) {}
}
