package ar.edu.um.cronos.application.port.out;

/**
 * No se pudo mandar el teléfono a la cátedra. No lleva la causa técnica en el mensaje (puede traer servidores o el valor): la
 * causa, si la hay, queda en {@code getCause()} y al log solo va el nombre de su clase.
 */
public class PhoneUnavailableException extends RuntimeException {

    public PhoneUnavailableException(Throwable cause) {
        super("No se pudo enviar el teléfono a la cátedra", cause);
    }
}
