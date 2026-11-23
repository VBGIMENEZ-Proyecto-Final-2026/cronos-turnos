package ar.edu.um.cronos.adapters.in.scheduling;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.reservation.RecoverReservations;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** La repetición de la recuperación: arranca ya, repite, no se corta por una falla y se detiene. */
class RecoverySchedulerTest {

    /** Cuenta las búsquedas del barrido: una por pasada. Si {@code fail} es verdadero, la base falla en cada una. */
    private static final class CountingRepo implements ReservationRepository {
        final AtomicInteger passes = new AtomicInteger();
        volatile boolean fail;

        @Override
        public List<Reservation> findExpiredPending(Instant cutoff, int limit) {
            passes.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("la base no responde");
            }
            return List.of();
        }

        @Override
        public List<Reservation> findStale(ReservationStatus status, Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public Reservation save(Reservation reservation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<Reservation> findById(long id) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Reservation> findByReservationProcessId(String id) {
            return java.util.Optional.empty();
        }

        @Override
        public ar.edu.um.cronos.application.port.out.ReservationPage findByUser(long u, ReservationStatus s, int p, int z) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<Reservation> findActive(long u, long p, java.time.LocalDate d, java.time.LocalTime t) {
            return java.util.Optional.empty();
        }
    }

    private static RecoverReservations recovery(ReservationRepository repo) {
        return new RecoverReservations(repo, null, Clock.fixed(Instant.parse("2026-11-24T14:00:00Z"), ZoneOffset.UTC),
                new RecoverReservations.Settings(Duration.ofMinutes(2), Duration.ofMinutes(2), 10));
    }

    @Test
    void laPrimeraPasadaCorreAlArrancarYDespuesSeRepite() {
        var repo = new CountingRepo();
        var scheduler = new RecoveryScheduler(recovery(repo), Duration.ofMillis(50));

        scheduler.start();
        try {
            assertThat(scheduler.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> repo.passes.get() >= 3);
        } finally {
            scheduler.stop();
        }
        assertThat(scheduler.isRunning()).isFalse();
    }

    @Test
    void laPrimeraPasadaNoEsperaAlIntervalo() {
        var repo = new CountingRepo();
        var scheduler = new RecoveryScheduler(recovery(repo), Duration.ofHours(1));

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> repo.passes.get() == 1);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void unaFallaDeLaBaseNoCortaLaRepeticionYQuedaEnElLog() {
        var repo = new CountingRepo();
        repo.fail = true;
        var scheduler = new RecoveryScheduler(recovery(repo), Duration.ofMillis(50));

        try (LogCapture logs = new LogCapture()) {
            scheduler.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> repo.passes.get() >= 3);
                assertThat(logs.text()).contains("evento=reserva.recuperacion.error");
                repo.fail = false;
                int antes = repo.passes.get();
                await().atMost(Duration.ofSeconds(5)).until(() -> repo.passes.get() >= antes + 2);
            } finally {
                scheduler.stop();
            }
        }
    }

    @Test
    void despuesDeDetenerseNoVuelveACorrer() throws Exception {
        var repo = new CountingRepo();
        var scheduler = new RecoveryScheduler(recovery(repo), Duration.ofMillis(20));
        scheduler.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> repo.passes.get() >= 2);

        scheduler.stop();
        Thread.sleep(100); // lo que ya estaba en curso termina
        int alDetener = repo.passes.get();
        Thread.sleep(200);

        assertThat(repo.passes.get()).isEqualTo(alDetener);
    }

    @Test
    void unaPasadaQueLanzaCualquierCosaNoCancelaLasSiguientes() {
        var llamadas = new AtomicInteger();
        var rota = new RecoverReservations(new CountingRepo(), null, Clock.systemUTC(),
                new RecoverReservations.Settings(Duration.ofMinutes(2), Duration.ofMinutes(2), 10)) {
            @Override
            public Report run() {
                llamadas.incrementAndGet();
                throw new AssertionError("algo que no era ni una excepción");
            }
        };
        var scheduler = new RecoveryScheduler(rota, Duration.ofMillis(50));

        scheduler.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> llamadas.get() >= 3);
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void unIntervaloNoPositivoSeRechaza() {
        assertThatThrownBy(() -> new RecoveryScheduler(recovery(new CountingRepo()), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
