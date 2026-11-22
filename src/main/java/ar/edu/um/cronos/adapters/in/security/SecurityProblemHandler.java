package ar.edu.um.cronos.adapters.in.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Respuestas de seguridad como {@code application/problem+json} con el formato de la referencia de integración
 * (sección 13): {@code 401 NO_AUTENTICADO} sin token o con un token inválido, vencido o mal firmado, y
 * {@code 403 SIN_PERMISO} con un token válido que no alcanza para el recurso. Son los mismos códigos que usa atlas,
 * para que la app trate igual a los dos servicios.
 *
 * <p>No dice por qué se rechazó el token (firma, vencimiento, algoritmo): esa pista sirve a quien ataca. Tampoco
 * repite el rol que faltaba. Cada rechazo queda en el log con el método y la ruta, y en el {@code 403} con el
 * usuario, nunca con el token.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemHandler.class);

    private final JsonMapper mapper;

    public SecurityProblemHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        LogEvent.warn(log, "seguridad.acceso.no_autenticado", "metodo", request.getMethod(),
                "ruta", request.getRequestURI(), "motivo", exception.getClass().getSimpleName(), "estado", 401);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        // No se registra quién es: el nombre de usuario es un dato personal y el requestId alcanza para seguir el pedido.
        LogEvent.warn(log, "seguridad.acceso.denegado", "metodo", request.getMethod(),
                "ruta", request.getRequestURI(), "motivo", exception.getClass().getSimpleName(), "estado", 403);
        write(response, request, HttpStatus.FORBIDDEN, "SIN_PERMISO");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("message", "error.http." + status.value());
        problem.put("path", request.getRequestURI());
        problem.put("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
