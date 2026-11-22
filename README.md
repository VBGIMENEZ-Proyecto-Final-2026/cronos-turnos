# cronos-turnos

Servicio backend (Java + Spring Boot) responsable de construir la disponibilidad de turnos, gestionar holds temporales y conducir el proceso de confirmación de reservas (REST + Kafka) contra el servicio central de la cátedra. Gestiona el registro y autenticación de usuarios finales con JWT propio y mantiene la propiedad de cada reserva por usuario final. Consulta el catálogo vigente a atlas-catalogo mediante contrato propio; no mantiene su propia copia del catálogo.

Documentación, contratos y plantillas: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs).

## Configuración

Las variables de entorno se declaran en `.env.example` (versionado, con placeholders). Para trabajar en local:

```bash
cp .env.example .env   # .env está ignorado por git
```

Completar los valores con la respuesta de la cuenta técnica de la cátedra. Los dos backends comparten el mismo set de variables `CATEDRA_*`. **Cronos no arranca sin `CATEDRA_REST_BASE_URL` y `CATEDRA_ID_TOKEN`** (el mensaje dice cuál falta). Nunca commitear `.env`, tokens ni IPs. Convención completa en `alejandria-docs`.

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

Cronos registra a los usuarios finales, emite el JWT y valida los tokens que recibe. Sin JHipster, con Spring Security a mano y el contrato de usuario compatible con el de JHipster (ADR 0005 en la biblioteca).

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

### Cuenta del usuario: `GET /api/account` (requiere token)

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/api/account
```

Devuelve la cuenta (la misma forma que el registro, sin contraseña ni hash) **del dueño del token**: el usuario sale del `sub` del token ya validado y la ruta no recibe ningún identificador, así que nadie puede pedir la cuenta de otro. Pide `ROLE_USER` o `ROLE_ADMIN`.

### Qué valida cronos de un token y qué responde

Cronos valida sus propios tokens con la clave **pública derivada de la privada**: solo RS256 (un token `HS256` o `none` se rechaza), firma, vencimiento con una tolerancia de reloj de 60 segundos (`CRONOS_JWT_CLOCK_SKEW`, 0 a 5 minutos) y los claims `sub` y `exp` obligatorios. Las autoridades salen del claim `auth`.

| Situación | Respuesta |
|---|---|
| Sin token, token mal formado, vencido, alterado, de otra clave, de otro algoritmo o sin `exp` | `401` `NO_AUTENTICADO` (con `WWW-Authenticate: Bearer`) |
| Token válido de un usuario que ya no existe o no está activo | `401` `NO_AUTENTICADO` |
| Token válido pero sin el rol que pide la ruta, o una ruta no habilitada | `403` `SIN_PERMISO` |
| Una ruta no habilitada, sin token | `401` `NO_AUTENTICADO` (no se sabe quién es, así que no llega a `403`) |

Son `application/problem+json` con los mismos códigos que atlas. El rechazo **no dice por qué** falló el token y nunca lo repite; el log registra el método y la ruta (y el usuario en un `403`), jamás el token. Las rutas públicas (`register`, `authenticate`, `health`) **ignoran el encabezado `Authorization`**: un cliente con un token vencido guardado puede volver a iniciar sesión.

### Simular el recorrido de un cliente (y la prueba cruzada con atlas)

Con cronos (`:8081`) y atlas (`:8080`) corriendo y con la clave pública del mismo par en atlas:

```bash
scripts/recorrido-cliente.sh          # LIMPIAR=1 borra al final el usuario de prueba
```

Registra a un cliente, inicia sesión, consulta su cuenta, usa **el mismo token en atlas** (categorías y profesionales), prueba los malos tokens (sin token, alterado, de otra clave, vencido, sin rol) en los dos servicios y termina pidiéndole a cronos los turnos libres de un profesional (atlas + cátedra). Cada paso dice si la respuesta fue la esperada y el script sale con error si alguna no lo fue. Requiere `curl`, `jq`, `openssl` y `python3`. Los tokens vencido y sin rol los firma el script con la clave de desarrollo (`DEV_PRIVATE_KEY`, por defecto `~/.atlas-dev/dev-private.pem`); sin ella se omiten. El último bloque necesita además que la **cátedra** responda (cronos le pide las ocupaciones con la cuenta técnica); si no responde, ese paso sale mal con `503 CATEDRA_UNAVAILABLE`.

### CORS

Sin `CRONOS_CORS_ALLOWED_ORIGINS` no se acepta ningún origen. Con una lista (`esquema://host[:puerto]`, sin comodines ni rutas) se aceptan solo esos, con `GET`, `POST` y `OPTIONS`, los encabezados `Authorization`, `Content-Type` y `Accept`, y **sin credenciales**. La app Android no es un navegador y no lo necesita.

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
- **Sin revocación de tokens:** un token vale hasta que vence (1 hora, o 30 días con `rememberMe`); no hay cierre de sesión en el servidor. Lo único que lo invalida antes es borrar o desactivar al usuario: `GET /api/account` lo comprueba en cada pedido.
- **Los roles salen del token, no de la base:** si a un usuario se le cambia el rol, el token viejo conserva el anterior hasta que vence. `GET /api/account` muestra los roles de la base.
- **No se valida `iss` ni `aud` (el token no los lleva), y rotar la clave invalida todos los tokens emitidos y exige reiniciar atlas.**
- **Mínimo de 4 caracteres:** es el de la cátedra y es débil.
- **Contraseñas de más de 72 bytes:** BCrypt no las admite, así que se prehashean con SHA-256 antes de BCrypt; el hash guardado sigue siendo un BCrypt estándar.

