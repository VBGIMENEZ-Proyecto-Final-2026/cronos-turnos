-- Usuarios finales de cronos-turnos y sus autoridades (ADR 0005). El contrato es compatible con el usuario de
-- JHipster (enunciado 3.2): el backend decide id, activación, autoridades y fechas; quien se registra no.
-- Las reglas que el dominio ya garantiza (login y email en minúsculas) se repiten acá como CHECK: si una
-- escritura salta el dominio, la base la rechaza en lugar de guardar un duplicado disfrazado.

CREATE TABLE app_user (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login         varchar(50) NOT NULL,
    password_hash varchar(100) NOT NULL,
    first_name    varchar(50),
    last_name     varchar(50),
    email         varchar(254),
    image_url     varchar(256),
    lang_key      varchar(10) NOT NULL DEFAULT 'es',
    activated     boolean     NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT app_user_login_key UNIQUE (login),
    -- Postgres permite varios NULL en una columna UNIQUE: el email es opcional y único solo si se informa.
    CONSTRAINT app_user_email_key UNIQUE (email),
    CONSTRAINT app_user_login_lowercase CHECK (login = lower(login)),
    CONSTRAINT app_user_email_lowercase CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT app_user_login_length CHECK (char_length(login) >= 3)
);

CREATE TABLE user_authority (
    user_id   bigint      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    authority varchar(50) NOT NULL,
    PRIMARY KEY (user_id, authority),
    CONSTRAINT user_authority_format CHECK (authority ~ '^ROLE_[A-Z_]+$')
);
