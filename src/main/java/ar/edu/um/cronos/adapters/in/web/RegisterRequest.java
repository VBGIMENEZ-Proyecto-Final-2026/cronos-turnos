package ar.edu.um.cronos.adapters.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/register}. Solo lleva lo que elige quien se registra: cualquier otro campo (id,
 * activación, autoridades) se ignora. Las validaciones son las de la referencia de la cátedra (registro de la
 * cuenta técnica, 5.1) y {@code toString} no imprime la contraseña.
 */
record RegisterRequest(
        @NotNull @Size(min = 3, max = 50) @Pattern(regexp = "[A-Za-z0-9._@-]*") String login,
        @NotNull @Size(min = 4, max = 100) String password,
        @Size(max = 50) String firstName,
        @Size(max = 50) String lastName,
        @Email @Size(min = 5, max = 254) String email,
        @Size(max = 256) String imageUrl,
        @Size(min = 2, max = 10) String langKey) {

    @Override
    public String toString() {
        return "RegisterRequest[login=" + login + "]";
    }
}
