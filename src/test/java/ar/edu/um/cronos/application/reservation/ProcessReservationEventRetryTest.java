package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.ProcessedEvents;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.TransactionRunner;
import ar.edu.um.cronos.application.port.out.UnmatchedEvents;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent.Outcome;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationEvent;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Qué hace {@link ProcessReservationEvent} cuando otro escritor cambia el proceso a la vez (por ejemplo, el pedido de
 * reserva que está guardando su paso a {@code WAITING_FOR_PHONE}): reintenta entero y, mientras no lo logre, no deja
 * nada marcado. Con dobles que respetan el deshacer de una transacción.
 */
class ProcessReservationEventRetryTest {

    private static final Instant T0 = Instant.parse("2026-11-21T15:00:00Z");

    /** El proceso guardado y cuántas veces la próxima escritura va a perder contra otro escritor. */
    private static class Reservations implements ReservationRepository {
        Reservation stored;
        int conflictsLeft;
        int saveAttempts;

        @Override
        public Reservation save(Reservation reservation) {
            saveAttempts++;
            if (conflictsLeft > 0) {
                conflictsLeft--;
                throw new ReservationConflictException(null);
            }
            stored = reservation;
            return reservation;
        }

        @Override
        public Optional<Reservation> findById(long id) {
            return Optional.ofNullable(stored);
        }

        @Override
        public ar.edu.um.cronos.application.port.out.ReservationPage findByUser(long userId, ReservationStatus status,
                                                                               int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> findByReservationProcessId(String id) {
            return Optional.ofNullable(stored).filter(r -> id.equals(r.reservationProcessId()));
        }

        @Override
        public Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime start) {
            return Optional.empty();
        }
    }

    /** Un {@code TransactionRunner} que, si el bloque falla, deshace lo que se marcó como procesado. */
    private static class RollingBack implements TransactionRunner, ProcessedEvents {
        final Set<String> marked = new HashSet<>();

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            Set<String> before = new HashSet<>(marked);
            try {
                return work.get();
            } catch (RuntimeException e) {
                marked.clear();
                marked.addAll(before);
                throw e;
            }
        }

        @Override
        public boolean markProcessed(String eventId, String eventType, String reservationProcessId, Instant at) {
            return marked.add(eventId);
        }
    }

    private final Reservations reservations = new Reservations();
    private final RollingBack transaction = new RollingBack();
    private ProcessReservationEvent caso;
    private ReservationEvent.AppointmentConfirmed evento;

    @BeforeEach
    void preparar() {
        var turno = new Slot(LocalDate.of(2026, 11, 24), LocalTime.of(14, 30), LocalTime.of(15, 0));
        reservations.stored = Reservation.start(7, 218, "Romina", "Acosta", turno, "Juan", "Pérez", T0)
                .held("hold-1", "process-1", T0.plusSeconds(600), T0).waitingForPhone(null, T0);
        UnmatchedEvents sinUsar = (eventId, type, process, reason, at) -> false;
        caso = new ProcessReservationEvent(reservations, transaction, sinUsar, transaction,
                Clock.fixed(T0, ZoneOffset.UTC));
        evento = new ReservationEvent.AppointmentConfirmed("confirmation-event-1", "process-1", "hold-1", 8251);
    }

    @Test
    void siOtroEscritorCambiaElProcesoALaVezSeReintentaYSeAplicaUnaSolaVez() {
        reservations.conflictsLeft = 2;
        try (var log = new LogCapture()) {
            assertThat(caso.execute(evento)).isEqualTo(Outcome.APPLIED);

            assertThat(reservations.saveAttempts).isEqualTo(3);
            assertThat(reservations.stored.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(transaction.marked).containsExactly("confirmation-event-1");
            assertThat(log.eventos("kafka.evento.reintento")).hasSize(2);
            assertThat(log.eventos("kafka.evento.aplicado")).hasSize(1);
        }
    }

    @Test
    void siElConflictoNoSeVaDespuesDeTresIntentosSePropagaYNadaQuedaMarcado() {
        reservations.conflictsLeft = 10;

        assertThatThrownBy(() -> caso.execute(evento)).isInstanceOf(ReservationConflictException.class);

        assertThat(reservations.saveAttempts).isEqualTo(3);
        assertThat(transaction.marked).as("el deshacer también deshizo el marcado").isEmpty();
        assertThat(reservations.stored.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);

        // Y la próxima entrega del mismo evento sí lo aplica: no quedó "ya procesado".
        reservations.conflictsLeft = 0;
        assertThat(caso.execute(evento)).isEqualTo(Outcome.APPLIED);
    }
}
