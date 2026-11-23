package ar.edu.um.cronos.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Usuario final de la aplicación (enunciado 3.2). Compatible con el usuario de JHipster: {@code login},
 * contraseña, nombres, {@code email}, {@code imageUrl} y {@code langKey}, más el estado de activación, las
 * autoridades y las fechas de auditoría, que decide el backend y nunca quien se registra.
 *
 * <p>La contraseña se guarda siempre como hash: este tipo no conoce la contraseña en claro. {@code toString}
 * no imprime el hash, para que no llegue a un log por accidente.
 *
 * @param id           {@code null} hasta que se persiste
 * @param passwordHash hash de la contraseña, nunca el texto en claro
 */
public record User(
        Long id,
        String login,
        String passwordHash,
        String firstName,
        String lastName,
        String email,
        String imageUrl,
        String langKey,
        boolean activated,
        Set<String> authorities,
        Instant createdAt,
        Instant updatedAt) {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String DEFAULT_LANG_KEY = "es";

    public User {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(langKey, "langKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }

    /**
     * Alta de un usuario nuevo. Normaliza {@code login} y {@code email} a minúsculas (la unicidad no depende de
     * las mayúsculas), lo deja <b>activo</b> desde el primer momento (sin verificación por correo, enunciado 3.2) y
     * con la única autoridad {@code ROLE_USER}. Los campos de texto vacíos se guardan como ausentes.
     */
    public static User register(String login, String passwordHash, String firstName, String lastName, String email,
                                String imageUrl, String langKey, Instant now) {
        return new User(null, normalize(login), passwordHash, blankToNull(firstName), blankToNull(lastName),
                normalize(email), blankToNull(imageUrl), blankToNull(langKey) == null ? DEFAULT_LANG_KEY : langKey,
                true, Set.of(ROLE_USER), now, now);
    }

    /** Minúsculas y sin espacios en los extremos; {@code null} si queda vacío. */
    public static String normalize(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", login=" + login + ", activated=" + activated + ", authorities=" + authorities + "]";
    }
}
