package ar.edu.um.cronos.application.user;

/**
 * Login o contraseña incorrectos, o usuario no activo. Es una sola excepción, sin detalle, para que nada distinga
 * "no existe" de "contraseña equivocada" (no se revela qué logins existen).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Credenciales inválidas");
    }
}
