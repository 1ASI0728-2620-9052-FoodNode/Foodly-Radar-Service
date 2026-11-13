package pe.foodly.radar.infrastructure.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.util.UriUtils;

/**
 * Conexión a Redis (Lettuce). La URL se toma de {@code REDIS_URL}; con el esquema {@code rediss://}
 * (Upstash) se activa TLS. Formato: {@code redis[s]://[usuario:contraseña@]host[:puerto][/base]}.
 */
@Configuration
public class RedisConfig {

    private static final int DEFAULT_PORT = 6379;

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${radar.redis.url}") String url,
            @Value("${radar.redis.command-timeout-ms}") long commandTimeoutMs) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(commandTimeoutMs));
        if (isTls(url)) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(standaloneConfiguration(url), client.build());
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    static boolean isTls(String url) {
        return "rediss".equalsIgnoreCase(parse(url).getScheme());
    }

    static RedisStandaloneConfiguration standaloneConfiguration(String url) {
        URI uri = parse(url);

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                uri.getHost(), uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort());

        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null && !rawUserInfo.isEmpty()) {
            int separator = rawUserInfo.indexOf(':');
            String user = separator >= 0 ? rawUserInfo.substring(0, separator) : rawUserInfo;
            String password = separator >= 0 ? rawUserInfo.substring(separator + 1) : "";
            if (!user.isEmpty()) {
                config.setUsername(UriUtils.decode(user, StandardCharsets.UTF_8));
            }
            if (!password.isEmpty()) {
                config.setPassword(RedisPassword.of(UriUtils.decode(password, StandardCharsets.UTF_8)));
            }
        }

        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            try {
                config.setDatabase(Integer.parseInt(path.substring(1)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("REDIS_URL: la base de datos de la ruta debe ser un número.");
            }
        }
        return config;
    }

    /** Los mensajes de error no incluyen la URL, porque contiene la contraseña. */
    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("REDIS_URL es obligatoria.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("REDIS_URL no es una URL válida.");
        }
        String scheme = uri.getScheme();
        if (!"redis".equalsIgnoreCase(scheme) && !"rediss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("REDIS_URL debe empezar con redis:// o rediss://.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("REDIS_URL debe incluir el host.");
        }
        return uri;
    }
}
