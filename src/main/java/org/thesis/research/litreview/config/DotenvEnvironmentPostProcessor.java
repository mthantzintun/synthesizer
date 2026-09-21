package org.thesis.research.litreview.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads a {@code .env} file into the Spring {@link ConfigurableEnvironment}.
 *
 * <p>Spring Boot has no native support for {@code .env} - it is a Docker Compose
 * and shell convention, not a Spring one. Without this class, {@code .env} would
 * be read by {@code docker compose} (for the Postgres and GROBID containers) but
 * ignored by the application itself, so the two halves of the stack would
 * silently disagree about credentials. This makes the one file the source for
 * both.
 *
 * <p><b>Precedence.</b> Real configuration always wins over the file:
 * <ol>
 *   <li>command-line arguments and OS environment variables</li>
 *   <li>this {@code .env} file</li>
 *   <li>{@code application.yaml} and its profile variants</li>
 * </ol>
 * A {@code .env} in the working tree is therefore convenient locally, while
 * {@code OPENROUTER_API_KEY=... java -jar app.jar} in CI still takes precedence.
 * This works by running before the config-data post-processor (so
 * {@code application.yaml} is not yet loaded), skipping any key the environment
 * already defines, then inserting the remainder ahead of everything else.
 *
 * <p><b>Registration.</b> Listed in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.EnvironmentPostProcessor}. That file - not a
 * {@code .imports} file - is how Spring Boot 4 discovers these.
 *
 * <p><b>Logging.</b> The {@link Log} comes from the {@link DeferredLogFactory}
 * rather than a logger created here. Environment post-processors run before
 * logging is initialised (this one at {@code HIGHEST_PRECEDENCE}, while Spring
 * Boot's logging listener sits at {@code HIGHEST_PRECEDENCE + 20}), so a normal
 * logger would silently swallow everything this class emits - including the
 * "loaded N properties" line that is the only console evidence {@code .env} was
 * picked up at all. A deferred log buffers and replays once logging is ready.
 *
 * <p>Note that a deferred log is a commons-logging {@link Log}, which has no
 * {@code {}} placeholder support, so messages are built with
 * {@link String#format}.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The property source name, so it is identifiable in the actuator env endpoint. */
    public static final String SOURCE_NAME = "dotenv";

    /** Overrides the search, e.g. {@code DOTENV_PATH=/etc/synthesizer/.env}. */
    static final String PATH_OVERRIDE_ENV = "DOTENV_PATH";

    /** How far up the tree to look, so running from a submodule still finds the root file. */
    private static final int MAX_PARENT_DEPTH = 4;

    /**
     * A deferred log that is never replayed, so it discards everything. Lets the
     * static helpers be called from tests without producing output.
     */
    static final Log NO_OP_LOG = new DeferredLog();

    private final Log log;

    /**
     * @param logFactory supplied by Spring Boot's instantiator; see the class
     *                   javadoc for why the log must be deferred rather than
     *                   created directly
     */
    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    /**
     * Runs before {@code ConfigDataEnvironmentPostProcessor}
     * ({@code HIGHEST_PRECEDENCE + 10}). The ordering is load-bearing: the
     * "is this key already set?" check below must run while the environment
     * still holds only real configuration, not values {@code application.yaml}
     * is about to contribute.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = locate(log);
        if (envFile == null) {
            log.debug("No .env file found; using application.yaml and the OS environment only");
            return;
        }

        Map<String, String> parsed = parse(envFile, log);
        if (parsed.isEmpty()) {
            log.debug(String.format("%s exists but declares no properties", envFile));
            return;
        }

        // A key already present came from a command-line argument, a system
        // property or an OS environment variable - all of which must win. Only
        // the remainder is taken from the file.
        Map<String, Object> effective = new LinkedHashMap<>();
        parsed.forEach((key, value) -> {
            if (environment.getProperty(key) == null) {
                effective.put(key, value);
            }
        });

        if (effective.isEmpty()) {
            log.debug(String.format(
                    "%s: every key is already set in the environment; nothing to add", envFile));
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, effective));
        log.info(String.format("Loaded %d propert%s from %s (real environment variables take precedence)",
                effective.size(), effective.size() == 1 ? "y" : "ies", envFile.toAbsolutePath()));
    }

    // ------------------------------------------------------------------ lookup

    /** Finds the {@code .env} to use, or null when there is none. */
    static Path locate(Log log) {
        String override = System.getenv(PATH_OVERRIDE_ENV);
        if (override != null && !override.isBlank()) {
            Path path = Paths.get(override);
            if (Files.isReadable(path)) {
                return path;
            }
            log.warn(String.format(
                    "%s points at %s which is not readable; falling back to the default search",
                    PATH_OVERRIDE_ENV, path));
        }

        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth <= MAX_PARENT_DEPTH && dir != null; depth++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ----------------------------------------------------------------- parsing

    /**
     * Parses a dotenv file.
     *
     * <p>Supports what a {@code .env} realistically contains and no more: blank
     * lines, {@code #} comments, an optional {@code export} prefix, and values
     * optionally wrapped in single or double quotes. Values are <em>not</em>
     * shell-expanded and multi-line values are not supported - a secret needing
     * either is better passed as a real environment variable.
     *
     * @return declared keys in file order; empty when the file is empty or unreadable
     */
    static Map<String, String> parse(Path file, Log log) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            // An unreadable .env must never stop the application from starting -
            // it is a convenience, not a requirement.
            log.warn(String.format("Could not read %s: %s", file, e.getMessage()));
            return values;
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                log.warn(String.format("%s:%d: ignoring line without KEY=VALUE: %s",
                        file.getFileName(), i + 1, line));
                continue;
            }

            String key = line.substring(0, eq).strip();
            values.put(key, unquote(line.substring(eq + 1).strip()));
        }
        return values;
    }

    /**
     * Removes matching surrounding quotes and resolves escapes in double quotes.
     *
     * <p>{@code "a\nb"} becomes a real newline (handy for a PEM key), while
     * {@code 'a\nb'} stays literal - the same distinction the shell makes, so a
     * value copied out of a shell script behaves identically here.
     */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        // An unquoted value keeps everything after the first '=' verbatim,
        // including any further '=' (base64 keys contain them).
        return value;
    }
}

