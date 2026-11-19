package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;

import java.util.Optional;

/**
 * Autentica a un usuario por login y contraseña y le emite su JWT. Cualquier fallo (login inexistente,
 * contraseña equivocada, usuario no activo) lanza la misma {@link InvalidCredentialsException}. Para que el
 * tiempo de respuesta tampoco delate si el login existe, cuando no existe igual se calcula un hash contra uno
 * de relleno.
 */
public class AuthenticateUser {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenIssuer tokens;
    private final String fillerHash;

    public AuthenticateUser(UserRepository users, PasswordHasher hasher, TokenIssuer tokens) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.fillerHash = hasher.hash("relleno-para-igualar-tiempos");
    }

    /** @return el JWT del usuario */
    public String execute(String login, String password, boolean rememberMe) {
        Optional<User> found = users.findByLogin(User.normalize(login));
        boolean passwordMatches = hasher.matches(password, found.map(User::passwordHash).orElse(fillerHash));
        if (found.isEmpty() || !passwordMatches || !found.get().activated()) {
            throw new InvalidCredentialsException();
        }
        return tokens.issue(found.get(), rememberMe);
    }
}
