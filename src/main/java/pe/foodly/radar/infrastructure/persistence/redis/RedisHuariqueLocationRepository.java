package pe.foodly.radar.infrastructure.persistence.redis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

/**
 * Implementa {@link IHuariqueLocationRepository} con Spring Data Redis (Lettuce) (5.2.4, 5.2.7.2).
 *
 * <ul>
 *   <li>Hash {@code radar:huarique:{huariqueId}} con {@code lat}, {@code lng}, {@code h3Cell},
 *       {@code status}, {@code updatedAt} y {@code version}.</li>
 *   <li>Set {@code radar:cell:{h3Index}} con los huariqueId de esa celda.</li>
 * </ul>
 *
 * <p>El guardado y el borrado son atómicos: se ejecutan como un script Lua, que Redis corre sin
 * intercalar otros comandos. El script lee la celda anterior del hash, así que asume un Redis
 * standalone (Upstash lo es); en modo cluster las claves del set viejo no estarían declaradas.
 *
 * <p>La {@code version} es una concesión de infraestructura (no forma parte de la interfaz del dominio):
 * el listener de Business la usa para descartar eventos repetidos o desordenados.
 */
@Repository
public class RedisHuariqueLocationRepository implements IHuariqueLocationRepository, HuariqueVersionStore {

    public static final String HUARIQUE_KEY_PREFIX = "radar:huarique:";
    public static final String CELL_KEY_PREFIX = "radar:cell:";

    static final String FIELD_LAT = "lat";
    static final String FIELD_LNG = "lng";
    static final String FIELD_H3_CELL = "h3Cell";
    static final String FIELD_STATUS = "status";
    static final String FIELD_UPDATED_AT = "updatedAt";
    static final String FIELD_VERSION = "version";

    private static final Logger log = LoggerFactory.getLogger(RedisHuariqueLocationRepository.class);

