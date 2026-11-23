-- Procesos de reserva de los usuarios finales (enunciado 4.2 y 7). Cada proceso queda asociado al usuario que lo inició:
-- la cátedra solo conoce la cuenta técnica del proyecto, así que la propiedad por usuario vive acá.
--
-- Cronos NO guarda el catálogo: el nombre del profesional es una foto descriptiva de cuando se reservó, no el dato
-- vigente (enunciado 4.2). Los datos personales del paciente son los que el usuario escribió para la cátedra.

CREATE TABLE reservation_process (
    id                     bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Dueño del proceso. Sin ON DELETE: no se borra un usuario que tiene reservas.
    user_id                bigint       NOT NULL REFERENCES app_user (id),
    -- Lo que asigna la cátedra. Son NULL hasta que existe el hold; Postgres permite varios NULL en un UNIQUE.
    reservation_process_id varchar(100),
    hold_id                varchar(100),
    professional_id        bigint       NOT NULL,
    professional_first_name varchar(100),
    professional_last_name  varchar(100),
    appointment_date       date         NOT NULL,
    start_time             time         NOT NULL,
    end_time               time         NOT NULL,
    patient_first_name     varchar(100) NOT NULL,
    patient_last_name      varchar(100) NOT NULL,
    status                 varchar(30)  NOT NULL,
    expires_at             timestamptz,
    -- Un motivo estable (el nombre de un código), nunca un texto de la cátedra ni un dato del usuario.
    failure_code           varchar(60),
    -- Bloqueo optimista: dos escrituras simultáneas sobre el mismo proceso no se pisan.
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    CONSTRAINT reservation_process_process_key UNIQUE (reservation_process_id),
    CONSTRAINT reservation_process_hold_key UNIQUE (hold_id),
    CONSTRAINT reservation_process_status_valid CHECK (status IN
        ('STARTING', 'HELD', 'WAITING_FOR_PHONE', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'INVALID', 'CANCELLED', 'FAILED')),
    CONSTRAINT reservation_process_times_valid CHECK (end_time > start_time),
    -- Un proceso que ya tiene hold sabe cómo lo llama la cátedra.
    CONSTRAINT reservation_process_hold_known CHECK (
        status IN ('STARTING', 'FAILED') OR (hold_id IS NOT NULL AND reservation_process_id IS NOT NULL))
);

-- Un usuario no puede tener dos procesos activos para el mismo turno: pedir dos veces lo mismo (o dos pedidos a la
-- vez) no crea dos holds. Los procesos terminados mal o cerrados no cuentan, para poder volver a intentarlo.
CREATE UNIQUE INDEX reservation_process_active_slot
    ON reservation_process (user_id, professional_id, appointment_date, start_time)
    WHERE status IN ('STARTING', 'HELD', 'WAITING_FOR_PHONE', 'CONFIRMED');

-- "Mis reservas": todo lo de un usuario, lo más reciente primero.
CREATE INDEX reservation_process_user_created ON reservation_process (user_id, created_at DESC);
