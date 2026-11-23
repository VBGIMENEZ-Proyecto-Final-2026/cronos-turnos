package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.reservation.RecoverReservations.Report;
import ar.edu.um.cronos.application.reservation.StartReservationTest.FakeReservations;
import ar.edu.um.cronos.application.reservation.StartReservationTest.ScriptedHolds;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La recuperación y el barrido de vencimiento con dobles (issue #13): cada uno de los tres pasos, qué se toca y qué no,
 * qué pasa cuando la cátedra no responde y que ningún proceso quede en un estado del que no se pueda salir.
 */
class RecoverReservationsTest {

    private static final Instant T0 = Instant.parse("2026-11-24T14:00:00Z");
    private static final Duration STALE = Duration.ofMinutes(2);
    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final Slot TURNO = new Slot(LocalDate.of(2026, 12, 1), LocalTime.of(15, 30), LocalTime.of(16, 0));

    /** Un reloj que el test adelanta a mano. */
    static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private FakeReservations reservations;
    private ScriptedHolds holds;
    private RecoverReservations recovery;

    @BeforeEach
    void preparar() {
        clock = new MutableClock(T0);
        reservations = new FakeReservations();
        holds = new ScriptedHolds();
        recovery = armar(reservations);
    }

    private RecoverReservations armar(FakeReservations repo) {
        // StartReservation solo usa acá el repositorio, la cátedra y el reloj: no hay usuarios ni catálogo en una recuperación.
        StartReservation starter = new StartReservation(null, null, holds, repo, clock, ZoneOffset.UTC);
        return new RecoverReservations(repo, starter, clock, new RecoverReservations.Settings(STALE, GRACE, 100));
    }

    // ---- armar procesos en cada estado ---------------------------------------------------------------------------

    private Reservation starting(long user) {
        return reservations.save(Reservation.start(user, 218, "Romina", "Acosta", TURNO, "Paciente", "Prueba", clock.instant()));
    }

    private Reservation held(long user, Instant expiresAt) {
        Reservation s = starting(user);
        return reservations.save(s.held("hold-" + s.id(), "process-" + s.id(), expiresAt, clock.instant()));
    }

    private Reservation waiting(long user, Instant expiresAt) {
        return reservations.save(held(user, expiresAt).waitingForPhone(expiresAt, clock.instant()));
    }

    private ReservationStatus status(Reservation r) {
        return reservations.findById(r.id()).orElseThrow().status();
    }

    private void pasa(Duration d) {
        clock.advance(d);
    }

    // ---- 1. barrido de vencimiento -------------------------------------------------------------------------------

    @Test
    void unProcesoVencidoSeCierraSinQueLlegueElEventoYSinLlamarALaCatedra() {
        Reservation enHold = held(1, T0.plusSeconds(600));
        Reservation esperando = waiting(2, T0.plusSeconds(600));
        pasa(Duration.ofMinutes(10).plus(GRACE).plusSeconds(1));

        Report report = recovery.run();

        assertThat(status(enHold)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(esperando)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(report.expired()).isEqualTo(2);
        assertThat(holds.calls).as("el barrido no necesita a la cátedra").isEmpty();
    }

    @Test
    void sinPasarLaGraciaNoSeCierraNada() {
        Reservation esperando = waiting(1, T0.plusSeconds(600));
        pasa(Duration.ofMinutes(10).plus(GRACE).minusSeconds(1));

        Report report = recovery.run();

        assertThat(status(esperando)).as("la cátedra todavía puede estar mandando su resultado")
                .isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(report.expired()).isZero();
    }

    @Test
    void unaReservaConfirmadaNoVenceNuncaAunqueSuVencimientoPase() {
        Reservation w = waiting(1, T0.plusSeconds(600));
        Reservation confirmada = reservations.save(w.confirmed(55L, clock.instant()));
        pasa(Duration.ofDays(2));

        recovery.run();

        assertThat(status(confirmada)).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void siOtroEscritorLaConfirmoALaVezElBarridoNoLaPisaConVencida() {
        Reservation esperando = waiting(1, T0.plusSeconds(600));
        FakeReservations conCarrera = new FakeReservations() {
            boolean once;

            @Override
            public Reservation save(Reservation r) {
                if (!once && r.id() != null && r.status() == ReservationStatus.EXPIRED) {
                    once = true;
                    // El consumidor de Kafka aplica AppointmentConfirmed justo antes de que el barrido guarde.
                    super.save(rows.get(r.id()).confirmed(55L, clock.instant()));
                    throw new ReservationConflictException(null);
                }
                return super.save(r);
            }
        };
        conCarrera.rows.put(esperando.id(), esperando);
        conCarrera.history.put(esperando.id(), new java.util.ArrayList<>(java.util.List.of(esperando.status())));
        pasa(Duration.ofMinutes(10).plus(GRACE).plusSeconds(1));

        try (LogCapture logs = new LogCapture()) {
            Report report = armar(conCarrera).run();

            assertThat(conCarrera.rows.get(esperando.id()).status())
                    .as("una reserva confirmada por la cátedra no puede quedar vencida")
                    .isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(report.expired()).isZero();
            // Se decide con lo que quedó, sin intentar una transición ilegal que termine en un error registrado.
            assertThat(logs.text()).contains("evento=reserva.recuperacion.carrera")
                    .doesNotContain("evento=reserva.recuperacion.error");
        }
    }

    @Test
    void siElConflictoPersisteElProcesoQuedaParaLaProximaPasada() {
        Reservation esperando = waiting(1, T0.plusSeconds(600));
        FakeReservations siempreChoca = new FakeReservations() {
            @Override
            public Reservation save(Reservation r) {
                if (r.id() != null && r.status() == ReservationStatus.EXPIRED) {
                    throw new ReservationConflictException(null);
                }
                return super.save(r);
            }
        };
        siempreChoca.rows.put(esperando.id(), esperando);
        siempreChoca.history.put(esperando.id(), new java.util.ArrayList<>());
        pasa(Duration.ofMinutes(10).plus(GRACE).plusSeconds(1));

        Report report = armar(siempreChoca).run();

        assertThat(siempreChoca.rows.get(esperando.id()).status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(report.expired()).isZero();
    }

    // ---- 2. procesos que nunca llegaron a tener hold -------------------------------------------------------------

    @Test
    void unProcesoStartingAbandonadoPasaAFailedConUnMotivoEstable() {
        Reservation viejo = starting(1);
        pasa(STALE.plusSeconds(1));
        Reservation reciente = starting(2);

        Report report = recovery.run();

        Reservation cerrado = reservations.findById(viejo.id()).orElseThrow();
        assertThat(cerrado.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(cerrado.failureCode()).isEqualTo(RecoverReservations.INTERRUPTED);
        assertThat(status(reciente)).as("un pedido recién guardado puede estar en curso").isEqualTo(ReservationStatus.STARTING);
        assertThat(report.interrupted()).isEqualTo(1);
        assertThat(holds.calls).isEmpty();
    }

    // ---- 3. holds sin confirmar ----------------------------------------------------------------------------------

    @Test
    void unHoldSinConfirmarSeReconciliaRepitiendoLaConfirmacionConLosIdentificadoresGuardados() {
        Reservation viejo = held(7, T0.plusSeconds(600));
        pasa(STALE.plusSeconds(1));

        Report report = recovery.run();

        assertThat(status(viejo)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(report.reconciled()).isEqualTo(1);
        assertThat(holds.confirmArgs).hasSize(1);
        assertThat(holds.confirmArgs.get(0)).containsExactly("hold-" + viejo.id(), "user-7", "Paciente", "Prueba");
    }

    @Test
    void siLaCatedraYaLoHabiaConfirmadoRepetirLaConfirmacionCuentaComoExito() {
        Reservation viejo = held(1, T0.plusSeconds(600));
        holds.confirms.add(() -> {
            throw new CatedraRejectedException(CatedraFailure.RESERVATION_PROCESS_ALREADY_CONFIRMED, null);
        });
        pasa(STALE.plusSeconds(1));

        recovery.run();

        assertThat(status(viejo)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void siLaCatedraDiceQueElHoldYaNoExisteElProcesoSeCierraComoVencido() {
        Reservation viejo = held(1, T0.plusSeconds(600));
        holds.confirms.add(() -> {
            throw new CatedraRejectedException(CatedraFailure.HOLD_EXPIRED, null);
        });
        pasa(STALE.plusSeconds(1));

        Report report = recovery.run();

        assertThat(status(viejo)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(report.closedByCatedra()).isEqualTo(1);
    }

    @Test
    void unHoldRecienGuardadoNoSeToca() {
        Reservation reciente = held(1, T0.plusSeconds(600));
        pasa(STALE.minusSeconds(1));

        recovery.run();

        assertThat(status(reciente)).isEqualTo(ReservationStatus.HELD);
        assertThat(holds.calls).isEmpty();
    }

    // ---- la cátedra no responde ----------------------------------------------------------------------------------

    @Test
    void conLaCatedraCaidaLosReintentosTienenLimiteYLaPasadaSeCorta() {
        Reservation a = held(1, T0.plusSeconds(600));
        Reservation b = held(2, T0.plusSeconds(600));
        pasa(STALE.plusSeconds(1));
        for (int i = 0; i < 10; i++) {
            holds.confirms.add(() -> {
                throw new CatedraUnavailableException("sin respuesta", null);
            });
        }

        Report report = recovery.run();

        assertThat(holds.count("confirmHold")).as("el primer intento y una reconciliación, y no se prueba con los demás")
                .isEqualTo(2);
        assertThat(report.catedraUnavailable()).isTrue();
        assertThat(report.stillHeld()).isEqualTo(2);
        assertThat(status(a)).isEqualTo(ReservationStatus.HELD);
        assertThat(status(b)).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void laPasadaSiguienteReintentaYCuandoLaCatedraVuelveSeReconcilianTodos() {
        Reservation a = held(1, T0.plusSeconds(600));
        Reservation b = held(2, T0.plusSeconds(600));
        pasa(STALE.plusSeconds(1));
        holds.confirms.add(() -> {
            throw new CatedraUnavailableException("sin respuesta", null);
        });
        holds.confirms.add(() -> {
            throw new CatedraUnavailableException("sin respuesta", null);
        });
        recovery.run();

        pasa(Duration.ofSeconds(30));
        Report segunda = recovery.run();

        assertThat(status(a)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(status(b)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(segunda.reconciled()).isEqualTo(2);
    }

    @Test
    void conLaCatedraCaidaParaSiempreElProcesoNoSeQuedaColgadoElBarridoLoCierraAlVencer() {
        Reservation a = held(1, T0.plusSeconds(600));
        for (int i = 0; i < 1000; i++) {
            holds.confirms.add(() -> {
                throw new CatedraUnavailableException("sin respuesta", null);
            });
        }
        pasa(STALE.plusSeconds(1));
        recovery.run();
        assertThat(status(a)).isEqualTo(ReservationStatus.HELD);

        pasa(Duration.ofMinutes(10).plus(GRACE));
        recovery.run();

        assertThat(status(a)).as("sin la cátedra igual se cierra: el vencimiento no la necesita")
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    // ---- robustez ------------------------------------------------------------------------------------------------

    @Test
    void ningunEstadoNoFinalQuedaSinSalidaDespuesDeUnaPasadaConLaCatedraCaida() {
        // Un proceso en cada estado no final posible, con la cátedra caída y el reloj mucho después de todo vencimiento.
        Reservation s = starting(1);
        Reservation h = held(2, T0.plusSeconds(600));
        Reservation w = waiting(3, T0.plusSeconds(600));
        Reservation c = reservations.save(waiting(4, T0.plusSeconds(600)).confirmed(9L, clock.instant()));
        for (int i = 0; i < 1000; i++) {
            holds.confirms.add(() -> {
                throw new CatedraUnavailableException("sin respuesta", null);
            });
        }
        pasa(Duration.ofDays(1));

        recovery.run();

        Set<ReservationStatus> quedan = EnumSet.noneOf(ReservationStatus.class);
        for (Reservation r : new Reservation[]{s, h, w, c}) {
            quedan.add(status(r));
        }
        assertThat(status(s)).isEqualTo(ReservationStatus.FAILED);
        assertThat(status(h)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(w)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(status(c)).as("confirmada: sigue, y su salida es cancelar").isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(quedan).allSatisfy(st -> assertThat(st.isFinal() || st == ReservationStatus.CONFIRMED).isTrue());
    }

    @Test
    void unaSegundaPasadaSeguidaNoCambiaNada() {
        held(1, T0.plusSeconds(600));
        waiting(2, T0.plusSeconds(600));
        starting(3);
        pasa(Duration.ofDays(1));
        recovery.run();
        int llamadas = holds.calls.size();
        long versiones = reservations.rows.values().stream().mapToLong(Reservation::version).sum();

        Report segunda = recovery.run();

        assertThat(segunda.changedAnything()).isFalse();
        assertThat(holds.calls).hasSize(llamadas);
        assertThat(reservations.rows.values().stream().mapToLong(Reservation::version).sum()).isEqualTo(versiones);
    }

    @Test
    void unaFallaInesperadaEnUnProcesoNoImpideRecuperarLosDemas() {
        Reservation roto = held(1, T0.plusSeconds(600));
        Reservation sano = held(2, T0.plusSeconds(600));
        pasa(STALE.plusSeconds(1));
        holds.confirms.add(() -> {
            throw new IllegalStateException("falla inesperada");
        });

        try (LogCapture logs = new LogCapture()) {
            Report report = recovery.run();

            assertThat(status(roto)).isEqualTo(ReservationStatus.HELD);
            assertThat(status(sano)).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
            assertThat(report.reconciled()).isEqualTo(1);
            assertThat(logs.text()).contains("evento=reserva.recuperacion.error");
        }
    }

    @Test
    void cadaDecisionQuedaEnElLogSinDatosPersonales() {
        starting(1);
        held(2, T0.plusSeconds(600));
        waiting(3, T0.plusSeconds(60));
        pasa(Duration.ofMinutes(5));
        try (LogCapture logs = new LogCapture()) {
            recovery.run();

            String text = logs.textExcluding("org.", "com.", "ch.");
            assertThat(text).contains("evento=reserva.proceso.vencido_por_barrido")
                    .contains("evento=reserva.recuperacion.sin_hold")
                    .contains("evento=reserva.recuperacion.pasada");
            assertThat(text).doesNotContain("Paciente").doesNotContain("Prueba").doesNotContain("user-");
        }
    }
}
