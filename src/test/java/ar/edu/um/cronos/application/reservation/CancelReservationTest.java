package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationCancelGateway;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cancelar una reserva propia con dobles: qué se exige, la idempotencia, qué pasa cuando no se sabe si la cátedra canceló, la
 * carrera con el consumidor de Kafka y que el motivo del usuario no se guarda ni se registra.
 */
class CancelReservationTest {

    private static final Instant T0 = Instant.parse("2026-11-22T15:00:00Z");
    private static final Clock AHORA = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String MOTIVO = "Cambio solicitado por el usuario con datos de Juan Pérez";

    /** La cátedra de mentira: responde lo que se le encola (por defecto, cancela) y recuerda cada llamada. */
    private static class FakeCatedra implements ReservationCancelGateway {
        final List<String> processes = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final Deque<Supplier<Cancelled>> replies = new ArrayDeque<>();
        Runnable onCall = () -> { };

        @Override
        public Cancelled cancel(String reservationProcessId, String reason) {
            processes.add(reservationProcessId);
            reasons.add(reason);
            onCall.run();
            return replies.isEmpty() ? new Cancelled(8251, T0) : replies.poll().get();
        }
    }

    private final StartReservationTest.FakeReservations reservations = new StartReservationTest.FakeReservations();
    private final FakeCatedra catedra = new FakeCatedra();
    private final Map<String, User> usuarios = new HashMap<>();
    private CancelReservation caso;
    private int seq;

    private static User usuario(long id, String login, boolean activo) {
        Instant ahora = Instant.parse("2026-01-01T00:00:00Z");
        return new User(id, login, "hash", null, null, null, null, "es", activo, Set.of("ROLE_USER"), ahora, ahora);
    }

    private UserRepository users() {
        return new UserRepository() {
            public Optional<User> findByLogin(String login) { return Optional.ofNullable(usuarios.get(login)); }
            public boolean existsByLogin(String login) { return usuarios.containsKey(login); }
            public boolean existsByEmail(String email) { return false; }
            public User save(User user) { throw new UnsupportedOperationException(); }
        };
    }

    @BeforeEach
    void preparar() {
        usuarios.put("ana", usuario(7, "ana", true));
        usuarios.put("beto", usuario(8, "beto", true));
        usuarios.put("inactivo", usuario(9, "inactivo", false));
        caso = new CancelReservation(users(), reservations, catedra, AHORA);
    }

