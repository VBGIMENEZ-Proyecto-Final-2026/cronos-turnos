package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.user.GetAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cuenta del usuario autenticado. No recibe ningún identificador: el usuario es el {@code sub} del token ya
 * validado, así que nadie puede pedir la cuenta de otro (enunciado, sección 9).
 */
@RestController
@RequestMapping("/api")
class AccountController {

    private final GetAccount getAccount;

    AccountController(GetAccount getAccount) {
        this.getAccount = getAccount;
    }

    @GetMapping("/account")
    UserResponse account(@AuthenticationPrincipal Jwt token) {
        return UserResponse.from(getAccount.execute(token.getSubject()));
    }
}
