package ar.edu.um.cronos.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El teléfono se valida como lo hace la cátedra (referencia 15.5) y nunca se imprime. */
class PhoneNumberTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "+5492615551234 | +5492615551234",
            "2615551234 | 2615551234",
            "0261 555-1234 | 02615551234",
            "(261) 555.1234 | 2615551234",
            "+54 9 (261) 555-1234 | +5492615551234",
            "1234567 | 1234567",
            "+123456789012345 | +123456789012345",
            "+ 5492615551234 | +5492615551234"})
    void quitaEspaciosGuionesParentesisYPuntosYAceptaLoQueLaCatedraAcepta(String ingresado, String limpio) {
        assertThat(PhoneNumber.parse(ingresado).value()).isEqualTo(limpio);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "   ", "123456", "1234567890123456", "+", "++5492615551234", "5492615551234+",
            "26155a1234", "261-555-12\t34", "261\n5551234", "٢٦١٥٥٥١٢٣٤", "+54 9 261 555 1234 ext 5", "abc", "1-2-3",
            "+1234567890123456"})
    void unNumeroConOtroFormatoSeRechaza(String ingresado) {
        assertThatThrownBy(() -> PhoneNumber.parse(ingresado)).isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    void unValorEnormeSeRechazaSinProcesarlo() {
        assertThatThrownBy(() -> PhoneNumber.parse("1".repeat(100_000))).isInstanceOf(InvalidPhoneNumberException.class);
        assertThatThrownBy(() -> PhoneNumber.parse(" ".repeat(50) + "5492615551234"))
                .as("ni siquiera con separadores de sobra").isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    void elConstructorDirectoTambienValida() {
        assertThatThrownBy(() -> new PhoneNumber("no es un numero")).isInstanceOf(InvalidPhoneNumberException.class);
        assertThat(new PhoneNumber("+5492615551234").value()).isEqualTo("+5492615551234");
    }

    @Test
    void ningunTextoRepiteElNumero() {
        var numero = PhoneNumber.parse("+5492615551234");

        assertThat(numero.toString()).doesNotContain("5492615551234").doesNotContain("261");
        assertThatThrownBy(() -> PhoneNumber.parse("26155a1234-secreto")).satisfies(e ->
                assertThat(e.getMessage()).doesNotContain("26155a1234").doesNotContain("secreto"));
    }
}
