package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.port.out.EmailAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.LoginAlreadyUsedException;
import ar.edu.um.cronos.application.user.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Errores de la API como {@code application/problem+json}, con el formato de la referencia de integración
 * (sección 13): {@code title}, {@code status}, {@code message}, {@code path} y {@code code}, y {@code fieldErrors}
 * en los errores de cuerpo. Los mensajes son fijos: no repiten el valor recibido (menos aún una contraseña) ni la
 * causa técnica, que va solo al log y de ella solo la clase.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * En el registro, {@code INVALID_PASSWORD} cuando lo único que falla es el largo de una contraseña presente; en
     * cualquier otro caso (falta un campo, un formato o un largo inválido, o cualquier error del login)
     * {@code VALIDATION_ERROR}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> cuerpoInvalido(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        boolean soloLargoDeContrasena = "registerRequest".equals(e.getBindingResult().getObjectName())
                && e.getBindingResult().getErrorCount() == errors.size() && !errors.isEmpty()
                && errors.stream().allMatch(f -> f.getField().equals("password") && "Size".equals(f.getCode()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, soloLargoDeContrasena ? "INVALID_PASSWORD"
                : "VALIDATION_ERROR", request);
        problem.setTitle("Method argument not valid");
        problem.setProperty("message", "error.validation");
        // Solo el nombre del campo y la regla que falló: nunca el valor recibido.
        problem.setProperty("fieldErrors", errors.stream()
                .map(f -> Map.of("objectName", f.getObjectName(), "field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> cuerpoIlegible(HttpMessageNotReadableException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        problem.setDetail("El cuerpo de la solicitud no es un JSON válido");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(LoginAlreadyUsedException.class)
    ResponseEntity<ProblemDetail> loginRepetido(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "USERNAME_ALREADY_EXISTS", request));
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    ResponseEntity<ProblemDetail> emailRepetido(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_EXISTS", request));
    }

    /** Un 401 igual para cualquier causa: no dice si el login no existe, la contraseña falló o el usuario no está activo. */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> credencialesInvalidas(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", request));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> baseNoDisponible(RuntimeException e, HttpServletRequest request) {
        log.warn("No se pudo usar la base de datos ({})", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", request));
    }

    private static ProblemDetail problem(HttpStatus status, String code, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("message", "error.http." + status.value());
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("code", code);
        return problem;
    }
}
