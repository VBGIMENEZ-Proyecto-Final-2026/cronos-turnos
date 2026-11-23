package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.domain.User;

import java.util.List;

/** Usuario tal como lo ve el cliente. Nunca incluye la contraseña ni su hash. */
record UserResponse(
        Long id,
        String login,
        String firstName,
        String lastName,
        String email,
        String imageUrl,
        String langKey,
        boolean activated,
        List<String> authorities) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.login(), user.firstName(), user.lastName(), user.email(),
                user.imageUrl(), user.langKey(), user.activated(), user.authorities().stream().sorted().toList());
    }
}
