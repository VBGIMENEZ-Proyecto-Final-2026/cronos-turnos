package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.availability.GetAvailability;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Los turnos libres de un profesional entre dos fechas. Requiere un usuario autenticado: su JWT, ya validado, se
 * reenvía a atlas para que sepa quién consulta (ADR 0004). Las fechas van como {@code yyyy-MM-dd}.
 */
@RestController
@RequestMapping("/api")
class AvailabilityController {

    private final GetAvailability getAvailability;

    AvailabilityController(GetAvailability getAvailability) {
        this.getAvailability = getAvailability;
    }

    @GetMapping("/professionals/{professionalId}/availability")
    AvailabilityResponse availability(
            @PathVariable long professionalId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal Jwt token) {
        return AvailabilityResponse.from(
                getAvailability.execute(professionalId, from, to, token.getTokenValue()));
    }
}
