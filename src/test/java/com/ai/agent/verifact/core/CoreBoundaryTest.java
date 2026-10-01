package com.ai.agent.verifact.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared core must not depend on any product: products depend on the core, never the reverse. */
class CoreBoundaryTest {

    private static final Pattern PRODUCT_IMPORT = Pattern.compile(
            "^import (static )?com\\.ai\\.agent\\.verifact\\.(news|legal|research|verification|controller|service|feedback)\\.",
            Pattern.MULTILINE);

    @Test
    void coreImportsNoProductPackage() throws IOException {
        Path core = Path.of("src", "main", "java", "com", "ai", "agent", "verifact", "core");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(core)) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return PRODUCT_IMPORT.matcher(Files.readString(p)).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(Path::toString).toList();
        }
        assertThat(offenders).isEmpty();
    }
}
