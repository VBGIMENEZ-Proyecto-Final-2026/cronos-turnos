package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.Slot;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El ciclo completo del teléfono, con un Kafka real y PostgreSQL real, y la cátedra simulada por la propia prueba: la cátedra
 * pide el teléfono, cronos lo manda, la cátedra lo rechaza, cronos manda otro y la cátedra confirma. Es lo más parecido a la
 * reserva real que se puede probar sin tocar la cátedra.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, TelefonoDePunteAPunteTest.ConKafka.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelefonoDePunteAPunteTest {

    private static final String ACCIONES = "alumnos.turnos.acciones.cuenta-e2e";
    private static final String TELEFONO = "catedra.turnos.telefono.cuenta-e2e";
    private static final String GRUPO_DE_LA_CUENTA = "alumnos-e2e-" + UUID.randomUUID();
    private static final String GROUP_ID_TECNICO = "proyecto-e2e-privado";
    private static final String CLAVE = "clave-de-prueba-1";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    /** La API REST de la cátedra (solo la usa la cancelación). */
    static final StubServer CATEDRA_REST = new StubServer();

    static {
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ACCIONES, 1, (short) 1), new NewTopic(TELEFONO, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Order(1)
    static class ConKafka implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "catedra.kafka.consumer.enabled=true",
                    "catedra.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "catedra.kafka.actions-topic=" + ACCIONES,
                    "catedra.kafka.consumer-group-id=" + GRUPO_DE_LA_CUENTA,
                    "catedra.kafka.retry.delay=100ms",
                    "catedra.kafka.retry.max-delay=200ms",
                    "catedra.phone.enabled=true",
                    "catedra.phone.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "catedra.phone.topic=" + TELEFONO,
                    "catedra.phone.group-id=" + GROUP_ID_TECNICO,
                    "catedra.phone.send-timeout=5s",
                    "catedra.rest.base-url=" + CATEDRA_REST.url(), "catedra.rest.max-retries=0").applyTo(context);
        }
    }

    @AfterAll
    static void apagarKafka() {
        kafka.stop();
        CATEDRA_REST.close();
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationRepository reservations;
    @Autowired KafkaMessageListenerContainer<String, String> consumidor;

    // ---- ayudas ---------------------------------------------------------------------------------------------

    private String tokenDe(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}")).andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions telefono(String token, long id, String numero) throws Exception {
        return mvc.perform(post("/api/reservations/" + id + "/phone").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content("{\"phoneNumber\":\"" + numero + "\"}"));
    }

    /** Lo que publica la cátedra en el topic de acciones. */
    private static void laCatedraPublica(String... valores) throws Exception {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var productor = new KafkaProducer<String, String>(config)) {
            for (String valor : valores) {
                productor.send(new ProducerRecord<>(ACCIONES, 0, null, valor)).get();
            }
        }
    }

    private static String evento(String tipo, String eventId, String proceso, String hold, String extra) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + tipo + "\",\"reservationProcessId\":\"" + proceso
                + "\",\"holdId\":\"" + hold + "\",\"schemaVersion\":1" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** Lo que cronos publicó en el topic del teléfono para ese proceso, en orden. */
    private static List<ConsumerRecord<String, String>> publicadoPorCronos(String proceso, int esperados) {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> propios = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var particion = new TopicPartition(TELEFONO, 0);
            consumer.assign(List.of(particion));
            consumer.seekToBeginning(List.of(particion));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                propios.clear();
                consumer.seekToBeginning(List.of(particion));
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (r.key().equals(proceso)) {
                        propios.add(r);
                    }
                });
                return propios.size() >= esperados;
            });
        }
        return propios;
    }

    private String estado(long id) {
        return jdbc.queryForObject("SELECT status FROM reservation_process WHERE id = ?", String.class, id);
    }

    // ---- el ciclo --------------------------------------------------------------------------------------------

    @Test
    void elCicloCompletoPedidoEnvioRechazoOtroEnvioYConfirmacion() throws Exception {
        await().atMost(Duration.ofSeconds(90)).until(() -> !consumidor.getAssignedPartitions().isEmpty());
        String token = tokenDe("ana");
        long ana = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'ana'", Long.class);
        String proceso = "process-e2e-" + UUID.randomUUID();
        String hold = "hold-e2e-" + UUID.randomUUID();
        Instant ahora = Instant.now();
        var r = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2030, 3, 1), LocalTime.of(14, 30), LocalTime.of(15, 0)), "Juan", "Pérez", ahora));
        r = reservations.save(r.held(hold, proceso, ahora.plusSeconds(600), ahora));
        long id = reservations.save(r.waitingForPhone(ahora.plusSeconds(600), ahora)).id();

        try (var log = new LogCapture()) {
            // 1) La cátedra todavía no pidió el teléfono: cronos no lo acepta.
            telefono(token, id, "+5492617778899").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PHONE_NOT_REQUESTED"));

            // 2) La cátedra lo pide (AdditionalInformationRequested) y cronos guarda el requestEventId.
            laCatedraPublica(evento("AdditionalInformationRequested", "request-event-e2e-1", proceso, hold,
                    "\"requiredField\":\"PHONE_NUMBER\",\"expiresAt\":\"2030-03-01T14:44:50.905856025Z\""));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT request_event_id FROM reservation_process WHERE id = ?", String.class, id))
                    .isEqualTo("request-event-e2e-1"));

            // 3) El usuario informa el teléfono (con separadores) y cronos lo manda tal como lo pide la referencia.
            telefono(token, id, "+54 9 (261) 777-8899").andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.phoneSubmitted").value(true)).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"));
            var primero = publicadoPorCronos(proceso, 1).get(0);
            var mensaje1 = MAPPER.readTree(primero.value());
            assertThat(primero.key()).isEqualTo(proceso);
            assertThat(mensaje1.get("eventType").asString()).isEqualTo("AdditionalInformationSubmitted");
            assertThat(mensaje1.get("groupId").asString()).isEqualTo(GROUP_ID_TECNICO);
            assertThat(mensaje1.get("holdId").asString()).isEqualTo(hold);
            assertThat(mensaje1.get("requestEventId").asString()).isEqualTo("request-event-e2e-1");
            assertThat(mensaje1.get("information").get("phoneNumber").asString()).isEqualTo("+5492617778899");
            assertThat(mensaje1.get("schemaVersion").asInt()).isEqualTo(1);

            // 4) Sin respuesta de la cátedra no se manda otro.
            telefono(token, id, "2615550000").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PHONE_ALREADY_SUBMITTED"));

            // 5) La cátedra rechaza el teléfono: el proceso sigue esperando y se puede mandar otro.
            laCatedraPublica(evento("AdditionalInformationRejected", "rejection-event-e2e-1", proceso, hold,
                    "\"requestEventId\":\"request-event-e2e-1\",\"reason\":\"INVALID_PHONE_NUMBER\","
                            + "\"message\":\"El telefono ingresado no es valido.\""));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT last_rejection_reason FROM reservation_process WHERE id = ?",
                        String.class, id)).isEqualTo("INVALID_PHONE_NUMBER");
                assertThat(jdbc.queryForObject("SELECT phone_submitted_at FROM reservation_process WHERE id = ?",
                        java.sql.Timestamp.class, id)).as("se puede responder de nuevo").isNull();
            });
            assertThat(estado(id)).as("un rechazo no cierra el proceso").isEqualTo("WAITING_FOR_PHONE");

            // 6) El usuario manda otro número: otro eventId, el mismo requestEventId.
            telefono(token, id, "2615550000").andExpect(status().isAccepted());
            var segundo = MAPPER.readTree(publicadoPorCronos(proceso, 2).get(1).value());
            assertThat(segundo.get("eventId").asString()).isNotEqualTo(mensaje1.get("eventId").asString());
            assertThat(segundo.get("requestEventId").asString()).isEqualTo("request-event-e2e-1");
            assertThat(segundo.get("information").get("phoneNumber").asString()).isEqualTo("2615550000");

            // 7) La cátedra confirma: la reserva queda confirmada y ya no acepta teléfonos.
            laCatedraPublica(evento("AppointmentConfirmed", "confirmation-event-e2e-1", proceso, hold,
                    "\"requestEventId\":\"request-event-e2e-1\",\"reservationId\":8251,\"status\":\"CONFIRMED\""));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(estado(id)).isEqualTo("CONFIRMED"));
            telefono(token, id, "2615550001").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_AWAITING_PHONE"));
            assertThat(jdbc.queryForObject("SELECT catedra_reservation_id FROM reservation_process WHERE id = ?", Long.class, id))
                    .isEqualTo(8251L);

            // 7b) El usuario cancela: la cátedra responde por REST y el estado local pasa a CANCELLED enseguida, sin esperar el
            // evento. El AppointmentCancelled que la cátedra publica después llega a un estado final y no cambia nada.
            CATEDRA_REST.reply(Reply.json(200, "{\"reservationId\":8251,\"reservationProcessId\":\"" + proceso
                    + "\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-22T15:10:00Z\"}"));
            mvc.perform(post("/api/reservations/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content("{\"reason\":\"Cambio de planes\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(estado(id)).isEqualTo("CANCELLED");
            laCatedraPublica(evento("AppointmentCancelled", "cancellation-event-e2e-1", proceso, hold,
                    "\"reservationId\":8251,\"status\":\"CANCELLED\",\"reason\":\"Cambio de planes\""));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM processed_event WHERE event_id = 'cancellation-event-e2e-1'", Integer.class)).isEqualTo(1));
            assertThat(estado(id)).as("el evento tardío no cambia nada").isEqualTo("CANCELLED");
            assertThat(log.eventos("kafka.evento.ignorado")).anySatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains("id_evento=cancellation-event-e2e-1").contains("motivo=estado_final"));
            assertThat(CATEDRA_REST.requests()).hasSize(1);

            // 8) Los teléfonos no quedaron en ninguna tabla.
            var guardado = new StringBuilder();
            for (String tabla : List.of("reservation_process", "processed_event", "unmatched_event")) {
                jdbc.queryForList("SELECT * FROM " + tabla).forEach(fila -> guardado.append(fila).append('\n'));
            }
            assertThat(guardado.toString()).doesNotContain("7778899").doesNotContain("2615550000");

            // 9) Ni en el log de cronos, ni el groupId técnico, ni los nombres de los topics.
            String deCronos = log.list().stream().filter(e -> e.getLoggerName().startsWith("ar.edu.um.cronos"))
                    .map(e -> e.getFormattedMessage()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(deCronos).contains("reserva.telefono.enviado").contains("kafka.evento.aplicado")
                    .doesNotContain("7778899").doesNotContain("2615550000").doesNotContain(GROUP_ID_TECNICO)
                    .doesNotContain(TELEFONO).doesNotContain(ACCIONES).doesNotContain(GRUPO_DE_LA_CUENTA).doesNotContain("Cambio de planes");
            assertThat(log.eventos("reserva.telefono.enviado")).hasSize(2);
        }
    }
}
