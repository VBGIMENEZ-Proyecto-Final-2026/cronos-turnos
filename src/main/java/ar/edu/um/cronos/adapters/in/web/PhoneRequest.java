package ar.edu.um.cronos.adapters.in.web;

/**
 * El teléfono que el usuario informa para confirmar una reserva. Se valida en el caso de uso (formato de la cátedra), no con
 * anotaciones: un error de validación de Spring puede repetir el valor rechazado en un log.
 *
 * <p>Es un dato personal: {@code toString} no lo imprime (Spring escribe los objetos que lee del cuerpo cuando se activa el
 * nivel {@code TRACE}).
 */
record PhoneRequest(String phoneNumber) {

    @Override
    public String toString() {
        return "PhoneRequest[phoneNumber=***]";
    }
}
