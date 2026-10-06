package com.justcommit.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTest {

    private final ApplicationModules modules = ApplicationModules.of(JustCommitApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void printsDetectedModules() {
        var moduleIdentifiers = StreamSupport.stream(modules.spliterator(), false)
                .map(module -> module.getIdentifier().toString())
                .sorted()
                .toList();

        System.out.println("Detected Spring Modulith modules: " + moduleIdentifiers);

        assertThat(moduleIdentifiers).containsExactly(
                "common",
                "market",
                "member",
                "notification",
                "payment",
                "product",
                "settlement");
    }
}
