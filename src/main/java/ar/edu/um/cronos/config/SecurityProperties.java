package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Seguridad de cronos: cómo se firman los JWT que emite. Cronos tiene la clave PRIVADA; atlas solo la pública
 * (ADR 0004, opción A).
 */
@ConfigurationProperties("cronos.security")
public record SecurityProperties(@DefaultValue Jwt jwt) {

    /**
     * @param privateKey         clave privada RSA en PEM PKCS#8 ({@code BEGIN PRIVATE KEY}), por ejemplo desde un
     *                           secreto de Docker; las secuencias {@code \n} literales se toman como saltos de línea
     * @param privateKeyLocation ruta o URL ({@code file:}, {@code classpath:}) de un archivo con esa clave; sin
     *                           esquema se toma como ruta del sistema de archivos
     * @param ttl                vigencia normal del token
     * @param rememberMeTtl      vigencia con {@code rememberMe}
     */
    public record Jwt(
            String privateKey,
            String privateKeyLocation,
            @DefaultValue("1h") Duration ttl,
            @DefaultValue("30d") Duration rememberMeTtl) {

        public Jwt {
            privateKey = blankToNull(privateKey);
            privateKeyLocation = blankToNull(privateKeyLocation);
            if (privateKey == null && privateKeyLocation == null) {
                throw new IllegalArgumentException("Falta CRONOS_JWT_PRIVATE_KEY_LOCATION (archivo) o "
                        + "CRONOS_JWT_PRIVATE_KEY (PEM) con la clave privada con la que cronos firma los JWT");
            }
            if (privateKey != null && privateKeyLocation != null) {
                throw new IllegalArgumentException(
                        "Definí solo una: CRONOS_JWT_PRIVATE_KEY_LOCATION o CRONOS_JWT_PRIVATE_KEY, no las dos");
            }
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("CRONOS_JWT_TTL debe ser positiva");
            }
            if (rememberMeTtl.compareTo(ttl) < 0) {
                throw new IllegalArgumentException("CRONOS_JWT_REMEMBER_ME_TTL no puede ser menor que CRONOS_JWT_TTL");
            }
        }

        /** No imprime la clave: este objeto puede terminar en un log de arranque. */
        @Override
        public String toString() {
            return "Jwt[privateKey=" + (privateKey == null ? "-" : "(definida)") + ", privateKeyLocation="
                    + privateKeyLocation + ", ttl=" + ttl + ", rememberMeTtl=" + rememberMeTtl + "]";
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
