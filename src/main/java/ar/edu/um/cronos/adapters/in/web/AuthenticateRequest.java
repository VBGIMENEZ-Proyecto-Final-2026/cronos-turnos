package ar.edu.um.cronos.adapters.in.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/authenticate}, con la misma forma que el login de la cátedra (referencia 5.2). El
 * largo máximo de la contraseña también acota el trabajo de hash que puede pedir un cliente.
 */
record AuthenticateRequest(
        @NotNull @Size(min = 1, max = 254) String username,
        @NotNull @Size(min = 4, max = 100) String password,
        Boolean rememberMe) {

    boolean remember() {
        return Boolean.TRUE.equals(rememberMe);
    }

    @Override
    public String toString() {
        return "AuthenticateRequest[username=" + username + "]";
    }
}
