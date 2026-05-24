package net.skywall.eventmaster;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Sets {@code eventmaster.log.path} before Logback initialises. */
final class LoggingInit {

    private static final String PROPERTIES_FILE = "eventmaster.properties";
    private static final String LOG_PATH_KEY = "EVENTMASTER_LOG_PATH";
    private static final String SYSTEM_PROPERTY = "eventmaster.log.path";
    private static final String DEFAULT_LOG_FILE = "connector.log";

    private LoggingInit() {}

    static void configure() {
        if (System.getProperty(SYSTEM_PROPERTY) != null) {
            return;
        }

        String logFile = System.getenv(LOG_PATH_KEY);
        if (logFile == null || logFile.isBlank()) {
            logFile = readPropertiesLogPath();
        }
        if (logFile == null || logFile.isBlank()) {
            logFile = DEFAULT_LOG_FILE;
        }

        System.setProperty(SYSTEM_PROPERTY, AppRoot.resolve(logFile).toString());
    }

    private static String readPropertiesLogPath() {
        Path propsPath = AppRoot.resolve(PROPERTIES_FILE);
        if (!Files.isRegularFile(propsPath)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) {
            props.load(in);
        } catch (Exception e) {
            return null;
        }
        return props.getProperty(LOG_PATH_KEY);
    }
}
