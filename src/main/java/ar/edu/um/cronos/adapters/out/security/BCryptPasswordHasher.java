package ar.edu.um.cronos.adapters.out.security;

import ar.edu.um.cronos.application.port.out.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Hash de contraseñas con BCrypt (la sal va incluida en el hash).
 *
 * <p>BCrypt solo admite 72 bytes: Spring lanza una excepción con más, y otras implementaciones recortan en
 * silencio, con lo que dos contraseñas que comparten los primeros 72 bytes serían la misma. El contrato permite
 * contraseñas de hasta 100 caracteres (que en UTF-8 pueden pasar de 72 bytes), así que antes de BCrypt se las
 * reduce con SHA-256 y se codifica el resultado en Base64 (44 caracteres, sin bytes nulos). Toda la contraseña
 * cuenta y el hash guardado sigue siendo un BCrypt estándar.
 */
@Component
class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(prehash(rawPassword));
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        return encoder.matches(prehash(rawPassword), hash);
    }

    private static String prehash(String rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible en la JVM", e);
        }
    }
}
