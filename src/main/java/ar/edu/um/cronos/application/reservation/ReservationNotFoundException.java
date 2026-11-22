package ar.edu.um.cronos.application.reservation;

/** La reserva no existe <b>o no es del usuario</b>: no se distingue, para no revelar que existe la de otro. */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException() {
        super("La reserva no existe");
    }
}
