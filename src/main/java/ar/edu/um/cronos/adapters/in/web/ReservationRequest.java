package ar.edu.um.cronos.adapters.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * El pedido de iniciar una reserva. Las validaciones de los nombres son las de la cátedra para el paciente
 * (referencia de integración, 10). El usuario <b>no</b> se manda: sale del token. Un campo que no está acá
 * ({@code id}, {@code status}, {@code externalPatientId}, {@code holdId}...) se ignora.
 */
record ReservationRequest(
        @NotNull @Positive Long professionalId,
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotBlank @Size(min = 2, max = 100) String patientFirstName,
        @NotBlank @Size(min = 2, max = 100) String patientLastName) {

    @Override
    public String toString() {
        return "ReservationRequest[professionalId=" + professionalId + ", " + date + " " + startTime + "]";
    }
}
