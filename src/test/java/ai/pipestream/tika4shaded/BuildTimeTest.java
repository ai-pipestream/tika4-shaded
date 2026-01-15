package ai.pipestream.tika4shaded;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify that build-time.txt is present and contains a valid timestamp.
 */
public class BuildTimeTest {

    @Test
    public void testBuildTimeFileExists() {
        // Verify that build-time.txt is accessible from the classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("build-time.txt")) {
            assertNotNull(is, "build-time.txt should be present in the classpath");
        } catch (Exception e) {
            fail("Failed to load build-time.txt: " + e.getMessage());
        }
    }

    @Test
    public void testBuildTimeFileContainsValidTimestamp() throws Exception {
        // Load build-time.txt from classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("build-time.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            
            assertNotNull(is, "build-time.txt should be present in the classpath");

            // Read the timestamp
            String timestamp = reader.readLine();
            assertNotNull(timestamp, "build-time.txt should contain a timestamp");
            assertFalse(timestamp.trim().isEmpty(), "build-time.txt should not be empty");

            // Verify the timestamp is in ISO 8601 format and valid
            try {
                Instant instant = Instant.parse(timestamp.trim());
                assertNotNull(instant, "Timestamp should be parseable as ISO 8601");
                
                // Verify the timestamp is not in the future
                Instant now = Instant.now();
                assertTrue(instant.isBefore(now) || instant.equals(now), 
                    "Build timestamp should not be in the future");
                
                // Verify the timestamp is reasonable (not too old - within 10 years)
                Instant tenYearsAgo = now.minusSeconds(10L * 365 * 24 * 60 * 60);
                assertTrue(instant.isAfter(tenYearsAgo), 
                    "Build timestamp should be reasonably recent");
            } catch (DateTimeParseException e) {
                fail("Timestamp should be in valid ISO 8601 format: " + timestamp);
            }
        }
    }
}
