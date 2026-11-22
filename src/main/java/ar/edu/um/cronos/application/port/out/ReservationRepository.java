package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/** Persistencia de los procesos de reserva. Cada operación es una transacción corta: nunca se llama a la red dentro. */
public interface ReservationRepository {

    /**
     * Inserta un proceso nuevo (sin id) o actualiza uno existente. Devuelve el proceso guardado, con su id y su versión.
     *
     * @throws ActiveReservationExistsException si se inserta un proceso para un turno que el usuario ya tiene activo
     * @throws ReservationConflictException  si otro cambió el mismo proceso desde que se leyó
     */
    Reservation save(Reservation reservation);

    Optional<Reservation> findById(long id);

    /** El proceso que la cátedra conoce con este {@code reservationProcessId}; vacío si cronos no lo tiene. */
    Optional<Reservation> findByReservationProcessId(String reservationProcessId);

    /**
     * Las reservas de un usuario, la más reciente primero (por fecha de creación y, a igual fecha, por id).
     *
     * @param status si no es {@code null}, solo las que están en ese estado
     * @param page   desde 0
     */
    ReservationPage findByUser(long userId, ReservationStatus status, int page, int size);

    /** El proceso que todavía ocupa el turno del usuario (no terminó mal ni se cerró), si lo hay. */
    Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime startTime);
}
