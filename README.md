# cronos-turnos

Servicio backend (Java + Spring Boot) responsable de construir la disponibilidad de turnos, gestionar holds temporales y conducir el proceso de confirmación de reservas (REST + Kafka) contra el servicio central de la cátedra. Gestiona el registro y autenticación de usuarios finales con JWT propio y mantiene la propiedad de cada reserva por usuario final. Consulta el catálogo vigente a atlas-catalogo mediante contrato propio; no mantiene su propia copia del catálogo.

Documentación, contratos y plantillas: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs).

## Configuración

Las variables de entorno se declaran en `.env.example` (versionado, con placeholders). Para trabajar en local:

```bash
cp .env.example .env   # .env está ignorado por git
```

Completar los valores con la respuesta de la cuenta técnica de la cátedra. Los dos backends comparten el mismo set de variables `CATEDRA_*`. Nunca commitear `.env`, tokens ni IPs. Convención completa en `alejandria-docs`.

## Base de datos local

PostgreSQL con Docker Compose. Requiere las variables `CRONOS_DB_*` del `.env`.

```bash
docker compose up -d      # levanta la base (puerto 5434 en localhost)
docker compose ps         # el estado debe pasar a "healthy"
docker compose down       # apaga y conserva los datos (-v los borra)
```

La base solo escucha en `127.0.0.1`. No usar H2, SQLite ni bases embebidas. Las migraciones de este servicio son propias y no tocan la base del otro.

## Ejecutar el servicio

Requiere JDK 25 y la base levantada (sección anterior).

```bash
./gradlew bootRun                         # arranca en el puerto 8081
curl localhost:8081/actuator/health       # {"status":"UP", ...}
./gradlew test                            # contexto + ArchUnit (usa Testcontainers: necesita Docker o Podman)
```

Con Podman: `export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`.

## Usuarios

Cronos registra a los usuarios finales y (próximamente) emite el JWT. Sin JHipster, con Spring Security a mano y el contrato de usuario compatible con el de JHipster (ADR 0005 en la biblioteca).

### Registro: `POST /api/register` (público)

```bash
curl -i -X POST localhost:8081/api/register -H 'Content-Type: application/json' \
  -d '{"login":"juan.perez","password":"una-clave","firstName":"Juan","lastName":"Pérez","email":"juan@ejemplo.test"}'
```

| Campo | Regla |
|---|---|
| `login` | Obligatorio, 3 a 50, letras, números y `. _ @ -`. Se guarda en minúsculas y es único aunque cambien las mayúsculas. |
| `password` | Obligatoria, 4 a 100 caracteres. Se guarda con hash (SHA-256 + BCrypt), nunca en claro. |
| `firstName`, `lastName` | Opcionales, hasta 50. |
| `email` | Opcional, válido, 5 a 254. Único si se informa. |
| `imageUrl`, `langKey` | Opcionales (hasta 256; 2 a 10, por defecto `es`). |

`201` con el usuario (sin contraseña ni hash). El usuario queda **activo** y con `ROLE_USER`; `id`, activación, autoridades y fechas los decide el backend y se ignoran si llegan en el cuerpo. Errores `400` como `application/problem+json` con `code`: `VALIDATION_ERROR` (con `fieldErrors`), `INVALID_PASSWORD`, `USERNAME_ALREADY_EXISTS` y `EMAIL_ALREADY_EXISTS`. No repiten los valores recibidos.

Las validaciones son las del registro de la cuenta técnica de la cátedra (`INTEGRATION_REFERENCE`, 5.1), la única definición que existe: el enunciado no fija otras para el usuario final.

### Límites conocidos

- **Contraseñas y logs de Spring:** con la configuración normal la contraseña no aparece en ningún log (hay una prueba con niveles altos para la seguridad, el SQL con parámetros y el código propio). Pero si se sube a `DEBUG` o `TRACE` el log de `org.springframework.web`, Spring escribe el *rejected value* de una validación fallida, y eso incluye la contraseña cuando es ella la que no cumple el largo. No se activa ese nivel fuera del desarrollo.
- **Mínimo de 4 caracteres:** es el de la cátedra y es débil.
- **Contraseñas de más de 72 bytes:** BCrypt no las admite, así que se prehashean con SHA-256 antes de BCrypt; el hash guardado sigue siendo un BCrypt estándar.

Estado actual: esqueleto más el registro de usuarios (migración V1). El login con JWT, las reservas y el resto vienen en las próximas ramas.
