package ar.edu.um.cronos.adapters.out.rest;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Criterios comunes de los clientes REST: qué falla se reintenta y cómo se lee el {@code code} de un error. */
public final class RestFailures {

    private RestFailures() {
    }

    /**
     * Solo las fallas transitorias se reintentan: error de red, timeout y {@code 503}. Los clientes solo hacen
     * lecturas, que no tienen efecto si se repiten.
     */
    public static boolean isTransient(Throwable t) {
        return t instanceof ResourceAccessException
                || (t instanceof HttpServerErrorException e && e.getStatusCode().value() == 503);
    }

    /** El {@code code} funcional de un {@code application/problem+json}, o {@code null} si no se puede leer. */
    static String codeOf(RestClientResponseException e) {
        try {
            ProblemJson problem = e.getResponseBodyAs(ProblemJson.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Parte de {@code application/problem+json} que interesa. */
    record ProblemJson(String code) {
    }
}
