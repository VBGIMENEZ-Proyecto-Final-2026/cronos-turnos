package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Un proceso de reserva tal como lo ve su dueño. No expone cómo lo conoce la cátedra ({@code holdId},
 * {@code reservationProcessId}) ni el usuario: son detalles internos. Los datos del profesional son los de cuando
 * se reservó, no el catálogo vigente.
 */
record ReservationResponse(
        long id,
        ReservationStatus status,
        long professionalId,
        String professionalFirstName,
        String professionalLastName,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String patientFirstName,
        String patientLastName,
        Instant expiresAt,
        Instant createdAt) {

    static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.id(), r.status(), r.professionalId(), r.professionalFirstName(),
                r.professionalLastName(), r.date(), r.startTime(), r.endTime(), r.patientFirstName(),
                r.patientLastName(), r.expiresAt(), r.createdAt());
    }
}
