package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.Reservation;

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

    /** El proceso que todavía ocupa el turno del usuario (no terminó mal ni se cerró), si lo hay. */
    Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime startTime);
}
