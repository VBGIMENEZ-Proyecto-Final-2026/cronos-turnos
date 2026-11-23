package ar.edu.um.cronos;

import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.reservation.RecoverReservations;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.core.annotation.Order;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * <b>Reinicio con procesos pendientes</b> (issue #13): la ejecución anterior dejó procesos a medias en la base y una
 * aplicación nueva, recién arrancada, los recupera sola, sin que llegue ningún pedido ni ningún evento.
 *
 * <p>La aplicación que arranca es una segunda instancia completa (contexto de Spring nuevo) sobre la misma base real; la
 * cátedra es un servidor HTTP de mentira. Lo que dejó la "ejecución anterior" se arma por el repositorio con fechas de hace
 * una hora, así que ya pasó el tiempo de gracia.
 */
@SpringBootTest
@IntegrationTestSupport
class ReinicioConPendientesTest {

    private static final StubServer CATEDRA = new StubServer();
    private static final LocalDate DIA = LocalDate.now().plusDays(30);

    @Autowired ReservationRepository reservations;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private long ana;

    @BeforeEach
    void preparar() {
        limpiar();
        CATEDRA.reset();
        Instant hace1h = Instant.now().minus(1, ChronoUnit.HOURS);
        ana = users.save(User.register("ana", "$2a$10$hash", "Ana", "Gómez", null, null, "es", hace1h)).id();
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM unmatched_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    @AfterAll
    static void cerrar() {
        CATEDRA.close();
    }

    private Reservation guardar(int hora, java.util.function.UnaryOperator<Reservation> paso) {
        Instant hace1h = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        var turno = new Slot(DIA, LocalTime.of(hora, 0), LocalTime.of(hora, 30));
        var inicial = reservations.save(Reservation.start(ana, 218, "Romina", "Acosta", turno, "Juan", "Pérez", hace1h));
        return reservations.save(paso.apply(inicial));
    }

    private ConfigurableApplicationContext arrancarOtraInstancia() throws Exception {
        HikariDataSource base = dataSource.unwrap(HikariDataSource.class);
        // El inicializador base de las pruebas apaga la recuperación y gana contra los argumentos: por eso la instancia lleva
        // otro inicializador, posterior, que la enciende.
        return new SpringApplicationBuilder(CronosTurnosApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(new TestJwtKeyInitializer(), new RecuperacionEncendida(CATEDRA.url(), base))
                .run();
    }

    /** Apunta la instancia a la base y a la cátedra de mentira y enciende la recuperación con un intervalo corto. */
    @Order(100)
    static class RecuperacionEncendida implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        private final String catedra;
        private final HikariDataSource base;

        RecuperacionEncendida(String catedra, HikariDataSource base) {
            this.catedra = catedra;
            this.base = base;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + base.getJdbcUrl(),
                    "spring.datasource.username=" + base.getUsername(),
                    "spring.datasource.password=" + base.getPassword(),
                    "catedra.rest.base-url=" + catedra,
                    "catedra.rest.max-retries=0",
                    "cronos.recovery.enabled=true",
                    "cronos.recovery.interval=1s").applyTo(context);
        }
    }

    private ReservationStatus estado(Reservation r) {
        return reservations.findById(r.id()).orElseThrow().status();
    }

    @Test
    void unaAplicacionRecienArrancadaRecuperaLosProcesosQueQuedaronAMedias() throws Exception {
        Instant hace1h = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant venceEnDiezMinutos = Instant.now().plus(10, ChronoUnit.MINUTES);
        Reservation sinHold = guardar(9, r -> r);                                                       // se cortó antes del hold
        Reservation sinConfirmar = guardar(10, r -> r.held("hold-1", "process-1", venceEnDiezMinutos, hace1h));
        Reservation vencido = guardar(11, r -> r.held("hold-2", "process-2", hace1h.plusSeconds(600), hace1h)
                .waitingForPhone(hace1h.plusSeconds(600), hace1h));                                       // venció hace 50 min
        Reservation confirmada = guardar(12, r -> r.held("hold-3", "process-3", hace1h.plusSeconds(600), hace1h)
                .waitingForPhone(hace1h.plusSeconds(600), hace1h).confirmed(77L, hace1h));
        CATEDRA.reply(Reply.json(200, "{\"reservationProcessId\":\"process-1\",\"status\":\"WAITING_FOR_PHONE\","
                + "\"expiresAt\":\"" + venceEnDiezMinutos + "\"}"));

        // Los eventos de log de la recuperación se prueban en RecoverReservationsTest: arrancar otra aplicación reconfigura el
        // log y LogCapture deja de ver lo que escribe.
        try (ConfigurableApplicationContext otra = arrancarOtraInstancia()) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(estado(sinHold)).isEqualTo(ReservationStatus.FAILED);
                assertThat(estado(sinConfirmar)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
                assertThat(estado(vencido)).isEqualTo(ReservationStatus.EXPIRED);
            });

            assertThat(reservations.findById(sinHold.id()).orElseThrow().failureCode())
                    .isEqualTo(RecoverReservations.INTERRUPTED);
            assertThat(estado(confirmada)).as("una reserva confirmada no se toca").isEqualTo(ReservationStatus.CONFIRMED);
            // Se reconcilió con la cátedra por el identificador guardado, una sola vez: lo demás no la necesita.
            assertThat(CATEDRA.requests()).hasSize(1);
            assertThat(CATEDRA.requests().get(0).method()).isEqualTo("POST");
            assertThat(CATEDRA.requests().get(0).uri()).isEqualTo("/api/appointment-holds/hold-1/confirm");
        }
    }

    @Test
    void siLaCatedraNoRespondeAlArrancarLaAplicacionArrancaIgualYElProcesoSigueEnHeldHastaQueLaCatedraVuelva()
            throws Exception {
        Instant hace1h = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant venceEnDiezMinutos = Instant.now().plus(10, ChronoUnit.MINUTES);
        Reservation sinConfirmar = guardar(10, r -> r.held("hold-1", "process-1", venceEnDiezMinutos, hace1h));
        Reservation vencido = guardar(11, r -> r.held("hold-2", "process-2", hace1h.plusSeconds(600), hace1h));
        CATEDRA.reply(Reply.json(503, "{}"));

        try (ConfigurableApplicationContext otra = arrancarOtraInstancia()) {
            // El vencido se cierra sin la cátedra; el otro queda HELD y se reintenta en cada pasada, sin cerrarse mal.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertThat(estado(vencido)).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(CATEDRA.requests().size()).isGreaterThanOrEqualTo(4); // dos pasadas, dos intentos cada una
            });
            assertThat(estado(sinConfirmar)).isEqualTo(ReservationStatus.HELD);

            CATEDRA.reply(Reply.json(200, "{\"reservationProcessId\":\"process-1\",\"status\":\"WAITING_FOR_PHONE\","
                    + "\"expiresAt\":\"" + venceEnDiezMinutos + "\"}"));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(estado(sinConfirmar)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE));
        }
    }
}
