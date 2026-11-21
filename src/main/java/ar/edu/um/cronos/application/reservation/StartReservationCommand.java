package ar.edu.um.cronos.application.reservation;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * El pedido de iniciar una reserva.
 *
 * @param login     el login del usuario que la pide, tomado del token ya validado (nunca del cuerpo del pedido)
 * @param userToken el JWT del usuario, que se reenvía al catálogo sin modificar; dato opaco que no se registra
 */
public record StartReservationCommand(String login, long professionalId, LocalDate date, LocalTime startTime,
                                      String patientFirstName, String patientLastName, String userToken) {

    @Override
    public String toString() {
        // Sin el token ni los nombres del paciente.
        return "StartReservationCommand[professionalId=" + professionalId + ", " + date + " " + startTime + "]";
    }
}
