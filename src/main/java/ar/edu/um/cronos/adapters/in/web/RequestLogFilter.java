package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.logging.LogEvent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Le pone un identificador a cada pedido para poder seguirlo de punta a punta (norma de logs, ADR 0006):
 *
 * <ul>
 *   <li>Lo deja en el MDC como {@code requestId}: toda línea que se escriba mientras se atiende el pedido lo lleva
 *       (el patrón de consola lo muestra entre corchetes).</li>
 *   <li>Lo devuelve en el encabezado {@code X-Request-Id}: quien reporta un error puede citarlo.</li>
 *   <li>Escribe una línea al terminar, {@code http.pedido.resuelto}, con método, ruta, estado y duración. Es lo único
 *       que se registra de un pedido que sale bien; los hitos de negocio los escribe cada caso de uso.</li>
 * </ul>
 *
 * <p>Si el cliente manda su propio {@code X-Request-Id} se reutiliza, pero solo si es seguro de escribir en un log
 * (letras, números, punto, guion y guion bajo; de 8 a 64 caracteres). Cualquier otra cosa se descarta y se genera uno
 * nuevo: el valor viene de afuera y terminaría en cada línea del pedido. Va primero en la cadena de filtros, antes de
 * Spring Security, para que también los 401 y los 403 lo lleven. Nunca se registran la query ni los encabezados: la
 * ruta alcanza para saber qué se pidió.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLogFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = idFor(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            // Nadie más lo maneja: el contenedor va a responder 500. Se deja la traza acá, una sola vez.
            LogEvent.error(log, "http.pedido.fallido", e, "metodo", request.getMethod(),
                    "ruta", request.getRequestURI(), "ms", millisSince(start));
            throw e;
        } finally {
            // Si falló ya quedó su línea de error: el estado todavía no es el que va a ver el cliente.
            if (!failed && !request.getRequestURI().startsWith("/actuator/")) {
                LogEvent.info(log, "http.pedido.resuelto", "metodo", request.getMethod(),
                        "ruta", request.getRequestURI(), "estado", response.getStatus(), "ms", millisSince(start));
            }
            MDC.remove(MDC_KEY);
        }
    }

    private static String idFor(String received) {
        if (received != null && SAFE_ID.matcher(received).matches()) {
            return received;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
