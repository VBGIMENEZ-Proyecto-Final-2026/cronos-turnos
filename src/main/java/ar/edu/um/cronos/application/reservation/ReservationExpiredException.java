package ar.edu.um.cronos.application.reservation;

/**
 * El proceso ya pasó su {@code expiresAt}: la cátedra no va a aceptar el teléfono. No se cambia el estado acá: lo cierra el
 * evento de vencimiento de la cátedra.
 */
public class ReservationExpiredException extends RuntimeException {

    public ReservationExpiredException() {
        super("La reserva ya venció");
    }
}
