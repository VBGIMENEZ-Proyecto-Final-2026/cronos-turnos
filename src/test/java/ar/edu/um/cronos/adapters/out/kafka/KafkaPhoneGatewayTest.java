package ar.edu.um.cronos.adapters.out.kafka;

import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;
import ar.edu.um.cronos.config.CatedraPhoneProperties;
import ar.edu.um.cronos.domain.PhoneNumber;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * El productor del teléfono contra un Kafka real: el mensaje tiene que ser exactamente el de la referencia 15.5, con la clave
 * que pide, y cuando Kafka no responde el envío falla a tiempo en lugar de quedarse esperando.
 */
class KafkaPhoneGatewayTest {

    private static final String TOPIC = "catedra.turnos.telefono.cuenta-de-prueba";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void apagarKafka() {
        kafka.stop();
    }

    private static CatedraPhoneProperties propiedades(String servidores, Duration plazo) {
        return new CatedraPhoneProperties(servidores, TOPIC, "proyecto-de-prueba", plazo);
    }

    private static PhoneSubmission envio(String eventId, String proceso, String numero) {
        return new PhoneSubmission(eventId, proceso, "hold-6c0c2f41", "request-event-8a31", PhoneNumber.parse(numero),
                Instant.parse("2026-09-10T14:36:00.123456789Z"));
    }

