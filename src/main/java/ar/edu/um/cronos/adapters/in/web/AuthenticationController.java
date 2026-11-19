package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.user.AuthenticateUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login público: devuelve el JWT en el cuerpo ({@code id_token}) y en {@code Authorization: Bearer}. */
@RestController
@RequestMapping("/api")
class AuthenticationController {

    private final AuthenticateUser authenticateUser;

    AuthenticationController(AuthenticateUser authenticateUser) {
        this.authenticateUser = authenticateUser;
    }

    @PostMapping("/authenticate")
    ResponseEntity<TokenResponse> authenticate(@Valid @RequestBody AuthenticateRequest request) {
        String token = authenticateUser.execute(request.username(), request.password(), request.remember());
        return ResponseEntity.ok().header(HttpHeaders.AUTHORIZATION, "Bearer " + token).body(new TokenResponse(token));
    }
}
