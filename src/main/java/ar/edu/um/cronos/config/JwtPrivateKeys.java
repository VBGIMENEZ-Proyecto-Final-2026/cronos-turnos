package ar.edu.um.cronos.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

/**
 * Carga la clave privada con la que se firman los JWT y deriva su clave pública (la que usa cronos para validar
 * sus propios tokens, sin una segunda variable). Falla al arrancar con un mensaje claro si no se puede leer o no
 * es una clave RSA PKCS#8 de al menos 2048 bits. Los mensajes nunca repiten el contenido de la clave.
 */
final class JwtPrivateKeys {

    static final int MIN_BITS = 2048;

    private JwtPrivateKeys() {
    }

    static RSAPrivateKey load(SecurityProperties.Jwt jwt) {
        String pem = jwt.privateKey() != null ? jwt.privateKey().replace("\\n", "\n") : read(jwt.privateKeyLocation());
        RSAPrivateKey key;
        try {
            key = RsaKeyConverters.pkcs8().convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // Sin la causa: el mensaje de la excepción de origen podría citar parte de la clave.
            throw new IllegalArgumentException("La clave privada JWT no es un PEM PKCS#8 RSA válido "
                    + "(debe empezar con -----BEGIN PRIVATE KEY-----)");
        }
        if (key == null || key.getModulus().bitLength() < MIN_BITS) {
            throw new IllegalArgumentException("La clave privada JWT debe ser RSA de al menos " + MIN_BITS + " bits");
        }
        return key;
    }

    /** La pública sale de la privada: hace falta que la clave tenga los parámetros CRT (las generadas por OpenSSL los tienen). */
    static RSAPublicKey publicKeyOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalArgumentException("La clave privada JWT no trae el exponente público: no se puede derivar la pública");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo derivar la clave pública JWT", e);
        }
    }

    private static String read(String location) {
        try {
            Resource resource = resource(location);
            if (!resource.exists()) {
                throw new IllegalArgumentException("No existe el archivo de la clave privada JWT: " + location);
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo leer la clave privada JWT: " + location);
        }
    }

    private static Resource resource(String location) throws MalformedURLException {
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        if (location.startsWith("file:")) {
            return new UrlResource(location);
        }
        return new FileSystemResource(location);
    }
}
