package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Autentica a un usuario por login y contraseña y le emite su JWT. Cualquier fallo (login inexistente,
 * contraseña equivocada, usuario no activo) lanza la misma {@link InvalidCredentialsException}. Para que el
 * tiempo de respuesta tampoco delate si el login existe, cuando no existe igual se calcula un hash contra uno
 * de relleno.
 */
public class AuthenticateUser {

    private static final Logger log = LoggerFactory.getLogger(AuthenticateUser.class);

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
            // El motivo exacto queda solo en el log del servidor: al cliente siempre se le responde lo mismo. Nunca se
            // escribe el nombre de usuario que se intentó, solo el id si la cuenta existe.
            String reason = found.isEmpty() ? "usuario_inexistente" : !passwordMatches ? "clave_incorrecta"
                    : "cuenta_inactiva";
            LogEvent.warn(log, "usuario.login.fallido", "usuario", found.map(User::id).orElse(null), "motivo", reason);
            throw new InvalidCredentialsException();
        }
        String token = tokens.issue(found.get(), rememberMe);
        LogEvent.info(log, "usuario.login.ok", "usuario", found.get().id(), "recordarme", rememberMe);
        return token;
    }
}
