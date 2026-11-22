package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.application.port.out.ActiveReservationExistsException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Las migraciones V2 y V3 y el adaptador de reservas contra un PostgreSQL real: lo que la base garantiza por su cuenta
 * (índice único parcial, versiones, restricciones) además de lo que el adaptador traduce.
 */
@SpringBootTest
@IntegrationTestSupport
class JpaReservationRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-21T15:00:00Z");
    private static final Instant VENCE = Instant.parse("2026-11-24T15:00:00Z");
    private static final LocalDate DIA = LocalDate.of(2026, 11, 24);
    private static final Slot TURNO = new Slot(DIA, LocalTime.of(14, 30), LocalTime.of(15, 0));

    @Autowired
    ReservationRepository reservations;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private long ana;
    private long beto;

    @BeforeEach
    void preparar() {
        limpiar();
        ana = users.save(User.register("ana", "$2a$10$hash", "Ana", "Gómez", null, null, "es", T0)).id();
        beto = users.save(User.register("beto", "$2a$10$hash", "Beto", "Ruiz", null, null, "es", T0)).id();
    }

    @AfterEach
    void limpiar() {
        // Otras pruebas borran usuarios: las reservas se van antes (la base no deja borrar un usuario con reservas).
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM unmatched_event");
        jdbc.update("DELETE FROM reservation_process");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    private Reservation nueva(long userId, Slot turno) {
        return Reservation.start(userId, 218, "Romina", "Acosta", turno, "Juan", "Pérez", T0);
    }

    private Reservation nueva(long userId) {
        return nueva(userId, TURNO);
    }

    private Reservation conHold(Reservation r, String hold, String proceso) {
        return r.held(hold, proceso, VENCE, T0.plusSeconds(1));
    }

    // ---- guardar y leer -------------------------------------------------------------------------------------

    @Test
    void guardaUnProcesoNuevoYLoLeeIgual() {
        var guardado = reservations.save(nueva(ana));

        assertThat(guardado.id()).isNotNull();
        assertThat(guardado.version()).isZero();
        var leido = reservations.findById(guardado.id()).orElseThrow();
        assertThat(leido.userId()).isEqualTo(ana);
        assertThat(leido.professionalId()).isEqualTo(218);
        assertThat(leido.professionalFirstName()).isEqualTo("Romina");
        assertThat(leido.professionalLastName()).isEqualTo("Acosta");
        assertThat(leido.date()).isEqualTo(DIA);
        assertThat(leido.startTime()).isEqualTo(LocalTime.of(14, 30));
        assertThat(leido.endTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(leido.patientFirstName()).isEqualTo("Juan");
        assertThat(leido.patientLastName()).isEqualTo("Pérez");
        assertThat(leido.status()).isEqualTo(ReservationStatus.STARTING);
        assertThat(leido.holdId()).isNull();
        assertThat(leido.reservationProcessId()).isNull();
        assertThat(leido.expiresAt()).isNull();
        assertThat(leido.createdAt()).isEqualTo(T0);
    }

    @Test
    void actualizaElProcesoYLaVersionSube() {
        var inicial = reservations.save(nueva(ana));

        var held = reservations.save(conHold(inicial, "hold-1", "process-1"));
        var esperando = reservations.save(held.waitingForPhone(VENCE.plusSeconds(60), T0.plusSeconds(2)));

        assertThat(held.version()).isEqualTo(1);
        assertThat(esperando.version()).isEqualTo(2);
        var leido = reservations.findById(inicial.id()).orElseThrow();
        assertThat(leido.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(leido.holdId()).isEqualTo("hold-1");
        assertThat(leido.reservationProcessId()).isEqualTo("process-1");
        assertThat(leido.expiresAt()).isEqualTo(VENCE.plusSeconds(60));
        assertThat(leido.updatedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(reservations.findById(inicial.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_process", Integer.class)).isEqualTo(1);
    }

    @Test
    void unaEscrituraConUnaVersionViejaSeRechazaEnLugarDePisarElCambioDeOtro() {
        var inicial = reservations.save(nueva(ana));
        reservations.save(conHold(inicial, "hold-1", "process-1")); // otro la cambió

        assertThatThrownBy(() -> reservations.save(inicial.failed("X", T0.plusSeconds(5))))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(reservations.findById(inicial.id()).orElseThrow().status()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void unIdInexistenteNoSeEncuentra() {
        assertThat(reservations.findById(999_999)).isEmpty();
    }

    // ---- un solo proceso activo por turno --------------------------------------------------------------------

    @Test
    void laBaseNoDejaDosProcesosActivosDelMismoUsuarioParaElMismoTurno() {
        reservations.save(nueva(ana));

        assertThatThrownBy(() -> reservations.save(nueva(ana)))
                .isInstanceOf(ActiveReservationExistsException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_process", Integer.class)).isEqualTo(1);
    }

    @Test
    void unProcesoTerminadoMalNoImpideVolverAIntentarElMismoTurno() {
        var primero = reservations.save(nueva(ana));
        reservations.save(primero.failed("SLOT_ALREADY_HELD", T0.plusSeconds(1)));

        var segundo = reservations.save(nueva(ana));

        assertThat(segundo.id()).isNotEqualTo(primero.id());
    }

    @Test
    void unProcesoExpiradoTampocoImpideVolverAIntentar() {
        var held = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1"));
        reservations.save(held.expired(T0.plusSeconds(9)));

        assertThat(reservations.save(nueva(ana)).id()).isNotNull();
    }

    @Test
    void otroUsuarioPuedePedirElMismoTurnoYOtraHoraEsOtroTurno() {
        reservations.save(nueva(ana));

        reservations.save(nueva(beto));
        reservations.save(nueva(ana, new Slot(DIA, LocalTime.of(15, 0), LocalTime.of(15, 30))));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_process", Integer.class)).isEqualTo(3);
    }

    @Test
    void findActiveDevuelveElProcesoQueOcupaElTurnoSoloDeSuDueno() {
        var guardado = reservations.save(nueva(ana));

        assertThat(reservations.findActive(ana, 218, DIA, LocalTime.of(14, 30))).get()
                .extracting(Reservation::id).isEqualTo(guardado.id());
        assertThat(reservations.findActive(beto, 218, DIA, LocalTime.of(14, 30))).isEmpty();
        assertThat(reservations.findActive(ana, 218, DIA, LocalTime.of(15, 0))).isEmpty();
        assertThat(reservations.findActive(ana, 219, DIA, LocalTime.of(14, 30))).isEmpty();
        assertThat(reservations.findActive(ana, 218, DIA.plusDays(1), LocalTime.of(14, 30))).isEmpty();
    }

    @Test
    void findActiveIgnoraLosProcesosFinalesPeroNoLosConfirmados() {
        var held = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1"));
        var esperando = reservations.save(held.waitingForPhone(VENCE, T0.plusSeconds(2)));
        var confirmada = reservations.save(esperando.confirmed(8251, T0.plusSeconds(3)));
        assertThat(reservations.findActive(ana, 218, DIA, LocalTime.of(14, 30))).isPresent();

        reservations.save(confirmada.cancelled(T0.plusSeconds(4)));

        assertThat(reservations.findActive(ana, 218, DIA, LocalTime.of(14, 30))).isEmpty();
    }

    // ---- lo que la base garantiza por su cuenta ----------------------------------------------------------------

    @Test
    void elHoldYElProcesoDeLaCatedraNoSePuedenRepetirEntreProcesos() {
        reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1"));
        var otro = reservations.save(nueva(beto));

        assertThatThrownBy(() -> reservations.save(conHold(otro, "hold-1", "process-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reservations.save(conHold(otro, "hold-2", "process-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void variosProcesosSinHoldTodaviaConviven() {
        reservations.save(nueva(ana));
        reservations.save(nueva(beto));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation_process WHERE hold_id IS NULL", Integer.class)).isEqualTo(2);
    }

    @Test
    void laBaseRechazaUnEstadoQueNoExiste() {
        var guardado = reservations.save(nueva(ana));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET status = 'INVENTADO' WHERE id = ?", guardado.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- V3: eventos de la cátedra ---------------------------------------------------------------------------

    @Test
    void guardaYLeeLosDatosQueInformaLaCatedra() {
        var esperando = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1")
                .waitingForPhone(VENCE, T0.plusSeconds(2)));
        var pedido = reservations.save(esperando.informationRequested("request-event-1", T0.plusSeconds(3)));
        var rechazado = reservations.save(pedido.informationRejected("INVALID_PHONE_NUMBER", T0.plusSeconds(4)));

        var leido = reservations.findById(rechazado.id()).orElseThrow();
        assertThat(leido.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(leido.requestEventId()).isEqualTo("request-event-1");
        assertThat(leido.lastRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(leido.catedraReservationId()).isNull();

        var confirmada = reservations.save(leido.confirmed(8251, T0.plusSeconds(5)));
        var releida = reservations.findById(confirmada.id()).orElseThrow();
        assertThat(releida.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(releida.catedraReservationId()).isEqualTo(8251L);
        assertThat(releida.requestEventId()).isEqualTo("request-event-1");
    }

    @Test
    void guardaCuandoSeMandoElTelefonoYUnRechazoDeLaCatedraLoLibera() {
        var esperando = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1")
                .waitingForPhone(VENCE, T0.plusSeconds(2)).informationRequested("request-event-1", T0.plusSeconds(3)));
        assertThat(esperando.phoneSubmittedAt()).isNull();

        var enviado = reservations.save(esperando.phoneSubmitted(T0.plusSeconds(4)));

        assertThat(reservations.findById(enviado.id()).orElseThrow().phoneSubmittedAt()).isEqualTo(T0.plusSeconds(4));
        var liberado = reservations.save(enviado.informationRejected("INVALID_PHONE_NUMBER", T0.plusSeconds(5)));
        assertThat(reservations.findById(liberado.id()).orElseThrow().phoneSubmittedAt()).isNull();
    }

    // ---- las reservas de un usuario (V2 índice user_id, created_at DESC) -----------------------------------------

    private Reservation propia(long userId, int dia, long segundos, ReservationStatus estado) {
        var turno = new Slot(DIA.plusDays(dia), LocalTime.of(14, 30), LocalTime.of(15, 0));
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta", turno, "Juan", "Pérez",
                T0.plusSeconds(segundos)));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-" + userId + "-" + dia, "process-" + userId + "-" + dia, VENCE, T0.plusSeconds(segundos)));
        r = reservations.save(r.waitingForPhone(VENCE, T0.plusSeconds(segundos)));
        return estado == ReservationStatus.WAITING_FOR_PHONE ? r
                : reservations.save(r.confirmed(9000 + dia + userId, T0.plusSeconds(segundos)));
    }

    @Test
    void findByUserDevuelveSoloLasDelUsuarioLaMasRecientePrimeroYAIgualFechaElIdMayor() {
        var vieja = propia(ana, 1, 10, ReservationStatus.CONFIRMED);
        var nueva = propia(ana, 2, 30, ReservationStatus.CONFIRMED);
        var empate1 = propia(ana, 3, 20, ReservationStatus.CONFIRMED);
        var empate2 = propia(ana, 4, 20, ReservationStatus.CONFIRMED);
        var deBeto = propia(beto, 5, 40, ReservationStatus.CONFIRMED);

        var pagina = reservations.findByUser(ana, null, 0, 20);

        assertThat(pagina.items()).extracting(Reservation::id)
                .containsExactly(nueva.id(), empate2.id(), empate1.id(), vieja.id()).doesNotContain(deBeto.id());
        assertThat(pagina.totalItems()).isEqualTo(4);
        assertThat(reservations.findByUser(beto, null, 0, 20).items()).extracting(Reservation::id).containsExactly(deBeto.id());
    }

    @Test
    void findByUserFiltraPorEstadoYPaginaConElTotalCorrecto() {
        for (int i = 0; i < 5; i++) {
            propia(ana, i, i, ReservationStatus.CONFIRMED);
        }
        propia(ana, 9, 9, ReservationStatus.WAITING_FOR_PHONE);

        assertThat(reservations.findByUser(ana, ReservationStatus.WAITING_FOR_PHONE, 0, 20).totalItems()).isEqualTo(1);
        var confirmadas = reservations.findByUser(ana, ReservationStatus.CONFIRMED, 0, 2);
        assertThat(confirmadas.items()).hasSize(2);
        assertThat(confirmadas.totalItems()).isEqualTo(5);
        assertThat(confirmadas.totalPages()).isEqualTo(3);
        assertThat(reservations.findByUser(ana, ReservationStatus.CONFIRMED, 2, 2).items()).hasSize(1);
        assertThat(reservations.findByUser(ana, ReservationStatus.CONFIRMED, 7, 2).items()).isEmpty();
        assertThat(reservations.findByUser(ana, ReservationStatus.EXPIRED, 0, 20).items()).isEmpty();
    }

    @Test
    void ningunaTablaTieneUnaColumnaDondeGuardarElNumeroDeTelefono() {
        // El teléfono es un dato personal: se manda a la cátedra y no se guarda (decisión D3 del plan de Kafka). Lo único que
        // existe es CUÁNDO se mandó. Si alguien agregara una columna para el número, esta prueba falla y obliga a decidirlo.
        var columnas = jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND (column_name ILIKE '%phone%' OR column_name ILIKE '%telefono%' "
                + "OR column_name ILIKE '%celular%' OR column_name ILIKE '%mobile%')", String.class);

        assertThat(columnas).containsExactly("reservation_process.phone_submitted_at");
    }

    @Test
    void laBaseYaNoAceptaRejectedComoEstado() {
        var guardado = reservations.save(nueva(ana));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET status = 'REJECTED' WHERE id = ?", guardado.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unaReservaConfirmadaOCanceladaSabeComoLaLlamaLaCatedra() {
        var esperando = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1")
                .waitingForPhone(VENCE, T0.plusSeconds(2)));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET status = 'CONFIRMED' WHERE id = ?", esperando.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET status = 'CANCELLED' WHERE id = ?", esperando.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dosProcesosNoPuedenTenerLaMismaReservaDeLaCatedra() {
        var uno = reservations.save(conHold(reservations.save(nueva(ana)), "hold-1", "process-1")
                .waitingForPhone(VENCE, T0.plusSeconds(2)).confirmed(8251, T0.plusSeconds(3)));
        var otro = reservations.save(conHold(reservations.save(nueva(beto)), "hold-2", "process-2")
                .waitingForPhone(VENCE, T0.plusSeconds(2)));

        assertThat(uno.catedraReservationId()).isEqualTo(8251L);
        assertThatThrownBy(() -> reservations.save(otro.confirmed(8251, T0.plusSeconds(4))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unEventoYaProcesadoNoSePuedeRegistrarDeNuevo() {
        String insertar = "INSERT INTO processed_event (event_id, event_type, reservation_process_id, processed_at) "
                + "VALUES (?, 'AppointmentConfirmed', 'process-1', now())";
        jdbc.update(insertar, "confirmation-event-1");

        assertThatThrownBy(() -> jdbc.update(insertar, "confirmation-event-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // ON CONFLICT DO NOTHING es lo que usará el consumidor para decidir "ya procesado" sin lanzar nada.
        assertThat(jdbc.update(insertar + " ON CONFLICT (event_id) DO NOTHING", "confirmation-event-1")).isZero();
        assertThat(jdbc.update(insertar + " ON CONFLICT (event_id) DO NOTHING", "confirmation-event-2")).isEqualTo(1);
    }

    @Test
    void unEventoSinProcesoSeEstacionaUnaSolaVez() {
        String insertar = "INSERT INTO unmatched_event (event_id, event_type, reservation_process_id, reason, "
                + "received_at) VALUES (?, 'AppointmentProcessExpired', 'process-viejo', 'UNKNOWN_PROCESS', now()) "
                + "ON CONFLICT (event_id) DO NOTHING";

        assertThat(jdbc.update(insertar, "process-event-1")).isEqualTo(1);
        assertThat(jdbc.update(insertar, "process-event-1")).as("el mismo evento leído de nuevo").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM unmatched_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void laBaseRechazaUnTurnoQueTerminaAntesDeEmpezar() {
        var guardado = reservations.save(nueva(ana));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET end_time = start_time WHERE id = ?", guardado.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseRechazaUnProcesoConHoldPeroSinSaberComoLoLlamaLaCatedra() {
        var guardado = reservations.save(nueva(ana));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reservation_process SET status = 'HELD' WHERE id = ?", guardado.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elIdLoDecideLaBaseNuncaQuienInserta() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reservation_process (id, user_id, professional_id, "
                + "appointment_date, start_time, end_time, patient_first_name, patient_last_name, status, "
                + "created_at, updated_at) VALUES (1, ?, 1, '2026-11-24', '10:00', '10:30', 'Juan', 'Pérez', "
                + "'STARTING', now(), now())", ana)).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("cannot insert a non-DEFAULT value into column \"id\"");
    }

    @Test
    void unProcesoSiempreTieneUnUsuarioQueExiste() {
        assertThatThrownBy(() -> reservations.save(nueva(987_654))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noSeBorraUnUsuarioQueTieneReservas() {
        reservations.save(nueva(ana));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_authority WHERE user_id = ?; "
                + "DELETE FROM app_user WHERE id = ?", ana, ana)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, ana)).isEqualTo(1);
    }
}
