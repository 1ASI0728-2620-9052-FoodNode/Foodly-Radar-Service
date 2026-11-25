package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import pe.foodly.radar.application.eventhandlers.HuariqueRegisteredEventHandler;
import pe.foodly.radar.application.eventhandlers.HuariqueUpdatedEventHandler;
import pe.foodly.radar.application.eventhandlers.OpeningStatusChangedEventHandler;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.infrastructure.persistence.redis.HuariqueVersionStore;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessEventListenerTest {

    private static final long TAG = 7L;
    private static final HuariqueId ID = new HuariqueId("h-1");
    private static final String OPEN_SNAPSHOT = snapshot("OPEN", -12.1219, -77.0297);

    @Mock
    private HuariqueRegisteredEventHandler registeredHandler;
    @Mock
    private HuariqueUpdatedEventHandler updatedHandler;
    @Mock
    private OpeningStatusChangedEventHandler openingStatusChangedHandler;
    @Mock
    private HuariqueVersionStore versionStore;
    @Mock
    private Channel channel;

    private final List<Long> sleeps = new ArrayList<>();
    private BusinessEventListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        listener = new BusinessEventListener(new BusinessEventParser(mapper), registeredHandler, updatedHandler,
                openingStatusChangedHandler, versionStore, new EventRetryProperties(3, 100, 2.0), sleeps::add);
        when(versionStore.findVersion(ID)).thenReturn(Optional.empty());
    }

    private static Message message(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    /** Snapshot de Business: incluye campos que Radar no usa. */
    private static String snapshot(String openStatus, double latitude, double longitude) {
        return """
                { "id": "h-1", "ownerId": "owner-1", "name": "El Huarique", "address": "Lima 123",
                  "cuisineType": "CRIOLLA", "priceRange": { "minimum": 10, "maximum": 50 },
                  "location": { "latitude": %s, "longitude": %s }, "openStatus": "%s",
                  "tags": [], "openingHours": [], "images": [], "version": 0 }""".formatted(latitude, longitude, openStatus);
    }

    private static String registered(long aggregateVersion) {
        return event("HuariqueRegistrado", aggregateVersion, OPEN_SNAPSHOT);
    }

    private static String event(String type, long aggregateVersion, String data) {
        return """
                { "eventId": "e-1", "type": "%s", "schemaVersion": 1, "huariqueId": "h-1",
                  "aggregateVersion": %d, "occurredAt": "2026-11-24T20:00:00Z", "data": %s }"""
                .formatted(type, aggregateVersion, data);
    }

    private void verifyAckedOnly() throws Exception {
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    private void verifyNackedToDlqOnly() throws Exception {
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // ---- caso feliz y orden del ack ----

    @Test
    void appliesANewerEventStoresTheVersionAndAcksOnlyAfterRedisIsUpdated() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(3L));

        listener.onMessage(message(registered(4)), channel);

        InOrder order = inOrder(registeredHandler, versionStore, channel);
        order.verify(registeredHandler).handle(new HuariqueRegisteredEvent("e-1", "h-1", 4,
                Instant.parse("2026-11-24T20:00:00Z"), -12.1219, -77.0297, AvailabilityStatus.ACTIVE));
        order.verify(versionStore).writeVersion(ID, 4);
        order.verify(channel).basicAck(TAG, false);
        verifyAckedOnly();
        assertThat(sleeps).isEmpty();
    }

    /** aggregateVersion empieza en 0: la regla de versión no debe descartar el primer evento. */
    @Test
    void appliesTheFirstEventOfAHuariqueWhoseAggregateVersionIsZero() throws Exception {
        listener.onMessage(message(registered(0)), channel);

        verify(registeredHandler).handle(any(HuariqueRegisteredEvent.class));
        verify(versionStore).writeVersion(ID, 0);
        verifyAckedOnly();
    }

    @Test
    void aRepeatedVersionZeroIsDiscardedOnceVersionZeroIsStored() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(0L));

        listener.onMessage(message(registered(0)), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyAckedOnly();
    }

    @Test
    void versionOneSupersedesAStoredVersionZero() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(0L));

        listener.onMessage(message(event("PerfilDelHuariqueActualizado", 1, OPEN_SNAPSHOT)), channel);

        verify(updatedHandler).handle(any(HuariqueUpdatedEvent.class));
        verify(versionStore).writeVersion(ID, 1);
        verifyAckedOnly();
    }

    @Test
    void dispatchesEachTypeToItsOwnHandlerMappingOpenAndClosed() throws Exception {
        listener.onMessage(message(event("PerfilDelHuariqueActualizado", 1, snapshot("CLOSED", -12.15, -77.02))), channel);
        verify(updatedHandler).handle(new HuariqueUpdatedEvent("e-1", "h-1", 1,
                Instant.parse("2026-11-24T20:00:00Z"), -12.15, -77.02, AvailabilityStatus.INACTIVE));

        listener.onMessage(message(event("EstadoAbiertoCerradoCambiado", 2, OPEN_SNAPSHOT)), channel);
        verify(openingStatusChangedHandler).handle(
                new OpeningStatusChangedEvent("e-1", "h-1", 2, Instant.parse("2026-11-24T20:00:00Z"),
                        AvailabilityStatus.ACTIVE));

        verifyNoInteractions(registeredHandler);
    }

    // ---- regla de versión ----

    @Test
    void anEventWithTheSameVersionIsAckedAndDiscarded() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(4L));

        listener.onMessage(message(registered(4)), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyAckedOnly();
    }

    @Test
    void anOlderEventIsAckedAndDiscarded() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(9L));

        listener.onMessage(message(registered(2)), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyAckedOnly();
    }

    // ---- tipos que Radar no procesa ----

    @Test
    void menuEventsAreAckedAndIgnoredWithoutTouchingRedis() throws Exception {
        listener.onMessage(message(event("MenuCreado", 0, "{ \"categories\": [] }")), channel);
        listener.onMessage(message(event("MenuActualizado", 1, "{ \"categories\": [] }")), channel);

        verifyNoInteractions(registeredHandler, updatedHandler, openingStatusChangedHandler, versionStore);
        verify(channel, org.mockito.Mockito.times(2)).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void anUnknownTypeIsAckedAndIgnored() throws Exception {
        listener.onMessage(message(event("TipoNuevo", 1, "{}")), channel);

        verifyNoInteractions(registeredHandler, updatedHandler, openingStatusChangedHandler, versionStore);
        verifyAckedOnly();
    }

    @Test
    void theEnglishNamesOfTheOldContractAreNotProcessed() throws Exception {
        listener.onMessage(message(event("HuariqueRegistered", 1, OPEN_SNAPSHOT)), channel);

        verifyNoInteractions(registeredHandler, updatedHandler, openingStatusChangedHandler, versionStore);
        verifyAckedOnly();
    }

    // ---- datos inválidos: directo a la DLQ, sin reintentos ----

    @Test
    void anUnreadableMessageGoesStraightToTheDlq() throws Exception {
        listener.onMessage(message("{{{ no es json"), channel);

        verifyNoInteractions(registeredHandler, versionStore);
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aDataMissingRequiredFieldsGoesStraightToTheDlq() throws Exception {
        listener.onMessage(message(event("HuariqueRegistrado", 1, "{ \"openStatus\": \"OPEN\" }")), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void anUnsupportedSchemaVersionGoesStraightToTheDlq() throws Exception {
        listener.onMessage(message(registered(1).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")), channel);

        verifyNoInteractions(registeredHandler);
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void invalidDomainDataFromTheHandlerIsNotRetried() throws Exception {
        doThrow(new IllegalArgumentException("La latitud debe estar entre -90 y 90."))
                .when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler).handle(any());
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    // ---- reintento en memoria ----

    @Test
    void retriesWithGrowingBackoffAndAcksWhenALaterAttemptSucceeds() throws Exception {
        doThrow(new IllegalStateException("redis caído")).doThrow(new IllegalStateException("redis caído"))
                .doNothing().when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(3)).handle(any());
        assertThat(sleeps).containsExactly(100L, 200L);
        verify(versionStore).writeVersion(ID, 1);
        verifyAckedOnly();
    }

    @Test
    void afterThreeFailedAttemptsTheMessageIsNackedWithoutRequeue() throws Exception {
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(3)).handle(any());
        assertThat(sleeps).containsExactly(100L, 200L);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
    }

    @Test
    void aFailureWhileStoringTheVersionIsRetriedAndNothingIsAckedInBetween() throws Exception {
        doThrow(new IllegalStateException("redis caído")).doNothing().when(versionStore).writeVersion(ID, 1);

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(2)).handle(any());
        verify(channel, org.mockito.Mockito.times(1)).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void anInterruptedWaitLeavesTheMessageUnackedSoItIsRedelivered() throws Exception {
        BusinessEventListener interrupted = new BusinessEventListener(
                new BusinessEventParser(new ObjectMapper().findAndRegisterModules()), registeredHandler,
                updatedHandler, openingStatusChangedHandler, versionStore,
                new EventRetryProperties(3, 100, 2.0), millis -> {
                    throw new InterruptedException();
                });
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        try {
            interrupted.onMessage(message(registered(1)), channel);

            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // no contaminar otras pruebas
        }
    }

    @Test
    void aSingleAttemptConfigurationNeverSleeps() throws Exception {
        BusinessEventListener oneAttempt = new BusinessEventListener(
                new BusinessEventParser(new ObjectMapper().findAndRegisterModules()), registeredHandler,
                updatedHandler, openingStatusChangedHandler, versionStore,
                new EventRetryProperties(1, 100, 2.0), sleeps::add);
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        oneAttempt.onMessage(message(registered(1)), channel);

        verify(registeredHandler).handle(any());
        assertThat(sleeps).isEmpty();
        verify(channel).basicNack(TAG, false, false);
    }
}
