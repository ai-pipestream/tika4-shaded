package ai.pipestream.tika4shaded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Parses real documents through the classes INSIDE the shaded jar.
 *
 * <p>The jar is loaded by a {@link URLClassLoader} whose parent is the platform
 * class loader, so nothing from the test compile classpath (which still carries
 * the unrelocated Tika) can leak in. Everything is therefore driven by
 * reflection against {@code ai.pipestream.shaded.tika.Tika} — if the relocation
 * broke a reflective lookup, a resource path, or a {@code META-INF/services}
 * registration, these parses fail where a compile-classpath test would happily
 * pass.</p>
 *
 * <p>The Jackson tests below are the reason the {@code com.fasterxml.jackson}
 * relocation is safe to make: Tika 4 loads Jackson by name through
 * {@code Class.forName}, so absence of the origin package from the jar proves
 * nothing on its own.</p>
 */
class ShadedJarParseTest {

    private static final String SHADED_TIKA = "ai.pipestream.shaded.tika.Tika";

    private static URLClassLoader shadedLoader;
    private static Object tika;
    private static Method parseToString;
    private static Method detect;
    private static Path fixtureDir;

    @BeforeAll
    static void bootShadedTika() throws Exception {
        URL jarUrl = ShadedJarLocator.shadedJar().toUri().toURL();
        shadedLoader = new URLClassLoader("shaded-tika", new URL[]{jarUrl},
                ClassLoader.getPlatformClassLoader());

        Class<?> tikaClass = shadedLoader.loadClass(SHADED_TIKA);
        assertThat(tikaClass.getClassLoader())
                .as("Tika must come from the shaded jar, not from the test classpath")
                .isSameAs(shadedLoader);

        tika = tikaClass.getDeclaredConstructor().newInstance();
        parseToString = tikaClass.getMethod("parseToString", java.io.File.class);
        detect = tikaClass.getMethod("detect", java.io.File.class);

        fixtureDir = Files.createTempDirectory("tika4-shaded-fixtures");
        copyFixture("sample.md");
        copyFixture("sample.pdf");
    }

