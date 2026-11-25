package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/** Parser del sobre de Business v1: { eventId, type, schemaVersion, huariqueId, aggregateVersion, occurredAt, data }. */
class BusinessEventParserTest {

    private final BusinessEventParser parser = new BusinessEventParser(
            new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Snapshot de Business: lleva más campos de los que Radar usa (ownerId, name, images...). */
    private static final String SNAPSHOT_OPEN = """
            { "id": "h-1", "ownerId": "owner-1", "name": "El Huarique de Doña Rosa", "address": "Av. Larco 123",
              "cuisineType": "CRIOLLA", "priceRange": { "minimum": 15.00, "maximum": 45.50 },
              "location": { "latitude": -12.1219, "longitude": -77.0297 }, "openStatus": "OPEN",
              "tags": ["casero"], "openingHours": [], "images": [], "version": 4 }""";

    private static String envelope(String type, long aggregateVersion, String data) {
        return """
                { "eventId": "e-1", "type": "%s", "schemaVersion": 1, "huariqueId": "h-1", "aggregateVersion": %d,
                  "occurredAt": "2026-11-24T20:00:00.123Z", "data": %s }""".formatted(type, aggregateVersion, data);
    }

    @Test
    void parsesTheEnvelopeAndMapsARegisteredEvent() {
        BusinessEventEnvelope envelope = parser.parse(bytes(envelope("HuariqueRegistrado", 4, SNAPSHOT_OPEN)));

        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.type()).isEqualTo("HuariqueRegistrado");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
        assertThat(envelope.aggregateVersion()).isEqualTo(4L);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-11-24T20:00:00.123Z"));

        HuariqueRegisteredEvent event = parser.toRegistered(envelope);
        assertThat(event).isEqualTo(new HuariqueRegisteredEvent("e-1", "h-1", 4,
                Instant.parse("2026-11-24T20:00:00.123Z"), -12.1219, -77.0297, AvailabilityStatus.ACTIVE));
    }

    @Test
    void mapsTheOtherTwoTypes() {
        String closed = SNAPSHOT_OPEN.replace("\"OPEN\"", "\"CLOSED\"").replace("-12.1219", "-12.15");

        assertThat(parser.toUpdated(parser.parse(bytes(envelope("PerfilDelHuariqueActualizado", 5, closed)))))
                .satisfies(event -> {
                    assertThat(event.status()).isEqualTo(AvailabilityStatus.INACTIVE);
                    assertThat(event.latitude()).isEqualTo(-12.15);
                    assertThat(event.version()).isEqualTo(5);
                });
        OpeningStatusChangedEvent opening = parser.toOpeningStatusChanged(
                parser.parse(bytes(envelope("EstadoAbiertoCerradoCambiado", 6, closed))));
        assertThat(opening.status()).isEqualTo(AvailabilityStatus.INACTIVE);
        assertThat(opening.huariqueId()).isEqualTo("h-1");
        assertThat(opening.version()).isEqualTo(6);
    }

