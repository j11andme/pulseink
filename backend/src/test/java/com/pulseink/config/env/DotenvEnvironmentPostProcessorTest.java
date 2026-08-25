package com.pulseink.config.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.bootstrap.BootstrapContext;
import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tempDirectory;

    @Test
    void registersWithTheCurrentSpringBootEnvironmentProcessorFactory() {
        var deferredLogs = new DeferredLogs();
        var bootstrapContext = new DefaultBootstrapContext();
        var argumentResolver = ArgumentResolver.of(DeferredLogFactory.class, deferredLogs)
                .and(ConfigurableBootstrapContext.class, bootstrapContext)
                .and(BootstrapContext.class, bootstrapContext)
                .and(BootstrapRegistry.class, bootstrapContext);
        var processors = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(EnvironmentPostProcessor.class, argumentResolver);

        assertThat(processors)
                .extracting(processor -> processor.getClass().getName())
                .contains(DotenvEnvironmentPostProcessor.class.getName());
    }

    @Test
    void runsBeforeSpringConfigDataSoDotenvCanSelectTheActiveProfile() {
        assertThat(new DotenvEnvironmentPostProcessor(tempDirectory).getOrder())
                .isLessThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void loadsValuesFromTheNearestAncestorDotenv() throws Exception {
        var backendDirectory = Files.createDirectories(tempDirectory.resolve("backend/target/classes"));
        Files.writeString(
                tempDirectory.resolve(".env"),
                """
                # local settings
                PULSEINK_TEST_NAME="Pulse=Ink"
                PULSEINK_TEST_SINGLE='single value'
                """);
        var environment = new StandardEnvironment();

        new DotenvEnvironmentPostProcessor(backendDirectory).postProcessEnvironment(environment);

        assertThat(environment.getProperty("PULSEINK_TEST_NAME")).isEqualTo("Pulse=Ink");
        assertThat(environment.getProperty("PULSEINK_TEST_SINGLE")).isEqualTo("single value");
    }

    @Test
    void systemPropertyHasPriorityOverDotenv() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "PULSEINK_TEST_PRIORITY=dotenv");
        var environment = new StandardEnvironment();
        System.setProperty("PULSEINK_TEST_PRIORITY", "system");

        try {
            new DotenvEnvironmentPostProcessor(tempDirectory)
                    .postProcessEnvironment(environment);

            assertThat(environment.getProperty("PULSEINK_TEST_PRIORITY")).isEqualTo("system");
        } finally {
            System.clearProperty("PULSEINK_TEST_PRIORITY");
        }
    }

    @Test
    void missingDotenvLeavesEnvironmentUntouched() {
        var environment = new StandardEnvironment();

        new DotenvEnvironmentPostProcessor(tempDirectory).postProcessEnvironment(environment);

        assertThat(environment.getProperty("PULSEINK_TEST_MISSING")).isNull();
    }

    @Test
    void exposesSpringProfileAliasBeforeConfigDataIsConsumed() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "SPRING_PROFILES_ACTIVE=local");
        var environment = new StandardEnvironment();

        new DotenvEnvironmentPostProcessor(tempDirectory).postProcessEnvironment(environment);

        assertThat(environment.getProperty("spring.profiles.active")).isEqualTo("local");
    }

    @Test
    void malformedEntryReportsLineWithoutLeakingItsValue() throws Exception {
        Files.writeString(tempDirectory.resolve(".env"), "ARK_API_KEY top-secret-value");

        var thrown = catchThrowable(() -> new DotenvEnvironmentPostProcessor(tempDirectory)
                .postProcessEnvironment(new StandardEnvironment()));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1");
        assertThat(thrown.getMessage()).doesNotContain("top-secret-value");
    }
}
