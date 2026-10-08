# Dependencias de la ampliación

| Componente | Motivo | Licencia y conectividad |
| --- | --- | --- |
| SQLCipher Android Community 4.18.0 | Room/SQLite estándar no cifran páginas; evita criptografía propia por campo y protege índices, reglas y preferencias | BSD-3-Clause; procesamiento local, sin envío financiero. Avisos incluidos en `app/src/main/assets/SQLCIPHER-LICENSE.txt` |
| AndroidX Biometric 1.1.0 | Diálogo de autenticación del sistema compatible con API antiguas | Apache-2.0; local |
| AndroidX Fragment 1.8.9 | Compatibilidad de Biometric/FragmentActivity con Activity Result; la versión transitiva 1.2 no era suficiente | Apache-2.0; local |
| AndroidX Paging 3.5.1 y Room Paging 2.8.4 | Lista paginada con invalidación consistente y carga fuera de UI | Apache-2.0; local |

No se introduce biblioteca de gráficos, navegación ni emojis: Canvas y Material 3 cubren la interacción solicitada. No se han actualizado AGP, Gradle, SDK, Kotlin ni Compose como parte incidental.

Fuentes: [SQLCipher Android](https://github.com/sqlcipher/sqlcipher-android/tree/v4.18.0), [Biometric](https://developer.android.com/jetpack/androidx/releases/biometric), [Fragment](https://developer.android.com/jetpack/androidx/releases/fragment#1.8.9), [Paging](https://developer.android.com/jetpack/androidx/releases/paging), [licencia AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt).

Antes de cada distribución, revisar el grafo de dependencias resuelto y los avisos de componentes nativos transitivos del binario distribuido. Esta selección no sustituye una revisión periódica de vulnerabilidades ni una auditoría de la app.

## Inventario y verificación (8 de octubre de 2026)

El [inventario resuelto de runtime](RUNTIME_DEPENDENCIES.md) incluye versiones efectivas y licencias. El APK/AAB incorpora los textos GPLv3 de Luma, Apache-2.0, los avisos BSD de SQLCipher y la opción de dominio público de LibTomCrypt, cuyo commit nativo se fija en el aviso. Esto no equivale a un análisis independiente de toda la cadena nativa.

`gradle/verification-metadata.xml` fija SHA-256 de los artefactos usados por compilación y tests. La primera generación parte de los artefactos ya resueltos: detecta sustituciones posteriores, pero no acredita retrospectivamente su procedencia. Revisar cualquier nuevo hash antes de incorporarlo. Wrapper y scripts se regeneraron con la misma versión 9.5.0; su JAR y la distribución coinciden con los checksums oficiales. No se actualizó el toolchain.

La instrumentación de Room solicitaba JSON Serialization 1.8.1 mientras AGP compartía core 1.7.3 con la app, provocando `AbstractMethodError`. Se fija JSON 1.7.3 exclusivamente en `androidTest` para mantener compatibilidad binaria; no cambia el runtime de producción.

CI usa acciones fijadas por commit, permisos de lectura y caché básica; no publica build scans, grafos de dependencias ni informes financieros. Las tareas de CI ejecutan pruebas sintéticas. El workflow debe comprobarse una vez exista el repositorio remoto.
