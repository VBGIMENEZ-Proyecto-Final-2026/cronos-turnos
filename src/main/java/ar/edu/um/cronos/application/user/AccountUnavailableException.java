package ar.edu.um.cronos.application.user;

/**
 * El token es válido pero su usuario ya no existe o no está activo. Para el cliente es lo mismo que no estar
 * autenticado: la identidad que el token afirma ya no vale.
 */
public class AccountUnavailableException extends RuntimeException {

    public AccountUnavailableException() {
        super("La cuenta no está disponible");
    }
}
