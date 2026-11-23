package ar.edu.um.cronos.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Los holds en el servicio central de la cátedra (referencia de integración, 9 y 10). Se usa siempre la cuenta
 * técnica del proyecto: la cátedra no conoce a los usuarios finales de cronos.
 *
 * <p>Ninguna de las dos operaciones se reintenta sola: tienen efectos y un timeout no prueba que fallaron (referencia,
 * 18.4). Quien la llama decide cómo reconciliar.
 */
public interface HoldGateway {

    /** Lo que la cátedra devuelve al crear un hold. */
    record HoldCreated(String holdId, String reservationProcessId, Instant expiresAt) {
    }

    /** Lo que la cátedra devuelve al confirmar inicialmente un hold. */
    record HoldConfirmed(String reservationProcessId, Instant expiresAt) {
    }

    /**
     * Pide un hold temporal.
     *
     * @throws CatedraRejectedException    si la cátedra respondió y lo rechazó (turno tomado, inválido...)
     * @throws CatedraUnavailableException si no se sabe si lo creó: no respondió, error de red o de servidor
     */
    HoldCreated createHold(long professionalId, LocalDate date, LocalTime startTime, String externalPatientId);

    /**
     * Confirma inicialmente un hold: la cátedra publica {@code AdditionalInformationRequested} por Kafka.
     *
     * @throws CatedraRejectedException    si la cátedra respondió y lo rechazó (vencido, ya confirmado...)
     * @throws CatedraUnavailableException si no se sabe si lo confirmó
     */
    HoldConfirmed confirmHold(String holdId, String externalPatientId, String patientFirstName,
                              String patientLastName);
}
