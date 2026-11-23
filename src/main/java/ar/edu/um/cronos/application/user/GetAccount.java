package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;

/**
 * Devuelve la cuenta del usuario autenticado. El login sale del token ya validado, nunca de un dato que mande el
 * cliente: por eso un usuario solo puede ver la suya. Si el usuario ya no existe o no está activo falla con
 * {@link AccountUnavailableException}.
 */
public class GetAccount {

    private final UserRepository users;

    public GetAccount(UserRepository users) {
        this.users = users;
    }

    public User execute(String login) {
        return users.findByLogin(User.normalize(login))
                .filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
    }
}
