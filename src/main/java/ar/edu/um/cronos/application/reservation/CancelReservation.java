package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationCancelGateway;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.User;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Cancela una reserva propia confirmada (enunciado, requisito 14).
 *
 * <p><b>Qué se exige:</b> la reserva existe y es del usuario ({@link ReservationNotFoundException}: también si es de otro) y
 * está confirmada ({@link ReservationNotCancellableException}). Una reserva <b>ya cancelada</b> se devuelve tal cual, sin
 * llamar a la cátedra: cancelar es idempotente, como en la cátedra (referencia 12).
 *
 * <p><b>Si no se sabe qué pasó</b> (sin respuesta, timeout, error del servidor), un timeout no prueba que la operación haya
 * fallado (referencia 18.4). Como la cancelación de la cátedra es idempotente, repetirla es seguro: se repite <b>una sola
 * vez</b> (reconciliar) y, si sigue sin saberse, se falla con {@link CatedraUnavailableException} y la reserva queda
 * confirmada localmente. Repetir el pedido es seguro; y si la cátedra sí la canceló, el evento
 * {@code AppointmentCancelled} la actualiza por Kafka.
 *
 * <p><b>El estado local se actualiza con la respuesta REST</b>, sin esperar el evento: el usuario ve la reserva cancelada
 * enseguida. El evento que llega después encuentra un estado final y no cambia nada. Si el consumidor ya la había cancelado
 * a la vez, se devuelve el proceso como quedó.
 *
 * <p><b>El motivo es texto libre del usuario</b>: se manda a la cátedra y no se guarda ni se registra.
 */
public class CancelReservation {

    private static final Logger log = LoggerFactory.getLogger(CancelReservation.class);
    /** Largo máximo del motivo (referencia 12). */
    public static final int MAX_REASON_LENGTH = 500;
    /** Intentos cuando no se sabe si la cátedra procesó la cancelación: el primero y una reconciliación. */
    private static final int ATTEMPTS = 2;

    private final UserRepository users;
    private final ReservationRepository reservations;
    private final ReservationCancelGateway catedra;
    private final Clock clock;

    public CancelReservation(UserRepository users, ReservationRepository reservations, ReservationCancelGateway catedra,
                             Clock clock) {
        this.users = users;
        this.reservations = reservations;
        this.catedra = catedra;
        this.clock = clock;
    }

    /**
     * @param reason el motivo, o {@code null} o en blanco si no hay
     * @throws AccountUnavailableException           si el usuario del token ya no existe o no está activo
     * @throws ReservationNotFoundException          si la reserva no existe o no es del usuario
     * @throws ReservationNotCancellableException    si no está confirmada ni cancelada
     * @throws InvalidCancellationReasonException    si el motivo pasa los {@value #MAX_REASON_LENGTH} caracteres
     * @throws CatedraRejectedException              si la cátedra rechazó la cancelación
     * @throws CatedraUnavailableException           si no se pudo saber qué pasó con la cancelación
     */
    public Reservation execute(String login, long reservationId, String reason) {
        User user = users.findByLogin(User.normalize(login)).filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
        Reservation reservation = reservations.findById(reservationId).filter(r -> r.userId() == user.id())
                .orElseThrow(() -> rejected(user.id(), reservationId, "no_existe_o_no_es_suya",
                        new ReservationNotFoundException()));

        if (reservation.status() == ReservationStatus.CANCELLED) {
            LogEvent.info(log, "reserva.cancelacion.ya_cancelada", "usuario", user.id(), "reserva", reservation.id(),
                    "accion", "se_devuelve_tal_cual_sin_llamar_a_la_catedra");
            return reservation;
        }
        if (reservation.status() != ReservationStatus.CONFIRMED) {
            throw rejected(user.id(), reservation.id(), "no_esta_confirmada",
                    new ReservationNotCancellableException(reservation.status()));
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (cleanReason != null && cleanReason.length() > MAX_REASON_LENGTH) {
            throw rejected(user.id(), reservation.id(), "motivo_demasiado_largo", new InvalidCancellationReasonException());
        }

        long start = System.nanoTime();
        cancelInCatedra(user.id(), reservation, cleanReason);
        Reservation cancelled = markCancelled(reservation);
        LogEvent.info(log, "reserva.cancelacion.confirmada", "usuario", user.id(), "reserva", reservation.id(),
                "proceso", reservation.reservationProcessId(), "estado", cancelled.status(),
                "ms", (System.nanoTime() - start) / 1_000_000);
        return cancelled;
    }

    private void cancelInCatedra(long userId, Reservation reservation, String reason) {
        for (int attempt = 1; ; attempt++) {
            try {
                catedra.cancel(reservation.reservationProcessId(), reason);
                return;
            } catch (CatedraRejectedException e) {
                LogEvent.info(log, "reserva.cancelacion.rechazada", "usuario", userId, "reserva", reservation.id(),
                        "proceso", reservation.reservationProcessId(), "motivo", e.failure(),
                        "accion", "el_estado_local_no_cambia");
                throw e;
            } catch (CatedraUnavailableException e) {
                if (attempt >= ATTEMPTS) {
                    LogEvent.warn(log, "reserva.cancelacion.incierta", "usuario", userId, "reserva", reservation.id(),
                            "proceso", reservation.reservationProcessId(), "intentos", attempt,
                            "accion", "sigue_confirmada_localmente_repetir_el_pedido_es_seguro");
                    throw e;
                }
                LogEvent.info(log, "reserva.cancelacion.reconciliando", "usuario", userId, "reserva", reservation.id(),
                        "proceso", reservation.reservationProcessId(), "intento", attempt + 1);
            }
        }
    }

    /**
     * Guarda el estado cancelado. Si el consumidor de Kafka cambió el proceso a la vez (por ejemplo, ya aplicó
     * {@code AppointmentCancelled}), se relee y se devuelve como quedó o se reaplica sobre la versión nueva. Cualquier otra
     * falla de la base sube: la cátedra ya canceló y repetir el pedido es seguro (es idempotente).
     */
    private Reservation markCancelled(Reservation reservation) {
        try {
            return reservations.save(reservation.cancelled(now()));
        } catch (ReservationConflictException conflict) {
            Reservation fresh = reservations.findById(reservation.id()).orElseThrow(() -> conflict);
            LogEvent.info(log, "reserva.cancelacion.carrera", "reserva", reservation.id(), "estado_actual", fresh.status(),
                    "accion", fresh.status() == ReservationStatus.CONFIRMED ? "se_reaplica_sobre_la_version_nueva"
                            : "se_devuelve_el_proceso_ya_avanzado");
            return fresh.status() == ReservationStatus.CONFIRMED ? reservations.save(fresh.cancelled(now())) : fresh;
        }
    }

    private static <E extends RuntimeException> E rejected(long userId, long reservationId, String reason, E exception) {
        LogEvent.info(log, "reserva.cancelacion.denegada", "usuario", userId, "reserva", reservationId, "motivo", reason);
        return exception;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
