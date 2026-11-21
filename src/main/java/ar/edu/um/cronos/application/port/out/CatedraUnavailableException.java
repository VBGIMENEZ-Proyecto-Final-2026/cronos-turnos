package ar.edu.um.cronos.application.port.out;

/**
 * El servicio central de la cátedra no se puede usar: error de red, timeout, {@code 5xx}, o rechazó la credencial
 * técnica. Es transitoria para el usuario: no puede hacer nada con ella, salvo reintentar más tarde.
 */
public class CatedraUnavailableException extends RuntimeException {

    public CatedraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
