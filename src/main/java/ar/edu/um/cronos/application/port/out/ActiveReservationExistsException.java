package ar.edu.um.cronos.application.port.out;

/** El usuario ya tiene un proceso activo para ese turno: la base lo impide con un índice único parcial. */
public class ActiveReservationExistsException extends RuntimeException {

    public ActiveReservationExistsException(Throwable cause) {
        super("El usuario ya tiene un proceso activo para ese turno", cause);
    }
}