    private Reservation reserva(long userId, ReservationStatus estado) {
        seq++;
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2026, 12, 1).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0)), "Juan", "Pérez", T0));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, T0.plusSeconds(600), T0));
        if (estado == ReservationStatus.HELD) {
            return r;
        }
        r = reservations.save(r.waitingForPhone(T0.plusSeconds(600), T0));
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8000 + seq, T0));
            case CANCELLED -> reservations.save(r.confirmed(8000 + seq, T0).cancelled(T0));
            case EXPIRED -> reservations.save(r.expired(T0));
            case INVALID -> reservations.save(r.invalid("X", T0));
            case FAILED -> reservations.save(r.failed("CATEDRA_UNAVAILABLE", T0));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    private Reservation confirmada() {
        return reserva(7, ReservationStatus.CONFIRMED);
    }

    // ---- el camino feliz -------------------------------------------------------------------------------------

    @Test
    void cancelaEnLaCatedraYDejaLaReservaCanceladaLocalmente() {
        var r = confirmada();

        var resultado = caso.execute("ana", r.id(), "  Cambio de planes  ");

        assertThat(catedra.processes).containsExactly(r.reservationProcessId());
        assertThat(catedra.reasons).as("sin los espacios de los costados").containsExactly("Cambio de planes");
        assertThat(resultado.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(resultado.catedraReservationId()).as("conserva el id de la cátedra").isEqualTo(r.catedraReservationId());
        assertThat(reservations.rows.get(r.id())).isEqualTo(resultado);
        assertThat(resultado.version()).isEqualTo(r.version() + 1);
    }

    @Test
    void unMotivoVacioOEnBlancoNoSeMandaALaCatedra() {
        var a = confirmada();
        var b = confirmada();
        var c = confirmada();

        caso.execute("ana", a.id(), null);
        caso.execute("ana", b.id(), "");
        caso.execute("ana", c.id(), "   \n  ");

        assertThat(catedra.reasons).containsExactly(null, null, null);
    }

    @Test
    void cancelarUnaReservaYaCanceladaEsIdempotenteYNoLlamaALaCatedra() {
        var cancelada = reserva(7, ReservationStatus.CANCELLED);

        var resultado = caso.execute("ana", cancelada.id(), MOTIVO);

        assertThat(resultado).isEqualTo(cancelada);
        assertThat(catedra.processes).isEmpty();
    }

    @Test
    void cancelarDosVecesSoloLlamaUnaVezALaCatedra() {
        var r = confirmada();

        caso.execute("ana", r.id(), null);
        var segunda = caso.execute("ana", r.id(), null);

        assertThat(segunda.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(catedra.processes).hasSize(1);
    }

    // ---- quién y en qué estado -------------------------------------------------------------------------------

    @Test
    void laReservaDeOtroUsuarioOUnaInexistenteNoSeCancelan() {
        var deBeto = reserva(8, ReservationStatus.CONFIRMED);

        assertThatThrownBy(() -> caso.execute("ana", deBeto.id(), null)).isExactlyInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> caso.execute("ana", 98_765, null)).isExactlyInstanceOf(ReservationNotFoundException.class);

        assertThat(catedra.processes).isEmpty();
        assertThat(reservations.rows.get(deBeto.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void unUsuarioInexistenteOInactivoNoCancelaNada() {
        var r = confirmada();

        assertThatThrownBy(() -> caso.execute("fantasma", r.id(), null)).isInstanceOf(AccountUnavailableException.class);
        assertThatThrownBy(() -> caso.execute("inactivo", r.id(), null)).isInstanceOf(AccountUnavailableException.class);
        assertThat(catedra.processes).isEmpty();
    }

    @Test
    void soloSeCancelaUnaReservaConfirmada() {
        for (var estado : List.of(ReservationStatus.STARTING, ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE,
                ReservationStatus.EXPIRED, ReservationStatus.INVALID, ReservationStatus.FAILED)) {
            var r = reserva(7, estado);

            assertThatThrownBy(() -> caso.execute("ana", r.id(), null)).as(estado.name())
                    .isInstanceOfSatisfying(ReservationNotCancellableException.class, e -> assertThat(e.status()).isEqualTo(estado));
            assertThat(reservations.rows.get(r.id()).status()).isEqualTo(estado);
        }
        assertThat(catedra.processes).as("la cátedra no se entera").isEmpty();
    }

    @Test
    void elMotivoPasaHastaQuinientosCaracteres() {
        var larga = confirmada();
        var justa = confirmada();

        assertThatThrownBy(() -> caso.execute("ana", larga.id(), "x".repeat(CancelReservation.MAX_REASON_LENGTH + 1)))
                .isInstanceOf(InvalidCancellationReasonException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("xxxxx"));
        assertThat(catedra.processes).isEmpty();
        assertThat(reservations.rows.get(larga.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);

        caso.execute("ana", justa.id(), "x".repeat(CancelReservation.MAX_REASON_LENGTH));

        assertThat(catedra.reasons).singleElement().asString().hasSize(500);
    }

    // ---- lo que responde la cátedra ---------------------------------------------------------------------------

    @Test
    void siLaCatedraRechazaLaCancelacionElEstadoLocalNoCambia() {
        var r = confirmada();
        catedra.replies.add(() -> { throw new CatedraRejectedException(CatedraFailure.RESERVATION_NOT_CANCELLABLE, null); });
        try (var log = new LogCapture()) {
            assertThatThrownBy(() -> caso.execute("ana", r.id(), MOTIVO)).isInstanceOfSatisfying(CatedraRejectedException.class,
                    e -> assertThat(e.failure()).isEqualTo(CatedraFailure.RESERVATION_NOT_CANCELLABLE));

            assertThat(reservations.rows.get(r.id())).isEqualTo(r);
            assertThat(catedra.processes).as("un rechazo no se reintenta").hasSize(1);
            assertThat(log.eventos("reserva.cancelacion.rechazada")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("motivo=RESERVATION_NOT_CANCELLABLE")
                            .contains("accion=el_estado_local_no_cambia"));
        }
    }

    @Test
    void siNoSeSabeSiCanceloSeRepiteUnaVezComoReconciliacionYSiSeRecuperaQuedaCancelada() {
        var r = confirmada();
        catedra.replies.add(() -> { throw new CatedraUnavailableException("timeout", null); });
        try (var log = new LogCapture()) {
            var resultado = caso.execute("ana", r.id(), null);

            assertThat(resultado.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(catedra.processes).hasSize(2);
            assertThat(log.eventos("reserva.cancelacion.reconciliando")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("intento=2"));
        }
    }

    @Test
    void siSigueSinSaberseSeFallaYLaReservaSigueConfirmadaYRepetirElPedidoEsSeguro() {
        var r = confirmada();
        for (int i = 0; i < 2; i++) {
            catedra.replies.add(() -> { throw new CatedraUnavailableException("sin respuesta", null); });
        }
        try (var log = new LogCapture()) {
            assertThatThrownBy(() -> caso.execute("ana", r.id(), null)).isInstanceOf(CatedraUnavailableException.class);

            assertThat(catedra.processes).as("el primero y una reconciliación, no más").hasSize(2);
            assertThat(reservations.rows.get(r.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(log.eventos("reserva.cancelacion.incierta")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("intentos=2")
                            .contains("accion=sigue_confirmada_localmente_repetir_el_pedido_es_seguro"));
        }

        // Repetir el pedido cuando la cátedra vuelve: cancela y listo.
        assertThat(caso.execute("ana", r.id(), null).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    // ---- la carrera con el consumidor de Kafka -----------------------------------------------------------------

    @Test
    void siElEventoDeLaCatedraYaLaCancelaronMientrasTantoSeDevuelveComoQuedoSinError() {
        var r = confirmada();
        // El consumidor aplica AppointmentCancelled entre la respuesta REST y el guardado.
        catedra.onCall = () -> reservations.save(reservations.rows.get(r.id()).cancelled(T0));

        var resultado = caso.execute("ana", r.id(), null);

        assertThat(resultado.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservations.rows.get(r.id())).isEqualTo(resultado);
        assertThat(catedra.processes).hasSize(1);
    }

    @Test
    void siElProcesoCambioPorOtraRazonSeVuelveACancelarSobreLaVersionNueva() {
        var r = confirmada();
        // Un guardado ajeno que no cambia el estado pero sí la versión.
        catedra.onCall = () -> reservations.save(reservations.rows.get(r.id()));

        var resultado = caso.execute("ana", r.id(), null);

        assertThat(resultado.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservations.rows.get(r.id())).isEqualTo(resultado);
    }

    @Test
    void siLaBaseFallaDespuesDeQueLaCatedraCanceloLaFallaSubeYRepetirEsSeguro() {
        var r = confirmada();
        var sinGuardar = new StartReservationTest.FakeReservations() {
            boolean fallar = true;

            @Override
            public Reservation save(Reservation reservation) {
                if (fallar && reservation.status() == ReservationStatus.CANCELLED) {
                    throw new IllegalStateException("la base no responde");
                }
                return super.save(reservation);
            }
        };
        sinGuardar.rows.putAll(reservations.rows);
        reservations.history.forEach((id, historia) -> sinGuardar.history.put(id, new ArrayList<>(historia)));
        var otro = new CancelReservation(users(), sinGuardar, catedra, AHORA);

        assertThatThrownBy(() -> otro.execute("ana", r.id(), null)).isInstanceOf(IllegalStateException.class);
        assertThat(sinGuardar.rows.get(r.id()).status()).isEqualTo(ReservationStatus.CONFIRMED);

        sinGuardar.fallar = false;
        assertThat(otro.execute("ana", r.id(), null).status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(catedra.processes).as("la cátedra recibió la cancelación dos veces y es idempotente").hasSize(2);
    }

    // ---- el motivo no se registra ------------------------------------------------------------------------------

    @Test
    void elMotivoDelUsuarioNoApareceEnNingunLogNiMensaje() {
        var a = confirmada();
        var b = confirmada();
        var c = confirmada();
        catedra.replies.add(() -> { throw new CatedraRejectedException(CatedraFailure.RESERVATION_NOT_CANCELLABLE, null); });
        try (var log = new LogCapture()) {
            assertThatThrownBy(() -> caso.execute("ana", a.id(), MOTIVO)).satisfies(e ->
                    assertThat(String.valueOf(e.getMessage())).doesNotContain("Juan"));
            caso.execute("ana", b.id(), MOTIVO);
            assertThatThrownBy(() -> caso.execute("ana", c.id(), "x".repeat(600) + "Pérez")).satisfies(e ->
                    assertThat(e.getMessage()).doesNotContain("Pérez"));

            assertThat(log.text()).doesNotContain("Juan").doesNotContain("Pérez").doesNotContain("datos de");
            assertThat(log.eventos("reserva.cancelacion.confirmada")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("usuario=7").contains("estado=CANCELLED"));
            assertThat(log.eventos("reserva.cancelacion.denegada")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("motivo=motivo_demasiado_largo"));
        }
    }
}
