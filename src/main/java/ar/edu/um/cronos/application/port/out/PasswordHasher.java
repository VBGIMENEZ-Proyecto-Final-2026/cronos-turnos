package ar.edu.um.cronos.application.port.out;

/** Hash de contraseñas. La contraseña en claro solo existe en la memoria de quien llama; nunca se guarda ni se loguea. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