    /** Todo lo que hay en el topic, en orden. */
    private static List<ConsumerRecord<String, String>> leerTodo(int esperados) {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var particion = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(particion));
            consumer.seekToBeginning(List.of(particion));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(leidos::add);
                return leidos.size() >= esperados;
            });
        }
        return leidos;
    }

    // ---- el mensaje ------------------------------------------------------------------------------------------

    @Test
    void elMensajeEsExactamenteElDeLaReferenciaConLaClaveDelProceso() {
        var proceso = "process-a92b47d0-" + UUID.randomUUID();
        try (var gateway = new KafkaPhoneGateway(propiedades(kafka.getBootstrapServers(), Duration.ofSeconds(5)), MAPPER)) {
            gateway.submit(envio("phone-submitted-b91e", proceso, "+54 9 (261) 555-1234"));
        }

        var registro = leerTodo(1).stream().filter(r -> r.key().equals(proceso)).findFirst().orElseThrow();
        assertThat(registro.key()).as("la clave es el reservationProcessId").isEqualTo(proceso);
        assertThat(registro.value()).isEqualTo("{\"eventId\":\"phone-submitted-b91e\","
                + "\"eventType\":\"AdditionalInformationSubmitted\",\"groupId\":\"proyecto-de-prueba\","
                + "\"reservationProcessId\":\"" + proceso + "\",\"holdId\":\"hold-6c0c2f41\","
                + "\"requestEventId\":\"request-event-8a31\",\"information\":{\"phoneNumber\":\"+5492615551234\"},"
                + "\"occurredAt\":\"2026-09-10T14:36:00Z\",\"schemaVersion\":1}");
    }

    @Test
    void tieneLosMismosCamposQueElEjemploDeLaReferencia() throws Exception {
        // Copiado de INTEGRATION_REFERENCE-v2, sección 15.5.
        JsonNode referencia = MAPPER.readTree("""
                {"eventId":"phone-submitted-b91e","eventType":"AdditionalInformationSubmitted",
                 "groupId":"proyecto-juan-perez","reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41",
                 "requestEventId":"request-event-8a31","information":{"phoneNumber":"+5492615551234"},
                 "occurredAt":"2026-09-10T14:36:00Z","schemaVersion":1}""");
        var gateway = new KafkaPhoneGateway(propiedades("localhost:1", Duration.ofSeconds(5)), MAPPER);

        JsonNode nuestro = MAPPER.readTree(gateway.json(envio("phone-submitted-b91e", "process-a92b47d0", "+5492615551234")));

        assertThat(nuestro.propertyNames()).containsExactlyElementsOf(referencia.propertyNames());
        assertThat(nuestro.get("information").propertyNames()).containsExactly("phoneNumber");
        assertThat(nuestro.get("schemaVersion").isInt()).isTrue();
        assertThat(nuestro.get("eventType").asString()).isEqualTo("AdditionalInformationSubmitted");
        assertThat(nuestro.get("occurredAt").asString()).as("al segundo, como el ejemplo").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
    }

    @Test
    void variosEnviosAlMismoProcesoQuedanEnOrdenYCadaUnoConSuEventId() {
        var proceso = "process-orden-" + UUID.randomUUID();
        try (var gateway = new KafkaPhoneGateway(propiedades(kafka.getBootstrapServers(), Duration.ofSeconds(5)), MAPPER)) {
            gateway.submit(envio("phone-submitted-1", proceso, "2615550001"));
            gateway.submit(envio("phone-submitted-2", proceso, "2615550002"));
        }

        var delProceso = leerTodo(2).stream().filter(r -> r.key().equals(proceso)).toList();
        assertThat(delProceso).extracting(r -> MAPPER.readTree(r.value()).get("eventId").asString())
                .containsExactly("phone-submitted-1", "phone-submitted-2");
    }

    @Test
    void enviosConcurrentesNoSePierdenNiSeMezclan() throws Exception {
        var proceso = "process-concurrente-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (var gateway = new KafkaPhoneGateway(propiedades(kafka.getBootstrapServers(), Duration.ofSeconds(5)), MAPPER)) {
            List<Future<?>> futuros = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                futuros.add(pool.submit(() -> gateway.submit(envio("phone-concurrente-" + n, proceso, "26155500" + (10 + n)))));
            }
            for (var f : futuros) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        var ids = leerTodo(20).stream().filter(r -> r.key().equals(proceso))
                .map(r -> MAPPER.readTree(r.value()).get("eventId").asString()).toList();
        assertThat(ids).hasSize(20).doesNotHaveDuplicates();
    }

    // ---- las garantías del productor -------------------------------------------------------------------------

    @Test
    void elProductorEsperaATodasLasReplicasEsIdempotenteYTieneTodoAcotado() {
        var config = KafkaPhoneGateway.producerConfig(propiedades("kafka.invalid:9092", Duration.ofSeconds(5)));

        assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000L)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2500)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0)
                .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "cronos-telefono");
        assertThat(config.get(ProducerConfig.CLIENT_ID_CONFIG).toString()).as("sin nada de la cuenta").doesNotContain("proyecto");
    }

    @Test
    void elClienteDeKafkaAceptaLaConfiguracionConLosTiposQueEspera() {
        // Un valor del tipo equivocado (un Long donde se espera un entero) hace fallar la construcción del productor: pasó una
        // vez y una prueba que solo comparaba los valores no lo habría notado.
        for (Duration plazo : List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMinutes(1))) {
            var config = KafkaPhoneGateway.producerConfig(propiedades("localhost:1", plazo));

            try (var productor = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(config)) {
                assertThat(productor).isNotNull();
            }
        }
    }

    // ---- si Kafka no responde --------------------------------------------------------------------------------

    @Test
    void conKafkaCaidoFallaDentroDelPlazoSinQuedarseEsperando() {
        var gateway = new KafkaPhoneGateway(propiedades("localhost:1", Duration.ofSeconds(1)), MAPPER);
        long inicio = System.nanoTime();

        assertThatThrownBy(() -> gateway.submit(envio("phone-submitted-x", "process-x", "2615550000")))
                .isInstanceOfSatisfying(PhoneUnavailableException.class, e ->
                        assertThat(e.getMessage()).doesNotContain("localhost").doesNotContain("2615550000")
                                .doesNotContain("proyecto").doesNotContain("telefono"));

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).as("el plazo es 1 s: no espera de más")
                .isLessThan(Duration.ofSeconds(8));
        gateway.close();
    }

    @Test
    void conUnServidorQueNoResuelveFallaYVuelveAIntentarEnElProximoEnvio() {
        var gateway = new KafkaPhoneGateway(propiedades("kafka-de-la-catedra.example.invalid:9092", Duration.ofSeconds(1)), MAPPER);

        for (int intento = 0; intento < 2; intento++) {
            assertThatThrownBy(() -> gateway.submit(envio("phone-submitted-x", "process-x", "2615550000")))
                    .isInstanceOf(PhoneUnavailableException.class);
        }
        gateway.close();
    }

    @Test
    void cerrarSinHaberEnviadoNadaNoFalla() {
        new KafkaPhoneGateway(propiedades("localhost:1", Duration.ofSeconds(1)), MAPPER).close();
    }
}
