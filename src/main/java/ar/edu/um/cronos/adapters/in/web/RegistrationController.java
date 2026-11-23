package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.user.RegisterUser;
import ar.edu.um.cronos.application.user.RegisterUserCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registro público de usuarios finales (enunciado 3.2). */
@RestController
@RequestMapping("/api")
class RegistrationController {

    private final RegisterUser registerUser;

    RegistrationController(RegisterUser registerUser) {
        this.registerUser = registerUser;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(registerUser.execute(new RegisterUserCommand(request.login(), request.password(),
                request.firstName(), request.lastName(), request.email(), request.imageUrl(), request.langKey())));
    }
}
