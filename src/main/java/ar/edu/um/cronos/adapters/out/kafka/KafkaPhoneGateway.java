package ar.edu.um.cronos.adapters.out.kafka;

import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;
import ar.edu.um.cronos.config.CatedraPhoneProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica {@code AdditionalInformationSubmitted} en el topic del teléfono (referencia 15.5):
 *
 * <pre>{"eventId", "eventType", "groupId", "reservationProcessId", "holdId", "requestEventId",
 *  "information": {"phoneNumber"}, "occurredAt", "schemaVersion": 1}</pre>
 *
 * <p>La <b>clave del mensaje es el {@code reservationProcessId}</b> (como pide la referencia). El productor usa
 * {@code acks=all} e idempotencia, y un plazo acotado: pasado {@code sendTimeout} el envío falla con
 * {@link PhoneUnavailableException} y no se reintenta solo (no hay ciclos de reintento ilimitados, enunciado 11).
 *
 * <p>El productor se crea la primera vez que se usa: la aplicación arranca aunque Kafka no responda, y si no se pudo crear (un
 * servidor que no resuelve) se vuelve a intentar en el próximo envío.
 *
 * <p><b>Nunca registra nada del mensaje.</b> El número viaja dentro del valor y no se escribe en ningún log; las librerías de
 * Kafka están en {@code ERROR} (a {@code TRACE} el productor imprime cada registro).
 */
public class KafkaPhoneGateway implements PhoneGateway, AutoCloseable {

    /** Identifica al cliente ante el broker. No lleva nada de la cuenta (el groupId aparece en el cuerpo, no acá). */
    static final String CLIENT_ID = "cronos-telefono";
    static final String EVENT_TYPE = "AdditionalInformationSubmitted";
    static final int SCHEMA_VERSION = 1;

    private final CatedraPhoneProperties properties;
    private final JsonMapper mapper;
    private volatile KafkaProducer<String, String> producer;

    public KafkaPhoneGateway(CatedraPhoneProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void submit(PhoneSubmission submission) {
        ProducerRecord<String, String> record = new ProducerRecord<>(properties.topic().strip(),
                submission.reservationProcessId(), json(submission));
        try {
            producer().send(record).get(properties.sendTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhoneUnavailableException(e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new PhoneUnavailableException(e);
        }
    }

    /** El mensaje exacto de la referencia 15.5, con los campos en ese orden. */
    String json(PhoneSubmission submission) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", submission.eventId());
        message.put("eventType", EVENT_TYPE);
        message.put("groupId", properties.groupId().strip());
        message.put("reservationProcessId", submission.reservationProcessId());
        message.put("holdId", submission.holdId());
        message.put("requestEventId", submission.requestEventId());
        message.put("information", Map.of("phoneNumber", submission.phone().value()));
        message.put("occurredAt", submission.occurredAt().truncatedTo(ChronoUnit.SECONDS).toString());
        message.put("schemaVersion", SCHEMA_VERSION);
        return mapper.writeValueAsString(message);
    }

    static Map<String, Object> producerConfig(CatedraPhoneProperties properties) {
        // Ojo con los tipos: el cliente de Kafka rechaza un Long donde espera un entero de 32 bits (delivery.timeout.ms y
        // request.timeout.ms son INT; max.block.ms es LONG).
        int timeout = (int) properties.sendTimeout().toMillis();
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers().strip());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // El broker confirma cuando todas las réplicas lo tienen, y reintentar dentro del plazo no duplica el mensaje.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Todo acotado por el mismo plazo: esperar los metadatos, cada pedido y la entrega completa.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (long) timeout);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.max(500, timeout / 2));
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return config;
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> current = producer;
        if (current == null) {
            synchronized (this) {
                current = producer;
                if (current == null) {
                    current = new KafkaProducer<>(producerConfig(properties));
                    producer = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        KafkaProducer<String, String> current = producer;
        if (current != null) {
            current.close(java.time.Duration.ofSeconds(2));
        }
    }
}
