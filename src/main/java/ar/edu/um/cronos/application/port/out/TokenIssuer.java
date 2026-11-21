package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.User;

/** Emite el JWT de un usuario ya autenticado. La vigencia y la firma son detalles del adaptador. */
public interface TokenIssuer {

    /** @param rememberMe {@code true} pide la vigencia larga */
    String issue(User user, boolean rememberMe);
}