## Disponibilidad

### Turnos libres: `GET /api/professionals/{professionalId}/availability?from=&to=` (requiere token)

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "localhost:8081/api/professionals/207/availability?from=2026-11-23&to=2026-11-25"
# {"professionalId":207,"from":"2026-11-23","to":"2026-11-25","slots":[{"date":"2026-11-23","startTime":"18:00:00","endTime":"18:30:00"}, ...]}
```

Pide **a atlas** los horarios semanales del profesional (`GET /api/professionals/{id}`, **reenviándole el mismo JWT del usuario**, ADR 0004) y **a la cátedra** las ocupaciones del rango (`GET /api/appointment-occupancies`, con el JWT técnico del proyecto). Devuelve la grilla de cada horario habilitado menos las ocupaciones `CONFIRMED` y `HELD`. **No guarda nada**: ni el catálogo ni las ocupaciones; cada consulta las pide de nuevo.

| Regla | Detalle |
|---|---|
| Grilla | De `startTime` a `endTime`, cada `slotDurationMinutes`. Solo cuentan los turnos que **entran completos**. |
| Qué no se ofrece | Un profesional o un horario **deshabilitado**, un turno que **ya empezó** (se decide en la hora local del consultorio, `CRONOS_CLINIC_ZONE`), uno ocupado o bloqueado. |
| Rango | Fechas `yyyy-MM-dd`, `from` no posterior a `to`, máximo 31 días (`CRONOS_AVAILABILITY_MAX_RANGE_DAYS`). |
| Orden de las consultas | Primero atlas; si atlas falla, **no se consulta a la cátedra**. Si el profesional no atiende en el rango, tampoco se la consulta. A la cátedra se le pregunta **una sola vez** por todo el rango. |

| Situación | Respuesta |
|---|---|
| Rango inválido (invertido o demasiado largo) | `400 INVALID_DATE_RANGE` |
| Fecha mal formada, falta un parámetro o el id no es un número | `400 VALIDATION_ERROR` (nombra el parámetro, no repite el valor) |
| Sin token / sin rol | `401 NO_AUTENTICADO` / `403 SIN_PERMISO` |
| Profesional que atlas no conoce | `404 PROFESSIONAL_NOT_FOUND` |
| Atlas caído, lento o que rechaza el token | `503 CATALOG_UNAVAILABLE` (sin catálogo no se puede validar la información vigente) |
| Cátedra caída, lenta o que rechaza la credencial técnica | `503 CATEDRA_UNAVAILABLE` (nunca se inventan turnos libres) |

**Timeouts y reintentos acotados.** Atlas: 2 s para conectar, 5 s para leer, 1 reintento; cátedra: 3 s, 10 s, 1 reintento (`ATLAS_*` y `CATEDRA_REST_*`, máximo 5 reintentos). Solo se reintentan **lecturas** y solo fallas transitorias (red, timeout y `503`). **No se siguen redirecciones**: un token nunca se reenvía a otro destino. Ningún token (el del usuario ni el técnico) se escribe en un log ni en un mensaje de error.

**Límites conocidos:**
- Un turno se identifica por **fecha y hora de inicio**, como informa la cátedra; si dos horarios del mismo día se superponen con grillas desfasadas, un turno ocupado solo quita el que empieza a esa hora.
- La disponibilidad es una **foto**: entre que se consulta y se pide un hold, otro usuario puede tomar el turno. El hold (próxima etapa) es lo que lo reserva.
- Los horarios se truncan al minuto (se ignoran los segundos).
- Atlas responde con su copia local: si no sincronizó con la cátedra, los horarios pueden estar desactualizados.
- Probado de punta a punta con atlas real **y la cátedra real** el 2026-11-21 (evidencia `isla-2-disponibilidad.md` en la biblioteca): el formato de `appointment-occupancies` coincide con la referencia y un turno `CONFIRMED` real se restó.

## Reservas

### Iniciar una reserva: `POST /api/reservations` (requiere token)

```bash
curl -s -X POST localhost:8081/api/reservations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"professionalId":218,"date":"2026-12-15","startTime":"14:30","patientFirstName":"Juan","patientLastName":"Pérez"}'
# 202 {"id":6,"status":"WAITING_FOR_PHONE","professionalId":218,...,"expiresAt":"2026-12-15T14:44:50Z"}
```

Valida el turno contra atlas (el profesional existe, está habilitado y el turno está en su agenda y no empezó), le pide un **hold** a la cátedra y lo **confirma inicialmente**. Deja un proceso de reserva **asociado al usuario del token**, que queda esperando la información adicional (el teléfono y el resultado final llegan por Kafka: issue #15). Responde `202 Accepted` porque el proceso sigue.

| Campo | Regla |
|---|---|
| `professionalId`, `date`, `startTime` | Obligatorios. El turno tiene que existir en la agenda vigente del profesional. |
| `patientFirstName`, `patientLastName` | Obligatorios, 2 a 100 caracteres (las reglas de la cátedra para el paciente). |
| Cualquier otro campo (`id`, `status`, `userId`, `externalPatientId`...) | **Se ignora.** El dueño es el del token; la cátedra conoce al paciente como `user-<id>`, un identificador opaco que no revela el login. |

**Estados del proceso** (`STARTING` → `HELD` → `WAITING_FOR_PHONE` → `CONFIRMED` → `CANCELLED`, y los finales `REJECTED`, `EXPIRED`, `INVALID` y `FAILED`). Un estado final no retrocede. La respuesta no expone el `holdId` ni el `reservationProcessId` de la cátedra.

**Cómo se evita perder o duplicar.** El proceso se guarda **antes** de cada llamada a la cátedra y nunca dentro de una transacción de base de datos mientras se espera a la red.
- **Pedir dos veces lo mismo no crea dos holds**: el proceso activo del usuario se reutiliza, y la base lo impide con un índice único parcial aunque dos pedidos lleguen a la vez.
- **Si no se sabe si la cátedra creó el hold** (no respondió), el proceso se cierra como `FAILED` (`CATEDRA_UNAVAILABLE`) y el hold, si existe, vence solo.
- **Si no se sabe si confirmó**, se reconcilia **una vez** (repetir la confirmación es seguro: `RESERVATION_PROCESS_ALREADY_CONFIRMED` cuenta como éxito) y, si sigue sin saberse, queda en `HELD`: **volver a mandar el mismo pedido retoma la confirmación** sin crear otro hold.
- Los holds **nunca se reintentan solos**: tienen efectos y un timeout no prueba que fallaron.
- **Un proceso que ya venció se cierra al volver a pedir el turno.** Si el proceso activo (`HELD` o `WAITING_FOR_PHONE`) ya pasó su `expiresAt`, se cierra como `EXPIRED` y se empieza uno nuevo; una reserva `CONFIRMED` no vence. Con la cátedra real el hold vence solo y cronos, sin consumidor de Kafka todavía, no se entera: sin esta regla el usuario recibiría un proceso que ya no existe.

| Situación | Respuesta |
|---|---|
| Cuerpo inválido | `400 VALIDATION_ERROR` |
| El turno no está en la agenda o ya empezó / el profesional está deshabilitado | `400 INVALID_SLOT` / `400 PROFESSIONAL_DISABLED` |
| Profesional que atlas no conoce | `404 PROFESSIONAL_NOT_FOUND` |
| Turno ya tomado en la cátedra | `409 SLOT_ALREADY_HELD` / `409 SLOT_ALREADY_RESERVED` |
| El hold venció antes de confirmarse | `409 HOLD_EXPIRED` |
| El mismo pedido se está procesando ahora | `409 RESERVATION_IN_PROGRESS` |
| Atlas caído | `503 CATALOG_UNAVAILABLE` |
| Cátedra caída, lenta o que rechaza la credencial técnica | `503 CATEDRA_UNAVAILABLE` |
| La cátedra rechaza por un motivo que no debería ocurrir (paciente que no coincide, hold de otra cuenta, cuerpo rechazado) | `502 CATEDRA_ERROR` (sin detalle; queda en el log) |

**Límites conocidos:**
- Todavía **no hay listado ni consulta** de reservas, ni cancelación, ni Kafka (issues #12 y #15): un proceso queda en `WAITING_FOR_PHONE` aunque la cátedra lo haya dado por vencido, hasta que el usuario vuelva a pedir ese turno (se cierra como `EXPIRED`) o se implemente el evento de expiración y el barrido al reiniciar (#15 y #13).
- Probado con **un hold real** contra la cátedra el 2026-11-21 (evidencia `isla-2-holds.md` en la biblioteca).
- Un proceso que queda en `STARTING` (por ejemplo, si cronos se corta justo después de crearse) o un hold huérfano se resuelven con la recuperación al reiniciar (issue #13); hasta entonces un pedido repetido recibe `409 RESERVATION_IN_PROGRESS`.
- El nombre del paciente es un dato personal: ninguna clase de cronos lo escribe en un log. Con `TRACE` en `org.hibernate` o en `org.postgresql`, Hibernate y el driver escriben los valores que enlazan al SQL (incluido el nombre); no se activa fuera del desarrollo.
- Los datos del profesional guardados con la reserva son una foto de cuando se reservó, no el catálogo vigente.

## Kafka: los resultados de las reservas

La cátedra informa por Kafka lo que pasa con cada proceso (pide el teléfono, lo rechaza, confirma, vence, cancela). Cronos lo consume del topic de acciones y actualiza el proceso. Hoy es **solo lectura**: todavía no manda el teléfono (issue #15, pasos 5 y 6).

- **Un grupo propio:** cronos consume con `<CATEDRA_KAFKA_CONSUMER_GROUP_ID>-cronos`, no con el de atlas, y lee desde el principio si el grupo no tiene offset (`earliest`): no se pierde ningún evento.
- **Un mensaje por vez, y el offset se confirma después** de aplicarlo: si cronos se cae antes, el mensaje se vuelve a leer y no repite ningún efecto (cada evento se reconoce por su `eventId`).
- **Si el mensaje no se entiende** (JSON roto, falta un campo, una versión de esquema distinta): se registra sin su contenido y se descarta; no traba la partición. Un tipo de evento que cronos no usa se ignora.
- **Si el proceso del evento no existe:** se reintenta 3 veces y se estaciona en la tabla `unmatched_event`.
- **Si la base no responde:** se reintenta **sin límite**, con espera creciente (hasta 30 s). No se descarta ni se saltea: el orden de los eventos de un proceso importa, y descartar podría perder una confirmación.
- **Si un mensaje falla siempre por sí mismo:** 3 intentos y se estaciona (`PROCESSING_FAILED`).
- **Cronos arranca aunque Kafka no responda** y sigue atendiendo pedidos; el consumidor reintenta conectarse cada 10 s. Para apagarlo: `CRONOS_KAFKA_CONSUMER_ENABLED=false`.
- **Qué mirar:** `grep 'evento=kafka\.'` en el log; cada línea de un mensaje lleva `kafka-<partición>-<offset>` en el lugar del `requestId`. Los eventos están en el ADR 0006.
- **Qué no se escribe nunca:** el nombre del topic ni del grupo (contienen el `groupId` de la cuenta), los servidores de la cátedra, el contenido de un mensaje ni el mensaje de una excepción de base de datos (puede traer la fila con los datos del paciente). Se fija además un `client.id` propio, porque el que arma el cliente lleva el nombre del grupo.

```bash
# Ver los eventos estacionados (procesos que no existen o mensajes que fallan siempre):
docker exec cronos-db psql -U cronos -d cronos -c 'SELECT event_type, reason, count(*) FROM unmatched_event GROUP BY 1, 2;'
```

## Logs

Cada línea que informa algo es un **evento**: un código estable y pares `clave=valor` (norma completa y catálogo de eventos en el [ADR 0006](../../biblioteca/biblioteca-alejandria-/alejandria-docs/arquitectura/adr/0006-norma-de-logs.md) de la biblioteca).

```
2026-11-22T12:03:47.905-03:00  INFO 379219 --- [cronos-turnos] [    Test worker] [86927b6b6ef0] a.e.u.c.a.reservation.StartReservation   : evento=reserva.hold.confirmado usuario=3 reserva=2 proceso=process-a92b47d0 estado=WAITING_FOR_PHONE vence=2030-01-01T10:01:00Z siguiente=espera_el_telefono
```

- **Seguir un pedido:** cada pedido lleva un `requestId` (el tercer corchete de la consola, vacío fuera de un pedido). Se devuelve en el encabezado `X-Request-Id`; si el cliente manda uno seguro (`[A-Za-z0-9._-]`, de 8 a 64 caracteres) se reutiliza. `grep 86927b6b6ef0` muestra todo lo que pasó en ese pedido.
- **Buscar un tipo de evento:** `grep 'evento=reserva.proceso.fallido'`, o por dominio: `grep 'evento=catedra\.'`.
- **Niveles:** `ERROR` = un bug o algo que exige intervenir (único con traza); `WARN` = una falla esperable que se manejó (la cátedra no responde); `INFO` = un hito de negocio y el cierre de cada pedido (`http.pedido.resuelto` con estado y `ms`); `DEBUG` = detalle técnico.
- **Qué nunca se escribe:** tokens, contraseñas, teléfono, nombres, el nombre de usuario, la dirección y el `groupId` de la cátedra. Solo ids. Lo vigilan `ArquitecturaTest` (nadie llama a `log.info/warn/error` directo) y `NormaDeLogsTest`.
- Para escribir un evento nuevo: `LogEvent.info(log, "dominio.accion", "usuario", id, "reserva", reservaId)`.

Estado actual: esqueleto, registro de usuarios (migración V1), disponibilidad de turnos (atlas + cátedra), inicio de reservas (hold y confirmación inicial), login con JWT RS256, validación de los tokens propios, `GET /api/account`, CORS y la prueba cruzada con atlas (`scripts/recorrido-cliente.sh`). Todavía no hay usuario administrador, el envío del teléfono por Kafka, consulta ni cancelación de reservas, ni recuperación al reiniciar: vienen en las próximas ramas. El consumo de los resultados por Kafka ya está (ver "Kafka").
