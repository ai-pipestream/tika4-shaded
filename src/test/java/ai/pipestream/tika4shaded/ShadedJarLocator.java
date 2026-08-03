package ai.pipestream.tika4shaded;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the shaded uber-jar produced by the {@code shadowJar} task.
 *
 * <p>The path arrives as the {@code tika4shaded.jar} system property, wired in
 * {@code build.gradle} from {@code tasks.shadowJar.archiveFile}. Both failure
 * modes (property unset, file missing) throw, so a misconfigured build fails
 * the test run instead of silently skipping the only assertions that actually
 * exercise the relocation.</p>
 */
final class ShadedJarLocator {

    static final String JAR_PROPERTY = "tika4shaded.jar";

    private ShadedJarLocator() {
    }

    static Path shadedJar() {
        String configured = System.getProperty(JAR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "System property '" + JAR_PROPERTY + "' is not set. The shaded-jar tests cannot run "
                            + "without the artifact produced by the shadowJar task; see the `test` block in "
                            + "build.gradle.");
        }
        Path jar = Paths.get(configured);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(
                    "Shaded jar '" + jar.toAbsolutePath() + "' does not exist. The `test` task must run after "
                            + "`shadowJar`.");
        }
        return jar;
    }
}
