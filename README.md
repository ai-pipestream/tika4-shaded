# tika4-shaded

A JDK21 Gradle build that takes the latest version of Apache Tika 4 nightly snapshots and shades them into a versioned SNAPSHOT JAR for release.

## Features

This build includes the following Tika 4 components:
- `tika-core` - Core Tika functionality
- `tika-parsers-standard-package` - Standard parsers for common document formats
- `tika-parser-scientific-package` - Extended parsers for scientific document formats
- `tika-parser-ocr-package` - OCR support for images and scanned documents (requires tesseract)

## Requirements

- JDK 21 or later
- Gradle 8.5+ (included via wrapper)

## Building

To build the shaded JAR:

```bash
./gradlew shadowJar
```

The shaded JAR will be created in `build/libs/tika4-shaded-4.0.0-SNAPSHOT.jar`

## Publishing

To publish to a local Maven repository:

```bash
./gradlew publishToMavenLocal
```

## About Shading

This build uses the Shadow plugin to relocate all Tika packages from `org.apache.tika` to `ai.pipestream.shaded.tika` to avoid conflicts with other versions of Tika that may be present in the classpath.

## License

This project follows the Apache License 2.0, consistent with Apache Tika.

