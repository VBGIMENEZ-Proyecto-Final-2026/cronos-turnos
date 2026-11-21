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
 * La migración V2 y el adaptador de reservas contra un PostgreSQL real: lo que la base garantiza por su cuenta
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
        var confirmada = reservations.save(esperando.moveTo(ReservationStatus.CONFIRMED, T0.plusSeconds(3)));
        assertThat(reservations.findActive(ana, 218, DIA, LocalTime.of(14, 30))).isPresent();

        reservations.save(confirmada.moveTo(ReservationStatus.CANCELLED, T0.plusSeconds(4)));

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
