package pe.foodly.radar.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class RedisConfigTest {

    @Test
    void upstashUrlWithTlsKeepsHostPortUserAndPassword() {
        String url = "rediss://default:s3cret@eager-owl-1234.upstash.io:6379";

        RedisStandaloneConfiguration config = RedisConfig.standaloneConfiguration(url);

        assertThat(RedisConfig.isTls(url)).isTrue();
        assertThat(config.getHostName()).isEqualTo("eager-owl-1234.upstash.io");
        assertThat(config.getPort()).isEqualTo(6379);
        assertThat(config.getUsername()).isEqualTo("default");
        assertThat(config.getPassword().get()).isEqualTo("s3cret".toCharArray());
        assertThat(config.getDatabase()).isZero();
    }

    @Test
    void plainLocalUrlHasNoCredentialsAndNoTls() {
        String url = "redis://localhost:6379";

        RedisStandaloneConfiguration config = RedisConfig.standaloneConfiguration(url);

        assertThat(RedisConfig.isTls(url)).isFalse();
        assertThat(config.getHostName()).isEqualTo("localhost");
        assertThat(config.getUsername()).isNull();
        assertThat(config.getPassword().isPresent()).isFalse();
    }

    @Test
    void portDefaultsTo6379() {
        assertThat(RedisConfig.standaloneConfiguration("redis://redis-host").getPort()).isEqualTo(6379);
    }

    @Test
    void databaseComesFromThePath() {
        RedisStandaloneConfiguration config = RedisConfig.standaloneConfiguration("redis://localhost:6380/2");

        assertThat(config.getPort()).isEqualTo(6380);
        assertThat(config.getDatabase()).isEqualTo(2);
    }

    @Test
    void percentEncodedPasswordIsDecoded() {
        RedisStandaloneConfiguration config =
                RedisConfig.standaloneConfiguration("rediss://default:p%40ss%3Aw%2Bd@host:6379");

        assertThat(config.getPassword().get()).isEqualTo("p@ss:w+d".toCharArray());
    }

    @Test
    void connectionFactoryEnablesTlsForRediss() {
        LettuceConnectionFactory tls = (LettuceConnectionFactory) new RedisConfig()
                .redisConnectionFactory("rediss://default:s3cret@host.upstash.io:6379", 2000);
        LettuceConnectionFactory plain = (LettuceConnectionFactory) new RedisConfig()
                .redisConnectionFactory("redis://localhost:6379", 2000);

        assertThat(tls.isUseSsl()).isTrue();
        assertThat(plain.isUseSsl()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "http://localhost:6379", "localhost:6379", "redis://", "redis://localhost/abc"})
    void invalidUrlsAreRejected(String url) {
        assertThatThrownBy(() -> RedisConfig.standaloneConfiguration(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorMessagesNeverLeakThePassword() {
        assertThatThrownBy(() -> RedisConfig.standaloneConfiguration("ftp://default:topsecret@host:6379"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("topsecret");
    }
}
