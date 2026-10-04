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
