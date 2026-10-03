# cronos-turnos

Servicio backend (Java + Spring Boot) responsable de construir la disponibilidad de turnos, gestionar holds temporales y conducir el proceso de confirmación de reservas (REST + Kafka) contra el servicio central de la cátedra. Gestiona el registro y autenticación de usuarios finales con JWT propio y mantiene la propiedad de cada reserva por usuario final. Consulta el catálogo vigente a atlas-catalogo mediante contrato propio; no mantiene su propia copia del catálogo.

Documentación, contratos y plantillas: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria-/tree/main/alejandria-docs).
