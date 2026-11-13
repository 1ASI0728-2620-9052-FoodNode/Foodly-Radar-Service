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

class BusinessEventParserTest {

    private final BusinessEventParser parser = new BusinessEventParser(
            new ObjectMapper().findAndRegisterModules().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String envelope(String eventType, String payload) {
        return """
                { "eventId": "e-1", "eventType": "%s", "huariqueId": "h-1", "version": 4,
                  "occurredAt": "2026-11-13T12:00:00Z", "payload": %s }""".formatted(eventType, payload);
    }

    @Test
    void parsesTheEnvelopeAndMapsARegisteredEvent() {
        BusinessEventEnvelope envelope = parser.parse(bytes(envelope("HuariqueRegistered",
                "{ \"latitude\": -12.1219, \"longitude\": -77.0297, \"status\": \"ACTIVE\" }")));

        assertThat(envelope.eventId()).isEqualTo("e-1");
        assertThat(envelope.eventType()).isEqualTo("HuariqueRegistered");
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-11-13T12:00:00Z"));

        HuariqueRegisteredEvent event = parser.toRegistered(envelope);
        assertThat(event).isEqualTo(new HuariqueRegisteredEvent("e-1", "h-1", 4,
                Instant.parse("2026-11-13T12:00:00Z"), -12.1219, -77.0297, AvailabilityStatus.ACTIVE));
    }

    @Test
    void mapsTheOtherThreeTypes() {
        String locationPayload = "{ \"latitude\": -12.15, \"longitude\": -77.02, \"status\": \"INACTIVE\" }";

        assertThat(parser.toUpdated(parser.parse(bytes(envelope("HuariqueUpdated", locationPayload)))).status())
                .isEqualTo(AvailabilityStatus.INACTIVE);
        assertThat(parser.toLocationUpdated(parser.parse(bytes(envelope("LocationUpdated", locationPayload)))).latitude())
                .isEqualTo(-12.15);
        OpeningStatusChangedEvent opening = parser.toOpeningStatusChanged(
                parser.parse(bytes(envelope("OpeningStatusChanged", "{ \"status\": \"INACTIVE\" }"))));
        assertThat(opening.status()).isEqualTo(AvailabilityStatus.INACTIVE);
        assertThat(opening.huariqueId()).isEqualTo("h-1");
    }

    @Test
    void isOpenIsUsedWhenThereIsNoStatus() {
        assertThat(parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("OpeningStatusChanged", "{ \"isOpen\": true }")))).status())
                .isEqualTo(AvailabilityStatus.ACTIVE);
        assertThat(parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("OpeningStatusChanged", "{ \"isOpen\": false }")))).status())
                .isEqualTo(AvailabilityStatus.INACTIVE);
    }

    @Test
    void statusWinsOverIsOpenAndIsCaseInsensitive() {
        assertThat(parser.toOpeningStatusChanged(parser.parse(bytes(
                envelope("OpeningStatusChanged", "{ \"status\": \"inactive\", \"isOpen\": true }")))).status())
                .isEqualTo(AvailabilityStatus.INACTIVE);
    }

    @Test
    void unknownFieldsAreIgnored() {
        BusinessEventEnvelope envelope = parser.parse(bytes("""
                { "eventId": "e-1", "eventType": "MenuUpdated", "huariqueId": "h-1", "version": 1,
                  "campoNuevo": 1, "payload": { "platos": [1, 2, 3] } }"""));

        assertThat(envelope.eventType()).isEqualTo("MenuUpdated");
    }

    @Test
    void aMessageWithoutIdentityFieldsStillParsesSoUnknownTypesCanBeIgnored() {
        assertThat(parser.parse(bytes("{ \"eventType\": \"MenuUpdated\" }")).eventType()).isEqualTo("MenuUpdated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{{{ no es json", "[]", "\"texto\"", "null", "{}", "{ \"eventType\": \"  \" }",
            "{ \"eventType\": \"X\", \"occurredAt\": \"ayer\" }"})
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
    void aMissingPayloadIsMalformed() {
        BusinessEventEnvelope envelope = parser.parse(bytes("""
                { "eventId": "e-1", "eventType": "HuariqueRegistered", "huariqueId": "h-1", "version": 1 }"""));

        assertThatThrownBy(() -> parser.toRegistered(envelope)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void missingCoordinatesAreMalformedForTheTypesThatNeedThem() {
        BusinessEventEnvelope noLng = parser.parse(bytes(envelope("LocationUpdated",
                "{ \"latitude\": -12.1, \"status\": \"ACTIVE\" }")));

        assertThatThrownBy(() -> parser.toLocationUpdated(noLng)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void anUnknownStatusIsMalformed() {
        BusinessEventEnvelope envelope = parser.parse(bytes(envelope("OpeningStatusChanged", "{ \"status\": \"ABIERTO\" }")));

        assertThatThrownBy(() -> parser.toOpeningStatusChanged(envelope))
                .isInstanceOf(MalformedBusinessEventException.class);
    }

    @Test
    void aMissingStatusAndIsOpenIsMalformed() {
        BusinessEventEnvelope envelope = parser.parse(bytes(envelope("HuariqueRegistered",
                "{ \"latitude\": -12.1, \"longitude\": -77.0 }")));

        assertThatThrownBy(() -> parser.toRegistered(envelope)).isInstanceOf(MalformedBusinessEventException.class)
                .hasMessageContaining("status");
    }

    @Test
    void identityFieldsAreRequiredForTheTypesRadarProcesses() {
        BusinessEventEnvelope noVersion = parser.parse(bytes("""
                { "eventId": "e-1", "eventType": "OpeningStatusChanged", "huariqueId": "h-1",
                  "payload": { "status": "ACTIVE" } }"""));
        BusinessEventEnvelope negativeVersion = parser.parse(bytes("""
                { "eventId": "e-1", "eventType": "OpeningStatusChanged", "huariqueId": "h-1", "version": -1,
                  "payload": { "status": "ACTIVE" } }"""));
        BusinessEventEnvelope noHuarique = parser.parse(bytes("""
                { "eventId": "e-1", "eventType": "OpeningStatusChanged", "version": 1,
                  "payload": { "status": "ACTIVE" } }"""));

        assertThatThrownBy(() -> parser.requireIdentity(noVersion)).isInstanceOf(MalformedBusinessEventException.class);
        assertThatThrownBy(() -> parser.requireIdentity(negativeVersion)).isInstanceOf(MalformedBusinessEventException.class);
        assertThatThrownBy(() -> parser.requireIdentity(noHuarique)).isInstanceOf(MalformedBusinessEventException.class);
    }

    @Test
    void onlyTheFourEventsOfBusinessThatRadarUsesAreRecognized() {
        assertThat(BusinessEventType.fromEventType("HuariqueRegistered")).contains(BusinessEventType.HUARIQUE_REGISTERED);
        assertThat(BusinessEventType.fromEventType("HuariqueUpdated")).contains(BusinessEventType.HUARIQUE_UPDATED);
        assertThat(BusinessEventType.fromEventType("LocationUpdated")).contains(BusinessEventType.LOCATION_UPDATED);
        assertThat(BusinessEventType.fromEventType("OpeningStatusChanged"))
                .contains(BusinessEventType.OPENING_STATUS_CHANGED);
        assertThat(BusinessEventType.fromEventType("MenuUpdated")).isEmpty();
        assertThat(BusinessEventType.fromEventType("huariqueregistered")).isEmpty();
        assertThat(BusinessEventType.fromEventType(null)).isEmpty();
    }
}
