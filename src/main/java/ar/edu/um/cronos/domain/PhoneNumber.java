package ar.edu.um.cronos.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * El teléfono que el usuario informa para confirmar una reserva. Se valida igual que la cátedra (referencia 15.5): se quitan
 * espacios, guiones, paréntesis y puntos, y lo que queda tiene que cumplir {@code ^\+?[0-9]{7,15}$}.
 *
 * <p><b>Es un dato personal y cronos no lo guarda ni lo registra</b>: se manda a la cátedra y se descarta. Por eso este tipo
 * nunca lo imprime: ni {@code toString}, ni el mensaje de {@link InvalidPhoneNumberException} lo incluyen. El único lugar
 * que lee el valor es el adaptador que arma el mensaje para la cátedra.
 *
 * @param value el número ya limpio
 */
public record PhoneNumber(String value) {

    private static final Pattern VALID = Pattern.compile("\\+?[0-9]{7,15}");
    /** Lo que la cátedra ignora antes de validar. */
    private static final Pattern SEPARATORS = Pattern.compile("[ \\-().]");
    /** Un tope para no procesar un valor enorme: el más largo válido (15 dígitos y el +) con separadores de sobra. */
    private static final int MAX_RAW_LENGTH = 40;

    public PhoneNumber {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new InvalidPhoneNumberException();
        }
    }

    /**
     * @param raw lo que escribió el usuario
     * @throws InvalidPhoneNumberException si falta, es muy largo o no cumple el formato después de limpiarlo
     */
    public static PhoneNumber parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_RAW_LENGTH) {
            throw new InvalidPhoneNumberException();
        }
        return new PhoneNumber(SEPARATORS.matcher(raw).replaceAll(""));
    }

    @Override
    public String toString() {
        return "PhoneNumber[***]";
    }
}
