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

### Login: `POST /api/authenticate` (público)

```bash
curl -s -X POST localhost:8081/api/authenticate -H 'Content-Type: application/json' \
  -d '{"username":"juan.perez","password":"una-clave","rememberMe":false}'
# {"id_token":"<jwt>"}   (el mismo token también va en el encabezado Authorization: Bearer)
```

Misma forma que el login de la cátedra (`INTEGRATION_REFERENCE`, 5.2): `username` de 1 a 254, `password` de 4 a 100 y `rememberMe` opcional. El token es un JWT **RS256** con cuatro claims: `sub` (el login), `auth` (las autoridades separadas por espacio, p. ej. `ROLE_USER`, el claim que atlas lee), `iat` y `exp`. Dura 1 hora, o 30 días con `rememberMe` (`CRONOS_JWT_TTL` y `CRONOS_JWT_REMEMBER_ME_TTL`). No lleva email, nombres ni nada de la contraseña.

Una contraseña equivocada, un login inexistente y un usuario no activo dan **el mismo** `401` (`INVALID_CREDENTIALS`): no se revela qué logins existen. Cuando el login no existe igual se calcula un hash de relleno, para que el tiempo de respuesta tampoco lo delate. El login no distingue mayúsculas.

### Clave de firma (obligatoria)

Cronos firma con una clave **privada** RSA (PEM PKCS#8, 2048 bits o más); atlas valida con la **pública**. **Sin clave la aplicación no arranca.** Se carga por `CRONOS_JWT_PRIVATE_KEY_LOCATION` (archivo) o `CRONOS_JWT_PRIVATE_KEY` (PEM con `\n` literales), solo una de las dos, y nunca va en el repositorio (`*.pem` y `*.key` están en `.gitignore`). La pública se deriva de la privada, así que no hace falta una segunda variable.

```bash
# Par de claves de desarrollo (si no tenés uno; atlas usa el mismo par en ~/.atlas-dev/)
mkdir -p ~/.atlas-dev && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out ~/.atlas-dev/dev-private.pem
openssl pkey -in ~/.atlas-dev/dev-private.pem -pubout -out ~/.atlas-dev/dev-public.pem
# En el .env de cronos:   CRONOS_JWT_PRIVATE_KEY_LOCATION=/ruta/a/.atlas-dev/dev-private.pem
# En el .env de atlas:    ATLAS_JWT_PUBLIC_KEY_LOCATION=/ruta/a/.atlas-dev/dev-public.pem
```

Las pruebas no usan ninguna clave guardada: generan una al vuelo.

### Límites conocidos

- **Contraseñas y logs de Spring:** con la configuración normal la contraseña no aparece en ningún log (hay una prueba con niveles altos para la seguridad, el SQL con parámetros y el código propio). Pero si se sube a `DEBUG` o `TRACE` el log de `org.springframework.web`, Spring escribe el *rejected value* de una validación fallida, y eso incluye la contraseña cuando es ella la que no cumple el largo. No se activa ese nivel fuera del desarrollo.
- **Sin límite de intentos de login:** nada frena a quien prueba contraseñas contra un login. El hash lento de BCrypt y el largo máximo de la contraseña acotan el costo de cada intento, pero no los cuentan.
- **Sin revocación de tokens:** un token vale hasta que vence (1 hora, o 30 días con `rememberMe`); no hay cierre de sesión en el servidor.
- **Mínimo de 4 caracteres:** es el de la cátedra y es débil.
- **Contraseñas de más de 72 bytes:** BCrypt no las admite, así que se prehashean con SHA-256 antes de BCrypt; el hash guardado sigue siendo un BCrypt estándar.

Estado actual: esqueleto, registro de usuarios (migración V1) y login con JWT RS256. La seguridad de las rutas protegidas (`GET /api/account`), la prueba cruzada con atlas, las reservas y el resto vienen en las próximas etapas y ramas.
