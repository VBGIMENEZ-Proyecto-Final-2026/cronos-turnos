package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.adapters.out.persistence.ProcessedEventsFalibleConfiguration;
import ar.edu.um.cronos.adapters.out.persistence.ProcessedEventsFalibleConfiguration.ProcessedEventsFalible;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import ar.edu.um.cronos.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El consumidor de los eventos de reserva contra un Kafka real (Testcontainers) y PostgreSQL real. Cada prueba manda
 * mensajes y espera a que el offset del grupo avance: eso solo ocurre después de que el mensaje se aplicó, se descartó o
 * se estacionó, así que comprueba además de su efecto <b>cuándo</b> se confirma el offset.
 *
 * <p>El topic tiene una sola partición, como el de la cátedra, y el grupo es el propio de cronos.
 */
@SpringBootTest
@IntegrationTestSupport
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, ConsumidorKafkaTest.ConKafka.class})
@Import(ProcessedEventsFalibleConfiguration.class)
// El consumidor sigue vivo mientras el contexto vive y el contenedor de Kafka se apaga al terminar la clase.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumidorKafkaTest {

    private static final String TOPIC = "alumnos.turnos.acciones.cuenta-de-prueba";
    private static final String GRUPO_DE_LA_CUENTA = "alumnos-prueba-" + UUID.randomUUID();
    /** El grupo con el que cronos consume de verdad. */
    private static final String GRUPO = GRUPO_DE_LA_CUENTA + "-cronos";
    private static final TopicPartition PARTICION = new TopicPartition(TOPIC, 0);
    private static final Instant T0 = Instant.parse("2026-11-21T15:00:00Z");
    private static final Instant VENCE = Instant.parse("2026-11-24T15:00:00Z");

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        kafka.start();
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers",
                kafka.getBootstrapServers()))) {
            // Una sola partición, como el topic real de la cátedra.
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Enciende el consumidor apuntando al Kafka del contenedor. Va después del inicializador base: sus valores ganan. */
    @Order(1)
    static class ConKafka implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            org.springframework.boot.test.util.TestPropertyValues.of(
                    "catedra.kafka.consumer.enabled=true",
                    "catedra.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "catedra.kafka.actions-topic=" + TOPIC,
                    "catedra.kafka.consumer-group-id=" + GRUPO_DE_LA_CUENTA,
                    // Esperas cortas para que los reintentos no alarguen las pruebas.
                    "catedra.kafka.retry.delay=150ms",
                    "catedra.kafka.retry.max-delay=300ms",
                    "catedra.kafka.start-retry-interval=1s").applyTo(context);
        }
    }

    @AfterAll
    static void apagarKafka() {
        kafka.stop();
    }

    @Autowired KafkaMessageListenerContainer<String, String> consumidor;
    @Autowired ReservationRepository reservations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ProcessedEventsFalible falible;

    private long ana;
    private static int seq;

    @BeforeEach
    void preparar() {
        // Con earliest el consumidor ya leyó todo lo anterior; hace falta que tenga sus particiones antes de mandar nada.
        await().atMost(Duration.ofSeconds(90)).until(() -> !consumidor.getAssignedPartitions().isEmpty());
        falible.reset();
        limpiar();
        ana = users.save(User.register("ana", "$2a$10$hash", "Ana", "Gómez", null, null, "es", T0)).id();
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM unmatched_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    /** Un proceso de ana esperando el teléfono, con identificadores únicos en toda la clase. */
    private Reservation esperando() {
        seq++;
        var turno = new Slot(LocalDate.of(2026, 11, 24).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0));
        var r = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta", turno, "Juan", "Pérez", T0));
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, VENCE, T0.plusSeconds(1)));
        return reservations.save(r.waitingForPhone(VENCE, T0.plusSeconds(2)));
    }

    private Reservation leer(Reservation r) {
        return reservations.findById(r.id()).orElseThrow();
    }

    private static String json(String tipo, String eventId, String proceso, String hold, String extra) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + tipo + "\",\"reservationProcessId\":\""
                + proceso + "\",\"holdId\":\"" + hold + "\",\"schemaVersion\":1" + (extra.isEmpty() ? "" : "," + extra)
                + "}";
    }

    private static String pedido(Reservation r, String eventId) {
        return json("AdditionalInformationRequested", eventId, r.reservationProcessId(), r.holdId(),
                "\"requiredField\":\"PHONE_NUMBER\",\"expiresAt\":\"2026-11-24T15:00:00.905856025Z\"");
    }

    private static String confirmada(Reservation r, String eventId, long reservationId) {
        return json("AppointmentConfirmed", eventId, r.reservationProcessId(), r.holdId(),
                "\"reservationId\":" + reservationId + ",\"status\":\"CONFIRMED\"");
    }

    private static void enviar(String... valores) throws Exception {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> productor = new KafkaProducer<>(config)) {
            for (String valor : valores) {
                productor.send(new ProducerRecord<>(TOPIC, 0, null, valor)).get();
            }
        }
    }

    /** El offset que el grupo de cronos confirmó en la única partición (0 si todavía no confirmó ninguno). */
    private static long offsetConfirmado() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers",
                kafka.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GRUPO)
                    .partitionsToOffsetAndMetadata().get();
            OffsetAndMetadata confirmado = offsets.get(PARTICION);
            return confirmado == null ? 0 : confirmado.offset();
        }
    }

    private static void esperarOffset(long esperado) {
        await().ignoreExceptions().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(offsetConfirmado()).isEqualTo(esperado));
    }

    private int procesados() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class);
    }

    private int estacionados() {
        return jdbc.queryForObject("SELECT count(*) FROM unmatched_event", Integer.class);
    }

    // ---- aplicar y confirmar ---------------------------------------------------------------------------------

    @Test
    void unEventoAplicaElCambioYConfirmaElOffsetSinEscribirNiElTopicNiElGrupoNiLosServidores() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        try (var log = new LogCapture()) {
            enviar(pedido(r, "request-event-1"));

            esperarOffset(base + 1);
            assertThat(leer(r).requestEventId()).isEqualTo("request-event-1");
            assertThat(procesados()).isEqualTo(1);
            // La línea del evento lleva el identificador del mensaje, para seguirlo entre todas las que escribe.
            await().untilAsserted(() -> assertThat(log.eventos("kafka.evento.aplicado")).singleElement().satisfies(e -> {
                assertThat(e.getMDCPropertyMap()).containsEntry("requestId", "kafka-0-" + base);
                assertThat(e.getFormattedMessage()).contains("id_evento=request-event-1")
                        .contains("tipo=AdditionalInformationRequested");
            }));
            // El nombre del topic y del grupo contienen el groupId de la cuenta; los servidores son de la cátedra.
            String delConsumidor = loQueEscribioElConsumidor(log);
            assertThat(delConsumidor).as("el consumidor sí escribió algo que revisar").contains("kafka.evento.aplicado");
            assertThat(delConsumidor).doesNotContain(TOPIC).doesNotContain(GRUPO_DE_LA_CUENTA)
                    .doesNotContain(kafka.getBootstrapServers());
        }
    }

    @Test
    void elFlujoCompletoPedidoRechazoYConfirmacionLlegaAConfirmed() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();

        enviar(pedido(r, "request-event-1"),
                json("AdditionalInformationRejected", "rejection-event-1", r.reservationProcessId(), r.holdId(),
                        "\"reason\":\"INVALID_PHONE_NUMBER\",\"message\":\"El telefono ingresado no es valido.\""),
                confirmada(r, "confirmation-event-1", 8251));

        esperarOffset(base + 3);
        var tras = leer(r);
        assertThat(tras.status().name()).isEqualTo("CONFIRMED");
        assertThat(tras.catedraReservationId()).isEqualTo(8251L);
        assertThat(tras.requestEventId()).isEqualTo("request-event-1");
        assertThat(tras.lastRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(procesados()).isEqualTo(3);
    }

    @Test
    void elMismoEventoDosVecesSeAplicaUnaSolaVezYElOffsetAvanzaIgual() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();

        enviar(confirmada(r, "confirmation-event-1", 8251), confirmada(r, "confirmation-event-1", 8251));

        esperarOffset(base + 2);
        var tras = leer(r);
        assertThat(tras.status().name()).isEqualTo("CONFIRMED");
        assertThat(tras.version()).as("el proceso cambió una sola vez").isEqualTo(r.version() + 1);
        assertThat(procesados()).isEqualTo(1);
    }

    // ---- lo que no se entiende no traba la partición ---------------------------------------------------------

    @Test
    void unMensajeRotoUnTipoQueNoSeUsaYUnoNuloNoTrabanElConsumo() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        try (var log = new LogCapture()) {
            enviar("esto no es json",
                    "{\"eventId\":\"c-1\",\"eventType\":\"CatalogUpdated\",\"newVersion\":7,\"schemaVersion\":1}",
                    "{\"eventType\":\"AppointmentConfirmed\",\"schemaVersion\":1,\"eventId\":\"e\","
                            + "\"reservationProcessId\":\"p\",\"phoneNumber\":\"+5492615551234\"}",
                    pedido(r, "request-event-1"));
            try (var productor = productorSinClave()) {
                productor.send(new ProducerRecord<>(TOPIC, 0, null, null)).get(); // un mensaje sin valor
            }

            esperarOffset(base + 5);
            assertThat(leer(r).requestEventId()).as("el válido se aplicó pese a los anteriores").isEqualTo("request-event-1");
            assertThat(log.eventos("kafka.mensaje.ilegible")).extracting(e -> e.getFormattedMessage()).hasSize(3)
                    .anySatisfy(m -> assertThat(m).contains("motivo=json_ilegible"))
                    .anySatisfy(m -> assertThat(m).contains("motivo=falta_campo campo=reservationId")
                            .doesNotContain("5492615551234"))
                    .anySatisfy(m -> assertThat(m).contains("motivo=mensaje_vacio"))
                    .allSatisfy(m -> assertThat(m).contains("accion=descartado_y_offset_confirmado"));
            assertThat(log.eventos("kafka.mensaje.saltado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("tipo=CatalogUpdated"));
            assertThat(log.text()).as("el contenido de un mensaje roto nunca llega al log").doesNotContain("5492615551234");
        }
    }

    /**
     * Lo que el consumidor y cronos escriben con los niveles de producción (INFO o más), sin lo que escribe la prueba: el
     * hilo de la prueba y el del productor que usa para mandar mensajes (que sí conoce la dirección del servidor, como
     * cualquier cliente). Con todo en TRACE, como lo pone {@link LogCapture}, el JDK escribe al registrar las métricas
     * de JMX el nombre del topic: esas líneas no existen con el nivel normal y no son de cronos.
     */
    private static String loQueEscribioElConsumidor(LogCapture log) {
        String hiloDePrueba = Thread.currentThread().getName();
        return log.list().stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO))
                .filter(e -> !e.getThreadName().equals(hiloDePrueba) && !e.getThreadName().startsWith("kafka-producer"))
                .map(e -> e.getFormattedMessage()).reduce("", (a, b) -> a + "\n" + b);
    }

    private static KafkaProducer<String, String> productorSinClave() {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return new KafkaProducer<>(config);
    }

    // ---- proceso desconocido ---------------------------------------------------------------------------------

    @Test
    void unEventoDeUnProcesoQueNoExisteSeReintentaSeEstacionaYElConsumoSigue() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        try (var log = new LogCapture()) {
            // Uno desconocido al principio (el consumo sigue) y otro al final: el offset del último solo avanza si el
            // estacionado también confirma su offset, sin depender de que llegue otro mensaje después.
            enviar(json("AppointmentProcessExpired", "process-event-viejo-1", "process-que-no-existe", "hold-viejo", ""),
                    pedido(r, "request-event-1"),
                    json("AppointmentProcessExpired", "process-event-viejo-2", "process-tampoco-existe", "hold-viejo", ""));

            esperarOffset(base + 3);
            assertThat(leer(r).requestEventId()).isEqualTo("request-event-1");
            assertThat(jdbc.queryForList("SELECT event_id, reason FROM unmatched_event ORDER BY event_id"))
                    .extracting(f -> f.get("event_id") + ":" + f.get("reason"))
                    .containsExactly("process-event-viejo-1:UNKNOWN_PROCESS", "process-event-viejo-2:UNKNOWN_PROCESS");
            assertThat(procesados()).as("los desconocidos no se marcaron como procesados").isEqualTo(1);
            // Tres intentos en total por cada uno: dos reintentos con su línea, y al final el estacionado.
            assertThat(log.eventos("kafka.mensaje.reintento")).hasSize(4).allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains("tipo_de_falla=proceso_desconocido"));
            assertThat(log.eventos("kafka.evento.estacionado")).hasSize(2);
            assertThat(log.eventos("kafka.mensaje.agotado")).hasSize(2).allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains("accion=estacionado_y_offset_confirmado"));
        }
    }

    @Test
    void elClienteSeIdentificaConUnNombreQueNoLlevaElGrupoDeLaCuenta() {
        assertThat(consumidor.getContainerProperties().getClientId()).isEqualTo("cronos-eventos-de-reserva")
                .doesNotContain(GRUPO_DE_LA_CUENTA);
    }

    @Test
    void unEventoQueGanoLaCarreraContraElGuardadoDelProcesoSeAplicaEnUnReintentoSinEstacionarse() throws Exception {
        // El proceso existe pero todavía no está confirmado en la base (otra conexión lo tiene a medio guardar): el consumidor
        // no lo ve, reintenta, y cuando se confirma lo encuentra y lo aplica.
        seq++;
        String proceso = "process-tarde-" + seq;
        long base = offsetConfirmado();
        try (var log = new LogCapture(); Connection otra = dataSource.getConnection()) {
            otra.setAutoCommit(false);
            try (Statement st = otra.createStatement()) {
                st.execute("INSERT INTO reservation_process (user_id, reservation_process_id, hold_id, professional_id, "
                        + "appointment_date, start_time, end_time, patient_first_name, patient_last_name, status, "
                        + "expires_at, created_at, updated_at) VALUES (" + ana + ", '" + proceso + "', 'hold-tarde', 218, "
                        + "'2026-12-" + (10 + seq % 15) + "', '14:30', '15:00', 'Juan', 'Pérez', 'WAITING_FOR_PHONE', "
                        + "now() + interval '1 day', now(), now())");
            }
            enviar(json("AdditionalInformationRequested", "request-event-tarde", proceso, "hold-tarde", ""));

            // En cuanto aparece el primer reintento se confirma la fila: todavía queda la espera entera del siguiente.
            await().pollInterval(Duration.ofMillis(5)).atMost(Duration.ofSeconds(30)).until(() ->
                    !log.eventos("kafka.mensaje.reintento").isEmpty());
            otra.commit();

            esperarOffset(base + 1);
            assertThat(estacionados()).as("no se estacionó: se aplicó").isZero();
            assertThat(jdbc.queryForObject("SELECT request_event_id FROM reservation_process WHERE "
                    + "reservation_process_id = ?", String.class, proceso)).isEqualTo("request-event-tarde");
            assertThat(log.eventos("kafka.evento.proceso_desconocido")).isNotEmpty();
            assertThat(log.eventos("kafka.evento.aplicado")).hasSize(1);
        }
    }

    // ---- cuándo se confirma el offset ------------------------------------------------------------------------

    @Test
    void elOffsetSeConfirmaSoloDespuesDeAplicarElCambio() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();

        try (Connection bloqueo = dataSource.getConnection()) {
            bloqueo.setAutoCommit(false);
            try (Statement st = bloqueo.createStatement()) {
                // Toma la fila: el UPDATE del consumidor tiene que esperar a que se suelte.
                st.execute("SELECT id FROM reservation_process WHERE id = " + r.id() + " FOR UPDATE");
            }
            enviar(confirmada(r, "confirmation-event-1", 8251));

            // Durante dos segundos el consumidor tiene el mensaje en mano pero no pudo terminar: no hay offset ni efecto.
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(offsetConfirmado()).isEqualTo(base);
                assertThat(leer(r).status().name()).isEqualTo("WAITING_FOR_PHONE");
                assertThat(procesados()).as("ni siquiera el marcado se ve: es parte de la misma transacción").isZero();
            });
            bloqueo.rollback(); // suelta la fila
        }

        esperarOffset(base + 1);
        assertThat(leer(r).status().name()).isEqualTo("CONFIRMED");
        assertThat(procesados()).isEqualTo(1);
    }

    @Test
    void alReiniciarRetomaDesdeElOffsetConfirmadoYSiSeRetrocedeLosDuplicadosNoCambianNada() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        enviar(pedido(r, "request-event-1"), confirmada(r, "confirmation-event-1", 8251));
        esperarOffset(base + 2);
        var tras = leer(r);

        // 1) Un reinicio normal: retoma donde quedó, no relee nada.
        consumidor.stop();
        consumidor.start();
        await().atMost(Duration.ofSeconds(60)).until(() -> !consumidor.getAssignedPartitions().isEmpty());
        try (var log = new LogCapture()) {
            Thread.sleep(1500);
            assertThat(log.eventos("kafka.evento.duplicado")).as("no se releyó nada").isEmpty();
            assertThat(offsetConfirmado()).isEqualTo(base + 2);
        }

        // 2) Se retrocede el offset del grupo (como si cronos se hubiera caído antes de confirmar) y se vuelve a leer.
        consumidor.stop();
        await().ignoreExceptions().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers",
                    kafka.getBootstrapServers()))) {
                admin.alterConsumerGroupOffsets(GRUPO, Map.of(PARTICION, new OffsetAndMetadata(base))).all().get();
            }
        });
        try (var log = new LogCapture()) {
            consumidor.start();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertThat(log.eventos("kafka.evento.duplicado")).hasSize(2));
            esperarOffset(base + 2);
            assertThat(leer(r)).as("los dos eventos ya estaban aplicados: nada cambia").isEqualTo(tras);
            assertThat(procesados()).isEqualTo(2);
            assertThat(loQueEscribioElConsumidor(log)).contains("kafka.evento.duplicado")
                    .doesNotContain(TOPIC).doesNotContain(GRUPO_DE_LA_CUENTA).doesNotContain(kafka.getBootstrapServers());
        }
    }

    // ---- fallas al aplicar -----------------------------------------------------------------------------------

    @Test
    void unaCaidaPasajeraDeLaBaseSeReintentaSinLimiteYElMensajeNoSePierdeNiSeSaltea() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        // Cuatro fallas seguidas: más que los tres intentos de un mensaje que falla por sí mismo.
        falible.fail("request-event-1", 4, () -> new TransientDataAccessResourceException("la base no responde"));
        try (var log = new LogCapture()) {
            enviar(pedido(r, "request-event-1"), confirmada(r, "confirmation-event-1", 8251));

            esperarOffset(base + 2);
            assertThat(falible.calls("request-event-1")).as("4 fallas y el quinto intento aplicó").isEqualTo(5);
            assertThat(leer(r).requestEventId()).isEqualTo("request-event-1");
            assertThat(leer(r).status().name()).as("y el orden se respetó: el segundo se aplicó después").isEqualTo("CONFIRMED");
            assertThat(estacionados()).as("una caída pasajera no estaciona nada").isZero();
            assertThat(log.eventos("kafka.mensaje.reintento")).hasSize(4).allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains("tipo_de_falla=infraestructura_sin_respuesta")
                            .contains("motivo=TransientDataAccessResourceException"));
            assertThat(log.eventos("kafka.mensaje.agotado")).isEmpty();
        }
    }

    @Test
    void unMensajeQueFallaSiempreSeEstacionaConSuDiagnosticoSinEscribirElMensajeDeLaExcepcion() throws Exception {
        var r = esperando();
        long base = offsetConfirmado();
        falible.fail("request-event-malo", Integer.MAX_VALUE, () -> new DataIntegrityViolationException(
                "Failing row contains (Juan, Pérez) Key (event_id)=(request-event-malo)"));
        try (var log = new LogCapture()) {
            enviar(pedido(r, "request-event-malo"), confirmada(r, "confirmation-event-1", 8251));
            falible.fail("confirmation-event-malo", Integer.MAX_VALUE, () -> new DataIntegrityViolationException("otra"));
            enviar(confirmada(r, "confirmation-event-malo", 9999)); // un estacionado al final: debe confirmar su offset

            esperarOffset(base + 3);
            assertThat(falible.calls("request-event-malo")).as("tres intentos").isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT event_id, reason FROM unmatched_event ORDER BY event_id"))
                    .extracting(f -> f.get("event_id") + ":" + f.get("reason"))
                    .containsExactly("confirmation-event-malo:PROCESSING_FAILED", "request-event-malo:PROCESSING_FAILED");
            assertThat(leer(r).status().name()).as("el siguiente mensaje no quedó trabado").isEqualTo("CONFIRMED");
            assertThat(leer(r).requestEventId()).as("el malo no se aplicó").isNull();
            assertThat(log.eventos("kafka.mensaje.agotado")).hasSize(2).allSatisfy(e -> {
                assertThat(e.getLevel().toString()).isEqualTo("ERROR");
                assertThat(e.getFormattedMessage()).contains("excepcion=DataIntegrityViolationException")
                        .contains("motivo=PROCESSING_FAILED");
                assertThat(e.getThrowableProxy()).isNull();
            });
            // Lo que escribe cronos: ni el mensaje de la excepción ni el paciente. (Con todo en TRACE, Hibernate escribe
            // los parámetros que enlaza: es un límite conocido y no lo escribe cronos.)
            assertThat(log.list().stream().filter(e -> e.getLoggerName().startsWith("ar.edu.um.cronos"))
                    .map(e -> e.getFormattedMessage()).toList()).isNotEmpty()
                    .noneMatch(m -> m.contains("Juan") || m.contains("Pérez") || m.contains("Failing row"));
        }
    }
}
