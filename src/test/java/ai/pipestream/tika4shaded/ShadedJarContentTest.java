package ai.pipestream.tika4shaded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the layout of the produced shaded jar.
 *
 * <p>These assertions are the gate that was missing when the unrelocated Apache
 * HttpClient copy shipped and only blew up downstream, in module-parser's
 * {@code OpenApiEndpointIT}, on 2026-07-20. Every relocation configured in
 * {@code build.gradle} is checked here from BOTH sides: the origin package must
 * be completely absent, and the shaded package must be populated. A relocation
 * that is deleted, misspelled or silently stops matching turns this red.</p>
 */
class ShadedJarContentTest {

    private static JarFile jarFile;
    private static List<String> entryNames;

    /**
     * One relocation configured in {@code build.gradle}'s {@code shadowJar} block.
     *
     * @param originPackage  the dotted package that must NOT survive in the jar
     * @param shadedPackage  the dotted package it is rewritten to
     * @param minimumClasses lower bound on how many entries the shaded package must hold,
     *                       so an empty-but-present relocation cannot pass
     */
    record Relocation(String originPackage, String shadedPackage, int minimumClasses) {

        String originPath() {
            return originPackage.replace('.', '/') + "/";
        }

        String shadedPath() {
            return shadedPackage.replace('.', '/') + "/";
        }

        @Override
        public String toString() {
            return originPackage + " -> " + shadedPackage;
        }
    }

    /**
     * Mirrors the {@code relocate} lines in {@code build.gradle}. Add a row here in the
     * same commit as a new relocation.
     */
    static Stream<Relocation> relocations() {
        return Stream.of(
                new Relocation("org.apache.tika", "ai.pipestream.shaded.tika", 1000),
                new Relocation("com.google.protobuf", "ai.pipestream.shaded.protobuf", 100),
                new Relocation("com.google.common", "ai.pipestream.shaded.guava", 1000),
                // Standing regression guard for the 2026-07-20 break: consumers run their
                // own Apache HttpClient (REST Assured / Quarkus) and an unrelocated copy
                // here shadows theirs with a NoSuchMethodError. Do not delete this row.
                new Relocation("org.apache.http", "ai.pipestream.shaded.http", 400),
                // module-parser depends on org.commonmark directly at the catalog version;
                // an unrelocated copy here silently shadows it.
                new Relocation("org.commonmark", "ai.pipestream.shaded.commonmark", 200),
                // Quarkus manages its own Jackson in module-parser; an unrelocated 2.x
                // copy here is the same collision shape as the HttpClient one.
                new Relocation("com.fasterxml.jackson", "ai.pipestream.shaded.jackson", 500));
    }

    @BeforeAll
    static void openJar() throws IOException {
        jarFile = new JarFile(ShadedJarLocator.shadedJar().toFile());
        entryNames = Collections.unmodifiableList(
                jarFile.stream().map(JarEntry::getName).collect(Collectors.toList()));
    }

    @AfterAll
    static void closeJar() throws IOException {
        if (jarFile != null) {
            jarFile.close();
        }
    }

