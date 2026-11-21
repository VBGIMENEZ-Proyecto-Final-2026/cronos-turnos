package ar.edu.um.cronos.application.user;

/**
 * Datos que elige quien se registra (enunciado 3.2). No hay id, activación ni autoridades: los decide el backend.
 * {@code toString} no imprime la contraseña.
 */
public record RegisterUserCommand(
        String login,
        String password,
        String firstName,
        String lastName,
        String email,
        String imageUrl,
        String langKey) {

    @Override
    public String toString() {
        return "RegisterUserCommand[login=" + login + "]";
    }
}
