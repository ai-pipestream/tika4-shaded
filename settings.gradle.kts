rootProject.name = "tika4-shaded"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("tika", "4.0.0-SNAPSHOT")
            
            library("tika-core", "org.apache.tika", "tika-core").versionRef("tika")
            library("tika-parsers-standard", "org.apache.tika", "tika-parsers-standard-package").versionRef("tika")
            library("tika-parser-scientific", "org.apache.tika", "tika-parser-scientific-package").versionRef("tika")
            library("tika-parser-ocr", "org.apache.tika", "tika-parser-ocr-package").versionRef("tika")
        }
    }
}
