package ar.edu.um.cronos.application.reservation;

/** El motivo de la cancelación pasa los 500 caracteres. El mensaje es fijo: no repite lo que escribió el usuario. */
public class InvalidCancellationReasonException extends RuntimeException {

    public InvalidCancellationReasonException() {
        super("El motivo de la cancelación es demasiado largo");
    }
}