    @Test
    void openIsActiveAndClosedIsInactiveIgnoringCase() {
        assertThat(parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("EstadoAbiertoCerradoCambiado", 1, "{ \"openStatus\": \"OPEN\" }")))).status())
                .isEqualTo(AvailabilityStatus.ACTIVE);
        assertThat(parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("EstadoAbiertoCerradoCambiado", 1, "{ \"openStatus\": \"closed\" }")))).status())
                .isEqualTo(AvailabilityStatus.INACTIVE);
    }

    @Test
    void aStatusEventDoesNotNeedLocation() {
        OpeningStatusChangedEvent event = parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("EstadoAbiertoCerradoCambiado", 2, "{ \"openStatus\": \"OPEN\" }"))));

        assertThat(event.status()).isEqualTo(AvailabilityStatus.ACTIVE);
    }

    @Test
    void aggregateVersionZeroIsValid() {
        BusinessEventEnvelope envelope = parser.parse(bytes(envelope("HuariqueRegistrado", 0, SNAPSHOT_OPEN)));

        assertThat(parser.requireIdentity(envelope)).isZero();
        assertThat(parser.toRegistered(envelope).version()).isZero();
    }

    @Test
    void unknownFieldsAreIgnored() {
        BusinessEventEnvelope envelope = parser.parse(bytes("""
                { "eventId": "e-1", "type": "MenuCreado", "schemaVersion": 1, "huariqueId": "h-1",
                  "aggregateVersion": 0, "campoNuevo": 1, "data": { "categories": [1, 2, 3] } }"""));

        assertThat(envelope.type()).isEqualTo("MenuCreado");
    }

    @Test
    void aMessageWithoutIdentityFieldsStillParsesSoUnknownTypesCanBeIgnored() {
        assertThat(parser.parse(bytes("{ \"type\": \"MenuActualizado\" }")).type()).isEqualTo("MenuActualizado");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{{{ no es json", "[]", "\"texto\"", "null", "{}", "{ \"type\": \"  \" }",
            "{ \"type\": \"X\", \"occurredAt\": \"ayer\" }"})
    void unreadableEnvelopesAreMalformed(String body) {
        assertThatThrownBy(() -> parser.parse(bytes(body))).isInstanceOf(MalformedBusinessEventException.class);
    }

    @Test
    void aNullBodyIsMalformed() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(MalformedBusinessEventException.class);
    }

    @Test
    void errorMessagesDoNotEchoTheMessageContent() {
        assertThatThrownBy(() -> parser.parse(bytes("{{{ clave-secreta-123")))
                .isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageNotContaining("clave-secreta-123");
    }

    @Test
    void aMissingDataIsMalformed() {
        BusinessEventEnvelope envelope = parser.parse(bytes("""
                { "eventId": "e-1", "type": "HuariqueRegistrado", "schemaVersion": 1, "huariqueId": "h-1",
                  "aggregateVersion": 1 }"""));

        assertThatThrownBy(() -> parser.toRegistered(envelope)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("data");
    }

    @Test
    void missingLocationOrCoordinatesAreMalformedForTheTypesThatNeedThem() {
        BusinessEventEnvelope noLocation = parser.parse(bytes(envelope("PerfilDelHuariqueActualizado", 1,
                "{ \"openStatus\": \"OPEN\" }")));
        BusinessEventEnvelope noLongitude = parser.parse(bytes(envelope("HuariqueRegistrado", 1,
                "{ \"location\": { \"latitude\": -12.1 }, \"openStatus\": \"OPEN\" }")));

        assertThatThrownBy(() -> parser.toUpdated(noLocation)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("location");
        assertThatThrownBy(() -> parser.toRegistered(noLongitude)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void anUnknownOrMissingOpenStatusIsMalformed() {
        // ACTIVE/INACTIVE eran el formato anterior de Radar: Business solo publica OPEN y CLOSED.
        BusinessEventEnvelope unknown = parser.parse(bytes(envelope("EstadoAbiertoCerradoCambiado", 1,
                "{ \"openStatus\": \"ACTIVE\" }")));
        BusinessEventEnvelope missing = parser.parse(bytes(envelope("EstadoAbiertoCerradoCambiado", 1, "{ }")));

        assertThatThrownBy(() -> parser.toOpeningStatusChanged(unknown))
                .isInstanceOf(MalformedBusinessEventException.class).hasMessageContaining("openStatus");
        assertThatThrownBy(() -> parser.toOpeningStatusChanged(missing))
                .isInstanceOf(MalformedBusinessEventException.class).hasMessageContaining("openStatus");
    }

    @Test
    void identityFieldsAreRequiredForTheTypesRadarProcesses() {
        BusinessEventEnvelope noVersion = parser.parse(bytes("""
                { "eventId": "e-1", "type": "EstadoAbiertoCerradoCambiado", "schemaVersion": 1, "huariqueId": "h-1",
                  "data": { "openStatus": "OPEN" } }"""));
        BusinessEventEnvelope negativeVersion = parser.parse(bytes(envelope("EstadoAbiertoCerradoCambiado", -1,
                "{ \"openStatus\": \"OPEN\" }")));
        BusinessEventEnvelope noHuarique = parser.parse(bytes("""
                { "eventId": "e-1", "type": "EstadoAbiertoCerradoCambiado", "schemaVersion": 1,
                  "aggregateVersion": 1, "data": { "openStatus": "OPEN" } }"""));

        assertThatThrownBy(() -> parser.requireIdentity(noVersion)).isInstanceOf(MalformedBusinessEventException.class);
        assertThatThrownBy(() -> parser.requireIdentity(negativeVersion)).isInstanceOf(MalformedBusinessEventException.class);
        assertThatThrownBy(() -> parser.requireIdentity(noHuarique)).isInstanceOf(MalformedBusinessEventException.class);
    }

    @Test
    void onlySchemaVersion1IsSupported() {
        BusinessEventEnvelope v2 = parser.parse(bytes(envelope("HuariqueRegistrado", 1, SNAPSHOT_OPEN)
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")));
        BusinessEventEnvelope none = parser.parse(bytes(envelope("HuariqueRegistrado", 1, SNAPSHOT_OPEN)
                .replace("\"schemaVersion\": 1,", "")));

        assertThatThrownBy(() -> parser.requireIdentity(v2)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> parser.requireIdentity(none)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void onlyTheThreeBusinessEventsThatRadarUsesAreRecognized() {
        assertThat(BusinessEventType.fromEventType("HuariqueRegistrado")).contains(BusinessEventType.HUARIQUE_REGISTERED);
        assertThat(BusinessEventType.fromEventType("PerfilDelHuariqueActualizado"))
                .contains(BusinessEventType.HUARIQUE_UPDATED);
        assertThat(BusinessEventType.fromEventType("EstadoAbiertoCerradoCambiado"))
                .contains(BusinessEventType.OPENING_STATUS_CHANGED);
        assertThat(BusinessEventType.fromEventType("MenuCreado")).isEmpty();
        assertThat(BusinessEventType.fromEventType("MenuActualizado")).isEmpty();
        // Los nombres en inglés eran el contrato anterior: ya no se reconocen.
        assertThat(BusinessEventType.fromEventType("HuariqueRegistered")).isEmpty();
        assertThat(BusinessEventType.fromEventType("huariqueregistrado")).isEmpty();
        assertThat(BusinessEventType.fromEventType(null)).isEmpty();
    }

    @Test
    void routingKeysAreBusinessDotType() {
        assertThat(BusinessEventType.HUARIQUE_REGISTERED.routingKey()).isEqualTo("business.HuariqueRegistrado");
        assertThat(BusinessEventType.HUARIQUE_UPDATED.routingKey()).isEqualTo("business.PerfilDelHuariqueActualizado");
        assertThat(BusinessEventType.OPENING_STATUS_CHANGED.routingKey())
                .isEqualTo("business.EstadoAbiertoCerradoCambiado");
    }
}
