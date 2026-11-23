package ar.edu.um.cronos.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Respuesta del login: el JWT en {@code id_token}, como la cátedra y JHipster. */
record TokenResponse(@JsonProperty("id_token") String idToken) {

    @Override
    public String toString() {
        return "TokenResponse[idToken=(oculto)]";
    }
}