    @AfterAll
    static void shutdown() throws IOException {
        if (shadedLoader != null) {
            shadedLoader.close();
        }
        if (fixtureDir != null && Files.isDirectory(fixtureDir)) {
            try (var paths = Files.walk(fixtureDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort temp cleanup
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("the shaded jar can be used standalone, with no unrelocated Tika in sight")
    void shadedClassLoaderHasNoOriginalTika() {
        assertThatCode(() -> shadedLoader.loadClass(SHADED_TIKA)).doesNotThrowAnyException();
        assertThat(shadedLoader.findResource("org/apache/tika/Tika.class"))
                .as("unrelocated Tika inside the jar")
                .isNull();
        assertThat(shadedLoader.findResource("ai/pipestream/shaded/tika/Tika.class"))
                .as("relocated Tika inside the jar")
                .isNotNull();
    }

    @Test
    @DisplayName("markdown parses end to end through the relocated commonmark copy")
    void parsesMarkdown() throws Exception {
        Path markdown = fixtureDir.resolve("sample.md");

        assertThat(invokeDetect(markdown)).isEqualTo("text/markdown");

        String text = invokeParse(markdown);
        assertThat(text).isNotBlank();
        assertThat(text)
                .contains("Shaded Tika markdown fixture")
                .contains("bullet alpha")
                .contains("bullet bravo")
                .contains("A quoted line about relocation.")
                .contains("a link and some bold text.");
        // Markdown syntax must have been consumed by the parser, not passed through raw.
        assertThat(text).doesNotContain("# Shaded Tika markdown fixture");
        assertThat(text).doesNotContain("**bold**");
    }

    @Test
    @DisplayName("PDF parses end to end through the bundled PDFBox stack")
    void parsesPdf() throws Exception {
        Path pdf = fixtureDir.resolve("sample.pdf");

        assertThat(invokeDetect(pdf)).isEqualTo("application/pdf");

        String text = invokeParse(pdf);
        assertThat(text).isNotBlank();
        assertThat(text)
                .contains("Hello from the shaded Tika PDF fixture.")
                .contains("Relocation guard: ai.pipestream.shaded.tika");
    }

    @Test
    @DisplayName("Tika's reflective Jackson lookup resolves to the relocated name")
    void jacksonIsReachableUnderTheRelocatedName() throws Exception {
        // Tika 4 reaches Jackson through Class.forName on a String constant, not an
        // import, so the relocation only holds if shadow rewrote that constant too.
        // ConfigDeserializer.isJacksonAvailable() is that lookup's public verdict: it
        // is false the moment the constant and the bundled package disagree.
        Class<?> configDeserializer =
                shadedLoader.loadClass("ai.pipestream.shaded.tika.config.ConfigDeserializer");
        Object available = withContextClassLoader(
                () -> configDeserializer.getMethod("isJacksonAvailable").invoke(null));
        assertThat(available)
                .as("ConfigDeserializer.isJacksonAvailable() inside the shaded class loader")
                .isEqualTo(Boolean.TRUE);

        Class<?> mapper = shadedLoader.loadClass("ai.pipestream.shaded.jackson.databind.ObjectMapper");
        assertThat(mapper.getClassLoader())
                .as("the ObjectMapper Tika finds must be the shaded one")
                .isSameAs(shadedLoader);
        assertThat(shadedLoader.findResource("com/fasterxml/jackson/databind/ObjectMapper.class"))
                .as("unrelocated Jackson inside the jar")
                .isNull();
    }

    @Test
    @DisplayName("a component deserializes its JSON config through the relocated Jackson")
    void jsonConfigDeserializesThroughRelocatedJackson() throws Exception {
        // OverrideEncodingDetector is one of ~29 Tika components whose JsonConfig
        // constructor routes through ConfigDeserializer.buildConfig, i.e. through
        // Jackson databind. Driving it end to end proves the relocated copy actually
        // deserializes, rather than merely being present on the classpath.
        Class<?> jsonConfig = shadedLoader.loadClass("ai.pipestream.shaded.tika.config.JsonConfig");
        Object config = Proxy.newProxyInstance(shadedLoader, new Class<?>[]{jsonConfig},
                (proxy, method, args) -> "json".equals(method.getName())
                        ? "{\"charset\":\"ISO-8859-1\"}"
                        : null);

        Class<?> detector =
                shadedLoader.loadClass("ai.pipestream.shaded.tika.detect.OverrideEncodingDetector");
        Method getCharset = detector.getMethod("getCharset");

        Object configured = withContextClassLoader(
                () -> detector.getConstructor(jsonConfig).newInstance(config));
        assertThat(withContextClassLoader(() -> getCharset.invoke(configured)))
                .as("charset deserialized from JSON by the relocated Jackson")
                .hasToString("ISO-8859-1");

        // The no-arg constructor skips Jackson entirely, so a different value here is
        // what makes the assertion above evidence of deserialization and not of a default.
        Object defaulted = withContextClassLoader(
                () -> detector.getConstructor().newInstance());
        assertThat(withContextClassLoader(() -> getCharset.invoke(defaulted)))
                .as("default charset, reached without Jackson")
                .hasToString("UTF-8");
    }

    private static String invokeParse(Path file) throws Exception {
        return (String) withContextClassLoader(() -> parseToString.invoke(tika, file.toFile()));
    }

    private static String invokeDetect(Path file) throws Exception {
        return (String) withContextClassLoader(() -> detect.invoke(tika, file.toFile()));
    }

    /**
     * Tika's own service loader consults the thread context class loader first; without
     * this the parser registry would be resolved against the test classpath.
     */
    private static Object withContextClassLoader(ReflectiveCall call) throws Exception {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(shadedLoader);
        try {
            return call.run();
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    private static void copyFixture(String name) throws IOException {
        try (InputStream in = ShadedJarParseTest.class.getClassLoader()
                .getResourceAsStream("fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture fixtures/" + name);
            }
            Files.copy(in, fixtureDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object run() throws Exception;
    }
}
