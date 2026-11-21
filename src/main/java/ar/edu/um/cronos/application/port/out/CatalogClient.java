package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.ProfessionalAgenda;

/**
 * Lo que cronos le pide al servicio de catálogo (atlas). Cronos no guarda el catálogo: lo consulta cada vez que lo
 * necesita (enunciado 3 y 4.2).
 */
public interface CatalogClient {

    /**
     * La agenda vigente de un profesional.
     *
     * @param userToken el JWT del usuario tal como llegó, que se reenvía sin modificar (ADR 0004). Es un dato
     *                  opaco para esta capa: nunca se registra
     * @throws ProfessionalNotFoundException si el catálogo no conoce al profesional
     * @throws CatalogUnavailableException   si el catálogo no responde o no se lo puede consultar
     */
    ProfessionalAgenda findAgenda(long professionalId, String userToken);
}
