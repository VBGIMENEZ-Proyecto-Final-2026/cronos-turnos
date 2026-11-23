package ar.edu.um.cronos.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.function.Predicate;

/** Lo común de los clientes REST de salida: URL válida, timeouts acotados, sin redirecciones y reintentos cortos. */
final class HttpClientSupport {

    private HttpClientSupport() {
    }

    /** Devuelve la URL sin espacios, o falla diciendo qué variable arreglar. */
    static String requireHttpUrl(String url, String variable) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Falta " + variable);
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(variable.split(" ")[0] + " no es una URL válida");
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException(variable.split(" ")[0] + " debe ser una URL http o https absoluta");
        }
        return url.strip();
    }

    static void requireSaneTimeouts(String prefix, Duration connect, Duration read, long maxRetries) {
        if (connect.isNegative() || connect.isZero() || read.isNegative() || read.isZero()) {
            throw new IllegalArgumentException(prefix + ": los timeouts deben ser positivos");
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException(prefix + ": los reintentos deben estar entre 0 y 5 (sin ciclos ilimitados)");
        }
    }

    /**
     * Un {@code RestClient} con URL base, timeouts y <b>sin seguir redirecciones</b>: un token nunca se reenvía a
     * otro destino.
     */
    static RestClient.Builder restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withTimeouts(connectTimeout, readTimeout);
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .baseUrl(baseUrl);
    }

    /** Reintentos acotados con espera creciente, solo para lo que {@code transientFailure} considere transitorio. */
    static RetryTemplate retry(long maxRetries, Predicate<Throwable> transientFailure) {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(Duration.ofMillis(300))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(2))
                .predicate(transientFailure)
                .build());
    }
}
