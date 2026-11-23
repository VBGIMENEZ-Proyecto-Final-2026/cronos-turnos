-- El teléfono de una reserva (issue #15, paso 5). Cronos NO guarda el número: es un dato personal, se manda a la cátedra
-- por Kafka y se descarta. Lo único que se guarda es CUÁNDO se mandó, para no mandar dos veces el mismo teléfono mientras
-- la cátedra no respondió y para poder mostrar "teléfono enviado".
--
-- Queda en NULL si todavía no se mandó, o si la cátedra rechazó el teléfono o volvió a pedirlo (se puede mandar otro).
ALTER TABLE reservation_process ADD COLUMN phone_submitted_at timestamptz;
