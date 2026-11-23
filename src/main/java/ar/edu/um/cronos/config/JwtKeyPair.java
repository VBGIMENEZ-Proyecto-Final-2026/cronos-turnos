package ar.edu.um.cronos.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Las claves con las que cronos firma sus JWT (la privada) y valida los que recibe (la pública, derivada de la
 * privada). No tiene {@code toString} propio de la clave: el de un record imprimiría las dos, así que se oculta.
 */
public record JwtKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {

    @Override
    public String toString() {
        return "JwtKeyPair[(ocultas)]";
    }
}
