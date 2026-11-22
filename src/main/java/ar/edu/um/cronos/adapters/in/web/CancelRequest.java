package ar.edu.um.cronos.adapters.in.web;

/**
 * El cuerpo opcional de la cancelación. {@code reason} es texto libre del usuario (hasta 500 caracteres): se manda a la cátedra
 * y no se guarda ni se registra, así que {@code toString} no lo imprime (Spring escribe los objetos que lee del cuerpo cuando
 * el nivel es {@code TRACE}). Su largo se valida en el caso de uso, no con anotaciones: un error de validación de Spring puede
 * repetir el valor rechazado en un log.
 */
record CancelRequest(String reason) {

    @Override
    public String toString() {
        return "CancelRequest[reason=***]";
    }
}