    /**
     * KEYS[1] = hash del huarique, KEYS[2] = set de la celda nueva.
     * ARGV: 1 id, 2 lat, 3 lng, 4 h3Cell, 5 status, 6 updatedAt, 7 version (vacío = conservar), 8 prefijo de celdas.
     * Si la celda cambió, quita el id del set viejo, escribe el hash y lo agrega al set nuevo.
     */
    private static final RedisScript<Long> UPSERT_SCRIPT = new DefaultRedisScript<>("""
            local oldCell = redis.call('HGET', KEYS[1], 'h3Cell')
            if oldCell and oldCell ~= ARGV[4] then
              redis.call('SREM', ARGV[8] .. oldCell, ARGV[1])
            end
            redis.call('HSET', KEYS[1], 'lat', ARGV[2], 'lng', ARGV[3], 'h3Cell', ARGV[4],
                       'status', ARGV[5], 'updatedAt', ARGV[6])
            if ARGV[7] ~= '' then
              redis.call('HSET', KEYS[1], 'version', ARGV[7])
            end
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    /** KEYS[1] = hash del huarique. ARGV: 1 id, 2 prefijo de celdas. */
    private static final RedisScript<Long> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            local oldCell = redis.call('HGET', KEYS[1], 'h3Cell')
            if oldCell then
              redis.call('SREM', ARGV[2] .. oldCell, ARGV[1])
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    /** KEYS[1] = hash del huarique, ARGV[1] = versión. Solo escribe si el hash existe. */
    private static final RedisScript<Long> WRITE_VERSION_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              redis.call('HSET', KEYS[1], 'version', ARGV[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisHuariqueLocationRepository(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis);
        this.clock = Objects.requireNonNull(clock);
    }

    public static String huariqueKey(HuariqueId id) {
        return HUARIQUE_KEY_PREFIX + id.value();
    }

    public static String cellKey(H3Cell cell) {
        return CELL_KEY_PREFIX + cell.index();
    }

    /** Upsert idempotente. Conserva la {@code version} que ya tuviera el hash. */
    @Override
    public void save(HuariqueLocation location) {
        upsert(location, "");
    }

    /** Upsert idempotente que además escribe la {@code version} del evento que originó el cambio. */
    public void saveWithVersion(HuariqueLocation location, long version) {
        if (version < 0) {
            throw new IllegalArgumentException("La versión no puede ser negativa.");
        }
        upsert(location, Long.toString(version));
    }

    /** Versión almacenada en el hash, o vacío si el huarique no existe o aún no tiene versión. */
    @Override
    public Optional<Long> findVersion(HuariqueId id) {
        Object value = redis.opsForHash().get(huariqueKey(id), FIELD_VERSION);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.toString()));
        } catch (NumberFormatException e) {
            log.warn("La versión almacenada del huarique {} no es numérica; se ignora.", id.value());
            return Optional.empty();
        }
    }

    /** Escribe solo la versión, sin tocar el resto del hash. No crea el hash si no existe. */
    @Override
    public void writeVersion(HuariqueId id, long version) {
        if (version < 0) {
            throw new IllegalArgumentException("La versión no puede ser negativa.");
        }
        redis.execute(WRITE_VERSION_SCRIPT, List.of(huariqueKey(id)), Long.toString(version));
    }

    @Override
    public void remove(HuariqueId id) {
        Objects.requireNonNull(id);
        redis.execute(REMOVE_SCRIPT, List.of(huariqueKey(id)), id.value(), CELL_KEY_PREFIX);
    }

    @Override
    public Optional<HuariqueLocation> findById(HuariqueId id) {
        Objects.requireNonNull(id);
        Map<Object, Object> hash = redis.opsForHash().entries(huariqueKey(id));
        return toLocation(id.value(), hash);
    }

    /** Dos viajes a Redis en pipeline: los sets de todas las celdas y luego los hashes de los ids. */
    @Override
    public List<HuariqueLocation> findByCells(Set<H3Cell> cells) {
        Objects.requireNonNull(cells);
        if (cells.isEmpty()) {
            return List.of();
        }

        List<String> cellKeys = cells.stream().map(RedisHuariqueLocationRepository::cellKey).toList();
        List<Object> membersPerCell = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (String key : cellKeys) {
                    operations.opsForSet().members(key);
                }
                return null;
            }
        });

        Set<String> ids = new LinkedHashSet<>();
        for (Object members : membersPerCell) {
            if (members instanceof Set<?> set) {
                for (Object id : set) {
                    ids.add(id.toString());
                }
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        List<String> orderedIds = new ArrayList<>(ids);
        List<Object> hashes = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (String id : orderedIds) {
                    operations.opsForHash().entries(HUARIQUE_KEY_PREFIX + id);
                }
                return null;
            }
        });

        List<HuariqueLocation> locations = new ArrayList<>(orderedIds.size());
        for (int i = 0; i < orderedIds.size(); i++) {
            Object hash = hashes.get(i);
            if (hash instanceof Map<?, ?> map) {
                toLocation(orderedIds.get(i), map).ifPresent(locations::add);
            }
        }
        return locations;
    }

    private void upsert(HuariqueLocation location, String version) {
        Objects.requireNonNull(location);
        GeoCoordinates coordinates = location.getCoordinates();
        redis.execute(UPSERT_SCRIPT,
                List.of(huariqueKey(location.getHuariqueId()), cellKey(location.getH3Cell())),
                location.getHuariqueId().value(),
                Double.toString(coordinates.latitude()),
                Double.toString(coordinates.longitude()),
                location.getH3Cell().index(),
                location.getStatus().name(),
                Long.toString(clock.millis()),
                version,
                CELL_KEY_PREFIX);
    }

    /** Un hash incompleto o corrupto no debe romper toda la búsqueda: se registra y se omite. */
    private Optional<HuariqueLocation> toLocation(String id, Map<?, ?> hash) {
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        hash.forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        try {
            return Optional.of(new HuariqueLocation(
                    new HuariqueId(id),
                    new GeoCoordinates(Double.parseDouble(required(fields, FIELD_LAT)),
                            Double.parseDouble(required(fields, FIELD_LNG))),
                    new H3Cell(required(fields, FIELD_H3_CELL)),
                    AvailabilityStatus.valueOf(required(fields, FIELD_STATUS))));
        } catch (RuntimeException e) {
            log.warn("El hash del huarique {} es inválido ({}); se omite.", id, e.getMessage());
            return Optional.empty();
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalStateException("falta el campo " + name);
        }
        return value;
    }
}
