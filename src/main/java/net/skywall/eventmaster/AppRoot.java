package net.skywall.eventmaster;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime file root. When launched as {@code java -jar eventmaster.jar}, this is
 * the directory containing the JAR (so cron does not need {@code cd}). During
 * IDE/Gradle runs it falls back to the process working directory.
 */
final class AppRoot {

    private static final Path DIRECTORY = detect();

    private AppRoot() {}

    static Path directory() {
        return DIRECTORY;
    }

    static Path resolve(String relativePath) {
        Path path = Path.of(relativePath);
        return path.isAbsolute() ? path : DIRECTORY.resolve(path);
    }

    private static Path detect() {
        try {
            var codeSource = App.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Path.of("").toAbsolutePath();
            }
            URI location = codeSource.getLocation().toURI();
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().getParent();
            }
        } catch (Exception ignored) {
            // Fall back to the working directory for tests and IDE runs.
        }
        return Path.of("").toAbsolutePath();
    }
}
