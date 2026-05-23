package net.skywall.eventmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Path processedInstagramIdsPath;
    private final Path connectorStatePath;

    public StateStore(Path processedIdsPath, Path processedInstagramIdsPath, Path connectorStatePath) {
        this.processedIdsPath = processedIdsPath;
        this.processedInstagramIdsPath = processedInstagramIdsPath;
        this.connectorStatePath = connectorStatePath;
    }

    public Set<String> loadProcessedIds() {
        return loadIdSet(processedIdsPath);
    }

    public void saveProcessedIds(Set<String> ids) throws IOException {
        saveIdSet(processedIdsPath, ids);
    }

    public Set<String> loadProcessedInstagramIds() {
        return loadIdSet(processedInstagramIdsPath);
    }

    public void saveProcessedInstagramIds(Set<String> ids) throws IOException {
        saveIdSet(processedInstagramIdsPath, ids);
    }

    public int loadConsecutiveFailures() {
        return loadConnectorState().consecutiveFailures();
    }

    public Set<String> loadInstagramBootstrapped() {
        return loadConnectorState().instagramBootstrapped();
    }

    public void saveConsecutiveFailures(int n) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(n, current.instagramBootstrapped()));
    }

    public void saveInstagramBootstrapped(Set<String> accounts) throws IOException {
        ConnectorState current = loadConnectorState();
        saveConnectorState(new ConnectorState(current.consecutiveFailures(), accounts));
    }

    private ConnectorState loadConnectorState() {
        if (!Files.exists(connectorStatePath)) {
            return new ConnectorState(0, Set.of());
        }
        try {
            JsonNode root = Json.MAPPER.readTree(Files.readAllBytes(connectorStatePath));
            Set<String> bootstrapped = new HashSet<>();
            JsonNode handles = root.path("instagram_bootstrapped");
            if (handles.isArray()) {
                for (JsonNode handle : handles) {
                    String value = handle.asString(null);
                    if (value != null && !value.isBlank()) {
                        bootstrapped.add(value);
                    }
                }
            }
            return new ConnectorState(root.path("consecutive_failures").asInt(0), bootstrapped);
        } catch (IOException e) {
            log.warn("Could not parse {} — assuming fresh connector state", connectorStatePath.getFileName());
            return new ConnectorState(0, Set.of());
        }
    }

    private void saveConnectorState(ConnectorState state) throws IOException {
        Files.createDirectories(connectorStatePath.getParent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consecutive_failures", state.consecutiveFailures());
        if (!state.instagramBootstrapped().isEmpty()) {
            body.put("instagram_bootstrapped", new ArrayList<>(new TreeSet<>(state.instagramBootstrapped())));
        }
        Files.write(connectorStatePath, Json.PRETTY.writeValueAsBytes(body));
    }

    private static Set<String> loadIdSet(Path path) {
        if (!Files.exists(path)) {
            return new HashSet<>();
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Set<String> ids = new HashSet<>();
            for (String line : content.split("\\R")) {
                if (!line.isEmpty()) {
                    ids.add(line);
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

    private record ConnectorState(int consecutiveFailures, Set<String> instagramBootstrapped) {}
}
