package com.pulseink.sandbox.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

public final class DotenvEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "pulseinkRootDotenv";
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Path startDirectory;

    public DotenvEnvironmentPostProcessor() {
        this(Path.of(System.getProperty("user.dir", ".")));
    }

    DotenvEnvironmentPostProcessor(Path startDirectory) {
        this.startDirectory = startDirectory.toAbsolutePath().normalize();
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        postProcessEnvironment(environment);
    }

    void postProcessEnvironment(ConfigurableEnvironment environment) {
        var dotenv = findNearestDotenv();
        if (dotenv.isEmpty()) {
            return;
        }

        var values = readValues(dotenv.get());
        addSpringAliases(values);
        if (values.isEmpty()) {
            return;
        }

        var propertySources = environment.getPropertySources();
        var propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    propertySource);
        } else if (propertySources.contains(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(
                    StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    private Optional<Path> findNearestDotenv() {
        var directory = Files.isDirectory(startDirectory)
                ? startDirectory
                : startDirectory.getParent();
        while (directory != null) {
            var candidate = directory.resolve(".env");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    private static Map<String, Object> readValues(Path dotenv) {
        var values = new LinkedHashMap<String, Object>();
        try {
            var lines = Files.readAllLines(dotenv, StandardCharsets.UTF_8);
            for (var index = 0; index < lines.size(); index++) {
                parseLine(lines.get(index), index + 1).ifPresent(entry ->
                        values.put(entry.getKey(), entry.getValue()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read local .env file", exception);
        }
        return values;
    }

    private static Optional<Map.Entry<String, Object>> parseLine(
            String source, int lineNumber) {
        var line = source.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return Optional.empty();
        }

        var separator = line.indexOf('=');
        if (separator <= 0) {
            throw invalidEntry(lineNumber, "<unknown>");
        }

        var key = line.substring(0, separator).strip();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw invalidEntry(lineNumber, key.isEmpty() ? "<empty>" : key);
        }

        var value = line.substring(separator + 1).strip();
        if (value.startsWith("\"") || value.startsWith("'")) {
            var quote = value.charAt(0);
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote) {
                throw invalidEntry(lineNumber, key);
            }
            value = value.substring(1, value.length() - 1);
        }
        return Optional.of(Map.entry(key, value));
    }

    private static IllegalArgumentException invalidEntry(int lineNumber, String key) {
        return new IllegalArgumentException(
                "Invalid .env entry at line " + lineNumber + " (key: " + key + ")");
    }

    private static void addSpringAliases(Map<String, Object> values) {
        var activeProfiles = values.get("SPRING_PROFILES_ACTIVE");
        if (activeProfiles != null) {
            values.putIfAbsent("spring.profiles.active", activeProfiles);
        }
    }
}
