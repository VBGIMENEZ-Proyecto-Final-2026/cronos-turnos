package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.ProcessedEvents;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.TransactionRunner;
import ar.edu.um.cronos.application.port.out.UnmatchedEvents;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationEvent;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Aplica a un proceso de reserva lo que la cátedra informa por Kafka (referencia 15.4 a 15.10). No sabe de Kafka: recibe
 * un {@link ReservationEvent} ya interpretado y dice qué pasó con él ({@link Outcome}).
 *
 * <p><b>Atómico.</b> Marcar el {@code eventId} como procesado y cambiar el estado del proceso van en la misma
 * transacción: si el guardado falla, el evento no queda marcado y se reintenta entero; si se marcó, su efecto está
 * guardado. Por eso un evento nunca se aplica dos veces ni se pierde a medias. Dos copias del mismo evento a la vez
 * también quedan resueltas por la base: la segunda espera a la primera y recibe {@link Outcome#DUPLICATE}.
 *
 * <p><b>Un estado final nunca retrocede.</b> Un evento que no corresponde al estado actual (un {@code Confirmed} después
 * de un {@code Expired}, un {@code Cancelled} de algo que no estaba confirmado) no cambia nada, pero se registra como
 * procesado ({@link Outcome#IGNORED}) para que su duplicado tampoco haga nada.
 *
 * <p><b>Un proceso desconocido no se marca</b> ({@link Outcome#UNKNOWN_PROCESS}): puede ser un evento que ganó una
 * carrera contra el guardado del proceso, así que quien lo consume lo reintenta y, si sigue sin existir, lo estaciona con
 * {@link #park}.
 */
public class ProcessReservationEvent {

    private static final Logger log = LoggerFactory.getLogger(ProcessReservationEvent.class);
    /** Intentos si otro escritor (el propio pedido de reserva, por ejemplo) cambió el proceso a la vez. */
    private static final int ATTEMPTS = 3;
    /** El motivo con el que se estaciona un evento cuyo proceso no existe en esta base. */
    static final String UNKNOWN_PROCESS_REASON = "UNKNOWN_PROCESS";

    /** Qué pasó con un evento. */
    public enum Outcome {
        /** Se aplicó: el proceso cambió y el evento quedó marcado. */
        APPLIED,
        /** Ya se había procesado (mismo {@code eventId}): no se repite ningún efecto. */
        DUPLICATE,
        /** No corresponde al estado actual del proceso: no cambió nada, pero quedó marcado como procesado. */
        IGNORED,
        /** Cronos no tiene ese proceso: no se hizo nada y no se marcó. */
        UNKNOWN_PROCESS
    }

    private final ReservationRepository reservations;
    private final ProcessedEvents processed;
    private final UnmatchedEvents unmatched;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ProcessReservationEvent(ReservationRepository reservations, ProcessedEvents processed,
                                   UnmatchedEvents unmatched, TransactionRunner transactions, Clock clock) {
        this.reservations = reservations;
        this.processed = processed;
        this.unmatched = unmatched;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @throws ReservationConflictException si el proceso seguía cambiando a la vez después de {@value #ATTEMPTS}
     *                                      intentos (nada quedó marcado: se puede volver a llamar)
     */
    public Outcome execute(ReservationEvent event) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.inTransaction(() -> apply(event));
            } catch (ReservationConflictException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                LogEvent.info(log, "kafka.evento.reintento", "id_evento", event.eventId(), "tipo", event.type(),
                        "proceso", event.reservationProcessId(), "intento", attempt + 1,
                        "motivo", "el_proceso_cambio_a_la_vez");
            }
        }
    }

    /**
     * Estaciona un evento cuyo proceso no existe, para diagnosticarlo sin trabar el consumo. Es idempotente: estacionar
     * el mismo evento dos veces deja una sola fila.
     */
    public void park(ReservationEvent event) {
        boolean parked = unmatched.park(event.eventId(), event.type(), event.reservationProcessId(),
                UNKNOWN_PROCESS_REASON, now());
        LogEvent.warn(log, "kafka.evento.estacionado", "id_evento", event.eventId(), "tipo", event.type(),
                "proceso", event.reservationProcessId(), "motivo", UNKNOWN_PROCESS_REASON,
                "accion", parked ? "estacionado" : "ya_estaba_estacionado");
    }

    private Outcome apply(ReservationEvent event) {
        Optional<Reservation> found = reservations.findByReservationProcessId(event.reservationProcessId());
        if (found.isEmpty()) {
            LogEvent.warn(log, "kafka.evento.proceso_desconocido", "id_evento", event.eventId(), "tipo", event.type(),
                    "proceso", event.reservationProcessId(), "accion", "no_se_marca_se_reintenta_o_se_estaciona");
            return Outcome.UNKNOWN_PROCESS;
        }
        Reservation current = found.get();

        if (!processed.markProcessed(event.eventId(), event.type(), event.reservationProcessId(), now())) {
            LogEvent.info(log, "kafka.evento.duplicado", "id_evento", event.eventId(), "tipo", event.type(),
                    "proceso", event.reservationProcessId(), "reserva", current.id(), "accion", "sin_efectos");
            return Outcome.DUPLICATE;
        }

        Decision decision = decide(current, event);
        if (decision.next() == null) {
            // Un estado final es lo esperable (el evento llegó tarde); el resto es una anomalía que conviene ver.
            if (current.status().isFinal()) {
                LogEvent.info(log, "kafka.evento.ignorado", "id_evento", event.eventId(), "tipo", event.type(),
                        "proceso", event.reservationProcessId(), "reserva", current.id(), "estado", current.status(),
                        "motivo", decision.reason(), "accion", "registrado_sin_cambios");
            } else {
                LogEvent.warn(log, "kafka.evento.ignorado", "id_evento", event.eventId(), "tipo", event.type(),
                        "proceso", event.reservationProcessId(), "reserva", current.id(), "estado", current.status(),
                        "motivo", decision.reason(), "accion", "registrado_sin_cambios");
            }
            return Outcome.IGNORED;
        }

        Reservation saved = reservations.save(decision.next());
        LogEvent.info(log, "kafka.evento.aplicado", "id_evento", event.eventId(), "tipo", event.type(),
                "proceso", event.reservationProcessId(), "reserva", saved.id(), "usuario", saved.userId(),
                "estado_anterior", current.status(), "estado_nuevo", saved.status());
        return Outcome.APPLIED;
    }

    /** Qué hacer con el evento sobre el proceso: el proceso nuevo, o {@code null} más el motivo de no hacer nada. */
    private Decision decide(Reservation r, ReservationEvent event) {
        if (event.holdId() != null && r.holdId() != null && !event.holdId().equals(r.holdId())) {
            return Decision.ignored("hold_distinto");
        }
        Instant now = now();
        boolean awaiting = r.status() == ReservationStatus.HELD || r.status() == ReservationStatus.WAITING_FOR_PHONE;
        String wrongState = r.status().isFinal() ? "estado_final" : "estado_no_corresponde";

        return switch (event) {
            case ReservationEvent.InformationRequested e -> awaiting
                    ? Decision.of(r.informationRequested(e.eventId(), now)) : Decision.ignored(wrongState);
            case ReservationEvent.InformationRejected e -> awaiting
                    ? Decision.of(r.informationRejected(e.reason(), now)) : Decision.ignored(wrongState);
            case ReservationEvent.AppointmentConfirmed e -> switch (r.status()) {
                case WAITING_FOR_PHONE -> Decision.of(r.confirmed(e.reservationId(), now));
                // El resultado llegó antes que la confirmación REST: se pasa por WAITING_FOR_PHONE sin saltar estados.
                case HELD -> Decision.of(r.waitingForPhone(null, now).confirmed(e.reservationId(), now));
                case CONFIRMED -> Decision.ignored(
                        Long.valueOf(e.reservationId()).equals(r.catedraReservationId())
                                ? "ya_confirmada" : "otra_reserva_confirmada");
                default -> Decision.ignored(wrongState);
            };
            case ReservationEvent.ProcessExpired e -> awaiting
                    ? Decision.of(r.expired(now)) : Decision.ignored(wrongState);
            case ReservationEvent.ProcessInvalid e -> awaiting
                    ? Decision.of(r.invalid(e.reason(), now)) : Decision.ignored(wrongState);
            case ReservationEvent.AppointmentCancelled e -> r.status() == ReservationStatus.CONFIRMED
                    ? Decision.of(r.cancelled(now)) : Decision.ignored(wrongState);
        };
    }

    /** A la precisión de la base (microsegundos), como el resto de los instantes que guarda cronos. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Decision(Reservation next, String reason) {

        static Decision of(Reservation next) {
            return new Decision(next, null);
        }

        static Decision ignored(String reason) {
            return new Decision(null, reason);
        }
    }
}
