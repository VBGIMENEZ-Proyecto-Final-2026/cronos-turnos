package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.domain.ReservationStatus;

/** Solo se puede cancelar una reserva confirmada: esta está en otro estado. */
public class ReservationNotCancellableException extends RuntimeException {

    private final ReservationStatus status;

    public ReservationNotCancellableException(ReservationStatus status) {
        super("Solo se puede cancelar una reserva confirmada");
        this.status = status;
    }

    public ReservationStatus status() {
        return status;
    }
}