    @Test
    @DisplayName("the shaded jar is a real uber-jar, not an empty shell")
    void jarIsPopulated() {
        assertThat(entryNames)
                .as("entries in %s", jarFile.getName())
                .hasSizeGreaterThan(10_000);
        assertThat(entryNames).contains("META-INF/MANIFEST.MF");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relocations")
    @DisplayName("origin package is fully relocated away")
    void originPackageIsAbsent(Relocation relocation) {
        List<String> survivors = entriesUnder(relocation.originPath());
        assertThat(survivors)
                .as("entries still under %s in the shaded jar (relocation %s is not holding)",
                        relocation.originPath(), relocation)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("relocations")
    @DisplayName("shaded package is populated")
    void shadedPackageIsPopulated(Relocation relocation) {
        assertThat(entriesUnder(relocation.shadedPath()))
                .as("entries under %s (relocation %s produced nothing)",
                        relocation.shadedPath(), relocation)
                .hasSizeGreaterThanOrEqualTo(relocation.minimumClasses());
    }

    @Test
    @DisplayName("markdown support survived relocation")
    void markdownClassesArePresent() {
        assertThat(entryNames).contains(
                "ai/pipestream/shaded/tika/parser/markdown/MarkdownParser.class",
                "ai/pipestream/shaded/tika/sax/ToMarkdownContentHandler.class");
    }

    @Test
    @DisplayName("Tika service registrations exist under the relocated names")
    void tikaServiceFilesAreRelocated() throws IOException {
        List<String> tikaServices = entryNames.stream()
                .filter(name -> name.startsWith("META-INF/services/ai.pipestream.shaded.tika."))
                .collect(Collectors.toList());

        assertThat(tikaServices)
                .as("relocated Tika service files")
                .contains("META-INF/services/ai.pipestream.shaded.tika.parser.Parser",
                        "META-INF/services/ai.pipestream.shaded.tika.detect.Detector");

        List<String> parsers = serviceEntries("META-INF/services/ai.pipestream.shaded.tika.parser.Parser");
        assertThat(parsers)
                .as("registered parser implementations")
                .hasSizeGreaterThan(20)
                .allSatisfy(impl -> assertThat(impl).startsWith("ai.pipestream.shaded.tika."));
        assertThat(parsers).contains("ai.pipestream.shaded.tika.parser.markdown.MarkdownParser");
    }

    @Test
    @DisplayName("no service file name or body references a relocated origin package")
    void serviceFilesCarryNoUnrelocatedReferences() throws IOException {
        List<Relocation> all = relocations().collect(Collectors.toList());
        List<String> offences = new ArrayList<>();

        for (String name : entryNames) {
            if (!name.startsWith("META-INF/services/") || name.endsWith("/")) {
                continue;
            }
            for (Relocation relocation : all) {
                String dotted = relocation.originPackage() + ".";
                if (name.substring("META-INF/services/".length()).startsWith(dotted)) {
                    offences.add(name + " (file name still names " + relocation.originPackage() + ")");
                }
                for (String impl : serviceEntries(name)) {
                    if (impl.startsWith(dotted)) {
                        offences.add(name + " -> " + impl);
                    }
                }
            }
        }

        assertThat(offences)
                .as("META-INF/services entries pointing at classes that relocation removed from the jar")
                .isEmpty();
    }

    /**
     * The one {@code META-INF/services} registration that names classes the jar does not
     * contain and never could.
     *
     * <p>{@code edu.ucar:grib:4.5.5} ships this file with a doubled package segment
     * ({@code ucar.nc2.nc2.grib.grib1.Grib1Iosp}; the real class is
     * {@code ucar.nc2.grib.collection.Grib1Iosp}) and an interface name that does not
     * exist either (the real one is {@code ucar.nc2.iosp.IOServiceProvider}). The shaded
     * jar reproduces the file byte for byte, so this is upstream's defect, not the
     * relocation's - {@code ucar} is not a relocated package. Verified against the
     * dependency jar with {@code unzip -p grib-4.5.5.jar
     * META-INF/services/ucar.nc2.IOServiceProvider}. Excluded here so the assertion keeps
     * describing shading correctness; if this ever shrinks, delete the entry.</p>
     */
    private static final String UPSTREAM_BROKEN_SERVICE_FILE =
            "META-INF/services/ucar.nc2.IOServiceProvider";

    @Test
    @DisplayName("every service registration names a class that exists in the jar or the JDK")
    void serviceFilesNameResolvableClasses() throws IOException {
        // The failure this guards is silent: a registration naming a class relocation
        // moved away only blows up in the consumer's ServiceLoader, never in this build.
        List<String> unresolvable = new ArrayList<>();

        for (String name : entryNames) {
            if (!name.startsWith("META-INF/services/") || name.endsWith("/")
                    || name.equals(UPSTREAM_BROKEN_SERVICE_FILE)) {
                continue;
            }
            String iface = name.substring("META-INF/services/".length());
            if (looksLikeClassName(iface) && !resolvable(iface)) {
                unresolvable.add(name + " (service interface)");
            }
            for (String impl : serviceEntries(name)) {
                String className = impl.split("\\s+")[0];
                if (looksLikeClassName(className) && !resolvable(className)) {
                    unresolvable.add(name + " -> " + className);
                }
            }
        }

        assertThat(unresolvable)
                .as("service registrations naming classes that are in neither the shaded jar nor the JDK")
                .isEmpty();
    }

    /** True when the name is carried by the shaded jar or by the platform class loader. */
    private static boolean resolvable(String className) {
        if (entryNames.contains(className.replace('.', '/') + ".class")) {
            return true;
        }
        // JDK-owned service interfaces (java.security.Provider, javax.imageio.spi.*,
        // java.nio.file.spi.FileTypeDetector) are legitimately absent from an uber-jar.
        try {
            Class.forName(className, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean looksLikeClassName(String candidate) {
        return candidate.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");
    }

    @Test
    @DisplayName("Tika's component index is merged across modules and fully relocated")
    void tikaComponentIndexIsRelocated() throws IOException {
        List<String> indexes = entryNames.stream()
                .filter(name -> name.startsWith("META-INF/tika/") && name.endsWith(".idx"))
                .collect(Collectors.toList());

        assertThat(indexes)
                .as("Tika 4 generated component indexes")
                .contains("META-INF/tika/parsers.idx", "META-INF/tika/detectors.idx",
                        "META-INF/tika/parse-context.idx");
        assertThat(entryNames)
                .as("staging extension used to dodge the exclude of the unmerged originals")
                .noneMatch(name -> name.endsWith(".idxmerged"));

        List<Relocation> all = relocations().collect(Collectors.toList());
        List<String> offences = new ArrayList<>();
        List<String> danglingClasses = new ArrayList<>();

        for (String index : indexes) {
            List<String> lines = serviceEntries(index);
            for (String line : lines) {
                for (Relocation relocation : all) {
                    if (line.contains(relocation.originPackage() + ".")) {
                        offences.add(index + " -> " + line);
                    }
                }
                for (String className : classNamesIn(line)) {
                    if (!entryNames.contains(className.replace('.', '/') + ".class")) {
                        danglingClasses.add(index + " -> " + className);
                    }
                }
            }
        }

        assertThat(offences)
                .as("index entries naming classes that relocation removed from the jar")
                .isEmpty();
        assertThat(danglingClasses)
                .as("index entries naming classes absent from the jar")
                .isEmpty();

        // These indexes are contributed by ~30 Tika modules. shadowJar's EXCLUDE
        // duplicates strategy keeps only the first copy of each name, so an unmerged
        // parsers.idx silently collapses to a handful of entries.
        assertThat(serviceEntries("META-INF/tika/parsers.idx"))
                .as("merged parser index")
                .hasSizeGreaterThan(50);
    }

    /** Pulls the fully qualified class names out of an {@code a=b:key=c} index line. */
    private static List<String> classNamesIn(String line) {
        List<String> found = new ArrayList<>();
        for (String token : line.split("[=:]")) {
            String candidate = token.trim();
            if (candidate.contains(".") && candidate.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
                found.add(candidate);
            }
        }
        return found;
    }

    private static List<String> entriesUnder(String path) {
        return entryNames.stream().filter(name -> name.startsWith(path)).collect(Collectors.toList());
    }

    /**
     * Reads a {@code META-INF/services} file, dropping comments and blank lines the way
     * {@link java.util.ServiceLoader} does.
     */
    private static List<String> serviceEntries(String entryName) throws IOException {
        JarEntry entry = jarFile.getJarEntry(entryName);
        assertThat(entry).as("service file %s", entryName).isNotNull();
        List<String> implementations = new ArrayList<>();
        try (InputStream in = jarFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                String trimmed = (comment >= 0 ? line.substring(0, comment) : line).trim();
                if (!trimmed.isEmpty()) {
                    implementations.add(trimmed);
                }
            }
        }
        return implementations;
    }
}
