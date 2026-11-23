package ar.edu.um.cronos.application.port.out;

/** Ya existe un usuario con ese login. El mensaje no incluye el valor recibido. */
public class LoginAlreadyUsedException extends RuntimeException {

    public LoginAlreadyUsedException() {
        super("El login ya está en uso");
    }

    public LoginAlreadyUsedException(Throwable cause) {
        super("El login ya está en uso", cause);
    }
}
