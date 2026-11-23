package ar.edu.um.cronos.application.port.out;

/** La cátedra respondió y rechazó la operación por una razón conocida (a diferencia de no responder). */
public class CatedraRejectedException extends RuntimeException {

    private final CatedraFailure failure;

    public CatedraRejectedException(CatedraFailure failure, Throwable cause) {
        super("La cátedra rechazó la operación: " + failure, cause);
        this.failure = failure;
    }

    public CatedraFailure failure() {
        return failure;
    }
}
