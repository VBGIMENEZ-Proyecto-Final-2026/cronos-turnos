package ar.edu.um.cronos.application.reservation;

/** El usuario ya está iniciando esa misma reserva y todavía no terminó: no se puede empezar otra igual. */
public class ReservationInProgressException extends RuntimeException {

    public ReservationInProgressException() {
        super("Ya hay una reserva en curso para ese turno");
    }
}
