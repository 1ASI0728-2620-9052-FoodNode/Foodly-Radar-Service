package pe.foodly.radar.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** El dominio no puede importar Spring, Redis, JPA, RabbitMQ, HTTP ni H3 (convenciones de la spec). */
class DomainIndependenceTest {

    private static final Path DOMAIN_SOURCES = Path.of("src/main/java/pe/foodly/radar/domain");

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.springframework", "io.lettuce", "redis.clients", "com.uber", "jakarta.persistence",
            "jakarta.servlet", "javax.persistence", "com.rabbitmq", "org.apache.http", "java.net.http");

    @Test
    void domainSourcesDoNotImportFrameworksOrInfrastructureLibraries() throws IOException {
        assertThat(DOMAIN_SOURCES).isDirectory();

        try (Stream<Path> files = Files.walk(DOMAIN_SOURCES)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(DomainIndependenceTest::forbiddenImportsOf)
                    .toList();

            assertThat(offenders).isEmpty();
        }
    }

    private static Stream<String> forbiddenImportsOf(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> FORBIDDEN_IMPORT_PREFIXES.stream()
                            .anyMatch(prefix -> line.startsWith("import " + prefix)
                                    || line.startsWith("import static " + prefix)))
                    .map(line -> file.getFileName() + ": " + line);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + file, e);
        }
    }
}
