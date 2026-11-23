package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.domain.ReservationStatus;

/** La reserva ya no espera un teléfono: está confirmada, vencida, inválida, cancelada o falló. */
public class ReservationNotAwaitingPhoneException extends RuntimeException {

    private final ReservationStatus status;

    public ReservationNotAwaitingPhoneException(ReservationStatus status) {
        super("La reserva no espera un teléfono");
        this.status = status;
    }

    public ReservationStatus status() {
        return status;
    }
}
