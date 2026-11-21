package ar.edu.um.cronos.application.port.out;

/** Otro cambió el mismo proceso de reserva entre que se lo leyó y se lo quiso guardar (bloqueo optimista). */
public class ReservationConflictException extends RuntimeException {

    public ReservationConflictException(Throwable cause) {
        super("El proceso de reserva fue modificado por otra operación", cause);
    }
}
