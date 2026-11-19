package ar.edu.um.cronos.application.port.out;

/** Ya existe un usuario con ese email. El mensaje no incluye el valor recibido. */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException() {
        super("El email ya está en uso");
    }

    public EmailAlreadyUsedException(Throwable cause) {
        super("El email ya está en uso", cause);
    }
}
