package org.thesis.research.litreview.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for the {@code .env} loader.
 *
 * <p>This class sits between the developer and every credential in the
 * application, so the failure modes matter more than the happy path: a secret
 * silently truncated at a {@code #} or a {@code =} looks like an authentication
 * error from the remote service, and a loader that never runs looks like
 * "my API key is not being picked up". Both are miserable to debug, so both are
 * pinned here.
 */
class DotenvEnvironmentPostProcessorTest {

    private static final org.apache.commons.logging.Log NO_OP =
            DotenvEnvironmentPostProcessor.NO_OP_LOG;

    private static Map<String, String> parse(Path dir, String content) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return DotenvEnvironmentPostProcessor.parse(file, NO_OP);
    }

    /** A processor wired to a deferred log, as Spring would construct it. */
    private static DotenvEnvironmentPostProcessor processor() {
        return new DotenvEnvironmentPostProcessor(new DeferredLogFactory() {
            @Override
            public org.apache.commons.logging.Log getLog(java.util.function.Supplier<org.apache.commons.logging.Log> supplier) {
                return supplier.get();
            }
        });
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void readsSimpleKeyValuePairs(@TempDir Path dir) throws IOException {
        Map<String, String> values = parse(dir, """
                OPENROUTER_API_KEY=sk-abc123
                LLM_MODEL=anthropic/claude-sonnet-4.5
                """);

        assertThat(values)
                .containsEntry("OPENROUTER_API_KEY", "sk-abc123")
                .containsEntry("LLM_MODEL", "anthropic/claude-sonnet-4.5");
    }

    @Test
    void ignoresCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        Map<String, String> values = parse(dir, """
                # a leading comment

                LLM_MODEL=anthropic/claude-sonnet-4.5
                  # an indented comment
                LITREVIEW_DB_POOL_SIZE=10
                """);

        assertThat(values).containsOnlyKeys("LLM_MODEL", "LITREVIEW_DB_POOL_SIZE");
    }

    @Test
    void stripsSurroundingQuotes(@TempDir Path dir) throws IOException {
        Map<String, String> values = parse(dir, """
                DOUBLE="quoted value"
                SINGLE='also quoted'
                """);

        assertThat(values)
                .containsEntry("DOUBLE", "quoted value")
                .containsEntry("SINGLE", "also quoted");
    }

    @Test
    void resolvesEscapesInDoubleQuotesButNotSingleQuotes(@TempDir Path dir) throws IOException {
        // The shell makes this distinction, so a value copied from a shell
        // script must behave identically here.
        Map<String, String> values = parse(dir, """
                DOUBLE="line1\\nline2"
                SINGLE='line1\\nline2'
                """);

        assertThat(values.get("DOUBLE")).isEqualTo("line1\nline2");
        assertThat(values.get("SINGLE")).isEqualTo("line1\\nline2");
    }

    @Test
    void keepsEverythingAfterTheFirstEqualsSign(@TempDir Path dir) throws IOException {
        // Base64 keys and JDBC URLs both contain '='. Splitting on the last one
        // would corrupt them.
        Map<String, String> values = parse(dir, """
                LITREVIEW_DB_URL=jdbc:postgresql://localhost:5432/litreview?sslmode=disable
                BASE64_KEY=YWJjZGVmPT0=
                """);

        assertThat(values.get("LITREVIEW_DB_URL"))
                .isEqualTo("jdbc:postgresql://localhost:5432/litreview?sslmode=disable");
        assertThat(values.get("BASE64_KEY")).isEqualTo("YWJjZGVmPT0=");
    }

    @Test
    void keepsHashCharactersInsideAValue(@TempDir Path dir) throws IOException {
        // A '#' only starts a comment at the beginning of a line, so a password
        // containing one is not truncated.
        Map<String, String> values = parse(dir, "LITREVIEW_DB_PASSWORD=p@ss#word!");

        assertThat(values.get("LITREVIEW_DB_PASSWORD")).isEqualTo("p@ss#word!");
    }

    @Test
    void acceptsAnExportPrefix(@TempDir Path dir) throws IOException {
        // People paste lines straight out of a shell script.
        Map<String, String> values = parse(dir, "export OPENROUTER_API_KEY=sk-abc");

        assertThat(values).containsEntry("OPENROUTER_API_KEY", "sk-abc");
    }

    @Test
    void recordsABlankValueAsBlankSoItFallsBackToTheDefault(@TempDir Path dir) throws IOException {
        // This is the state a fresh .env is in: the key exists but is empty.
        // It must be parsed (so the file is not considered empty) but must not
        // override anything meaningful.
        Map<String, String> values = parse(dir, "OPENROUTER_API_KEY=\nLLM_MODEL=x");

        assertThat(values).containsEntry("OPENROUTER_API_KEY", "");
        assertThat(values).containsEntry("LLM_MODEL", "x");
    }

    @Test
    void skipsMalformedLinesWithoutFailing(@TempDir Path dir) throws IOException {
        Map<String, String> values = parse(dir, """
                LLM_MODEL=anthropic/claude-sonnet-4.5
                this line has no equals sign
                LITREVIEW_DB_USER=litreview
                """);

        assertThat(values).containsOnlyKeys("LLM_MODEL", "LITREVIEW_DB_USER");
    }

    @Test
    void anEmptyFileYieldsNoProperties(@TempDir Path dir) throws IOException {
        assertThat(parse(dir, "")).isEmpty();
        assertThat(parse(dir, "# only a comment\n")).isEmpty();
    }

    @Test
    void anUnreadableFileYieldsNoPropertiesRatherThanThrowing(@TempDir Path dir) {
        // A missing .env must never stop the application from starting - the
        // bundled application.yaml defaults are enough to run.
        assertThat(DotenvEnvironmentPostProcessor.parse(dir.resolve("nope.env"), NO_OP)).isEmpty();
    }

    @Test
    void laterDuplicatesWin(@TempDir Path dir) throws IOException {
        // Last-write-wins matches shell sourcing, so re-declaring a key at the
        // bottom of the file to override an earlier line behaves as expected.
        Map<String, String> values = parse(dir, """
                LLM_MODEL=first
                LLM_MODEL=second
                """);

        assertThat(values).containsEntry("LLM_MODEL", "second");
    }

    // ---------------------------------------------------------------- contract

    @Test
    void runsBeforeConfigDataSoTheFileDoesNotOverrideApplicationYaml() {
        // The ordering is load-bearing: the "is this key already set?" check in
        // postProcessEnvironment must run while the environment still contains
        // only real configuration. ConfigDataEnvironmentPostProcessor sits at
        // HIGHEST_PRECEDENCE + 10.
        assertThat(processor().getOrder())
                .isLessThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Test
    void isRegisteredInSpringFactoriesSoSpringBootActuallyFindsIt() throws IOException {
        // Without this registration the class is dead code. This asserts on the
        // registration file rather than on SpringFactoriesLoader.load(...),
        // because loading instantiates every registered factory and Spring
        // Boot's own post-processors now take constructor arguments - an
        // unrelated change there would break this test for the wrong reason.
        String factories = new ClassPathResource("META-INF/spring.factories")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(factories)
                .contains("org.springframework.boot.EnvironmentPostProcessor")
                .contains(DotenvEnvironmentPostProcessor.class.getName());
    }

    @Test
    void isConstructibleWithTheArgumentSpringSupplies() {
        // Spring's instantiator supplies a DeferredLogFactory. Asserting the
        // shape here catches the class drifting to a constructor Spring cannot
        // satisfy, which otherwise fails at startup with a far less obvious
        // message.
        assertThat(DotenvEnvironmentPostProcessor.class.getDeclaredConstructors())
                .anySatisfy(ctor -> assertThat(ctor.getParameterTypes())
                        .containsExactly(DeferredLogFactory.class));
    }

    // -------------------------------------------------------------- precedence

    @Test
    void aRealEnvironmentVariableWinsOverTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(".env");
        Files.writeString(file, "LLM_MODEL=from-file\nLITREVIEW_DB_USER=from-file\n");

        Map<String, String> parsed = DotenvEnvironmentPostProcessor.parse(file, NO_OP);

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("LLM_MODEL", "from-real-env")));

        // Mirror what postProcessEnvironment does: skip keys the environment
        // already defines, so an OS env var / -D flag wins over the file.
        Map<String, Object> effective = parsed.entrySet().stream()
                .filter(e -> environment.getProperty(e.getKey()) == null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(effective)
                .doesNotContainKey("LLM_MODEL")
                .containsEntry("LITREVIEW_DB_USER", "from-file");
    }

    @Test
    void locateHonoursTheDotenvPathOverrideWhenSet() {
        // The override comes from a real environment variable, so this can only
        // be exercised when one is present.
        String override = System.getenv(DotenvEnvironmentPostProcessor.PATH_OVERRIDE_ENV);
        assumeTrue(override != null && !override.isBlank(), "DOTENV_PATH not set");

        assertThat(DotenvEnvironmentPostProcessor.locate(NO_OP)).isEqualTo(Path.of(override));
    }

    // -------------------------------------------------------------- end to end

    @Test
    void postProcessEnvironmentInstallsTheRealDotenvAsAPropertySource() {
        // The whole point of the class, and the only test that exercises
        // locate() and the property-source insertion together. Surefire's
        // working directory is the project root, so locate() finds the
        // developer's actual .env.
        Path real = DotenvEnvironmentPostProcessor.locate(NO_OP);
        assumeTrue(real != null, "no .env in the working tree");

        Map<String, String> declared = DotenvEnvironmentPostProcessor.parse(real, NO_OP);
        assumeTrue(!declared.isEmpty(), ".env declares nothing");

        StandardEnvironment environment = new StandardEnvironment();
        // Provide one key the "real" way so precedence is exercised against a
        // live environment rather than only in the filtered copy.
        String firstKey = declared.keySet().iterator().next();
        environment.getPropertySources().addFirst(
                new MapPropertySource("test", Map.of(firstKey, "from-real-env")));

        processor().postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources()
                .contains(DotenvEnvironmentPostProcessor.SOURCE_NAME)).isTrue();
        // the real environment variable still wins for the key it defined
        assertThat(environment.getProperty(firstKey)).isEqualTo("from-real-env");
    }
}
