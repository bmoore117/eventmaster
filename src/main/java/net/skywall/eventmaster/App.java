package net.skywall.eventmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point and CLI dispatch.
 *
 * <pre>
 *   java -jar eventmaster.jar                  # run the connector
 *   java -jar eventmaster.jar test             # POST a synthetic events payload
 *   java -jar eventmaster.jar test --error     # simulate a scraper failure
 *   java -jar eventmaster.jar test --warnings  # POST a synthetic warnings payload
 *   java -jar eventmaster.jar test --dry-run
 * </pre>
 *
 * Exit codes: 0 success, 1 connector/webhook failure, 2 invalid CLI usage.
 */
public final class App {

    static {
        LoggingInit.configure();
    }

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private App() {}

    public static void main(String[] args) {
        int exitCode;
        try {
            Config config = new Config();
            log.info("App directory: {}", config.root);
            log.info("Log file: {}", config.logPath);

            if (args.length == 0) {
                exitCode = new ConnectorRun(config).execute();
            } else if ("test".equals(args[0])) {
                exitCode = runTestCommand(config, args);
            } else if (args[0].equals("-h") || args[0].equals("--help")) {
                printHelp();
                exitCode = 0;
            } else {
                System.err.println("Unknown command: " + args[0]
                        + " — use: java -jar eventmaster.jar [test [--error] [--dry-run]]");
                exitCode = 2;
            }
        } catch (Throwable t) {
            log.error("Unhandled error: {}", t.getMessage(), t);
            exitCode = 1;
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int runTestCommand(Config config, String[] args) {
        boolean simulateError = false;
        boolean simulateWarnings = false;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--error" -> simulateError = true;
                case "--warnings" -> simulateWarnings = true;
                case "--dry-run" -> dryRun = true;
                case "-h", "--help" -> {
                    printTestHelp();
                    return 0;
                }
                default -> {
                    System.err.println("Unknown test option: " + args[i]);
                    return 2;
                }
            }
        }
        if (simulateError && simulateWarnings) {
            System.err.println("--error and --warnings are mutually exclusive");
            return 2;
        }
        HermesClient client = new HermesClient(config);
        if (simulateWarnings) {
            return HermesTest.runWarnings(client, dryRun);
        }
        return HermesTest.run(client, simulateError, dryRun);
    }

    private static void printHelp() {
        System.out.println("""
                Usage:
                  java -jar eventmaster.jar                  Run the Gmail + Luma fetch and notify Hermes.
                  java -jar eventmaster.jar test             POST a synthetic events webhook payload.
                  java -jar eventmaster.jar test --error     Simulate a scraper failure.
                  java -jar eventmaster.jar test --warnings  POST a synthetic warnings webhook payload.
                  java -jar eventmaster.jar test --dry-run   Print the JSON payload without POSTing.
                """);
    }

    private static void printTestHelp() {
        System.out.println("""
                Usage: java -jar eventmaster.jar test [--error | --warnings] [--dry-run]
                  --error     Simulate a failed scraper run (events channel, hasErrors=true, empty newEvents).
                  --warnings  Simulate a source-level warning transition (warnings channel,
                              one current + one resolved entry). Mutually exclusive with --error.
                  --dry-run   Print the JSON payload to stdout without POSTing.
                """);
    }
}
