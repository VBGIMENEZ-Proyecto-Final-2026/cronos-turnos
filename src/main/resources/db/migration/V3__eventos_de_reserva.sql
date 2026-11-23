-- Eventos de la cátedra por Kafka (issue #15): lo que hace falta guardar para responder el teléfono, para aplicar cada
-- evento una sola vez y para no perder los que no se pueden aplicar.

-- 1. Datos nuevos del proceso.
ALTER TABLE reservation_process
    -- eventId del último AdditionalInformationRequested: se manda como requestEventId al responder el teléfono.
    ADD COLUMN request_event_id        varchar(200),
    -- Código del último AdditionalInformationRejected. Un rechazo NO termina el proceso (referencia 15.7).
    ADD COLUMN last_rejection_reason   varchar(60),
    -- Id de la reserva confirmada en la cátedra (AppointmentConfirmed).
    ADD COLUMN catedra_reservation_id  bigint;

ALTER TABLE reservation_process
    ADD CONSTRAINT reservation_process_catedra_reservation_key UNIQUE (catedra_reservation_id),
    -- Una reserva confirmada (o cancelada después de confirmarse) sabe cómo la llama la cátedra.
    ADD CONSTRAINT reservation_process_confirmed_known CHECK (
        status NOT IN ('CONFIRMED', 'CANCELLED') OR catedra_reservation_id IS NOT NULL);

-- 2. REJECTED deja de ser un estado: tras un rechazo del teléfono el proceso sigue esperando hasta que vence. Si
-- existiera una fila con ese estado, esta migración falla a propósito en lugar de reinterpretarla (ninguna versión
-- anterior de cronos la escribía).
ALTER TABLE reservation_process DROP CONSTRAINT reservation_process_status_valid;
ALTER TABLE reservation_process ADD CONSTRAINT reservation_process_status_valid CHECK (status IN
    ('STARTING', 'HELD', 'WAITING_FOR_PHONE', 'CONFIRMED', 'EXPIRED', 'INVALID', 'CANCELLED', 'FAILED'));

-- 3. Eventos ya aplicados: la entrega de Kafka es "al menos una vez", así que el mismo eventId puede llegar de nuevo
-- y no debe repetir ningún efecto (referencia 15.1 y 18.1). La clave primaria decide quién llegó primero; el registro
-- y el cambio de estado van en la misma transacción.
CREATE TABLE processed_event (
    event_id               varchar(200) PRIMARY KEY,
    event_type             varchar(60)  NOT NULL,
    reservation_process_id varchar(100) NOT NULL,
    processed_at           timestamptz  NOT NULL
);

CREATE INDEX processed_event_process ON processed_event (reservation_process_id);

-- 4. Eventos que no se pudieron aplicar porque el proceso no existe en esta base (se borró, o son de otra corrida).
-- No traban el consumo ni se pierden en silencio: quedan acá para diagnosticarlos. Sin datos personales: un evento de
-- la cátedra no los trae, y de todos modos solo se guardan identificadores.
CREATE TABLE unmatched_event (
    id                     bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id               varchar(200) NOT NULL,
    event_type             varchar(60)  NOT NULL,
    reservation_process_id varchar(100) NOT NULL,
    reason                 varchar(40)  NOT NULL,
    received_at            timestamptz  NOT NULL,
    -- El mismo evento leído dos veces no se estaciona dos veces.
    CONSTRAINT unmatched_event_event_key UNIQUE (event_id)
);
