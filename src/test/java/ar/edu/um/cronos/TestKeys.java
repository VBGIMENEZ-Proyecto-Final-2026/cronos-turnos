package ar.edu.um.cronos;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Par de claves RSA de prueba, generado una vez por JVM de prueba. No hay ninguna clave en el repositorio: ni
 * siquiera de prueba (los {@code *.pem} y {@code *.key} están en {@code .gitignore}).
 */
public final class TestKeys {

    private static final KeyPair PAIR = generate(2048);

    private TestKeys() {
    }

    public static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAPrivateKey privateKey() {
        return (RSAPrivateKey) PAIR.getPrivate();
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) PAIR.getPublic();
    }

    /** PEM PKCS#8 de la clave privada de prueba. */
    public static String privatePem() {
        return pem("PRIVATE KEY", PAIR.getPrivate().getEncoded());
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
