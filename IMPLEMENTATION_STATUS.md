# Seguimiento: exploración financiera y seguridad

Última revisión: 9 de octubre de 2026. Objetivo: código público auditable y futura publicación en Google Play.

## Implementación presente

- [x] Room v4, SQLCipher y migraciones explícitas 1→2→3→4, sin borrado destructivo.
- [x] Sobre Keystore y autenticación del sistema; hardware preferente pero opcional por compatibilidad.
- [x] Conversión conservadora de SQLite heredado y recuperación en slot separado con nueva clave.
- [x] Promoción reconciliada con el puntero: cancelar no destruye un slot activo o incierto; retirada posterior al commit.
- [x] Bloqueo de sesión, limpieza de ViewModels, protección de ventana, exclusión de backups y borrado local confirmado.
- [x] Copias cifradas manuales; esquema validado antes del contenido, tipos estrictos, límites y cancelación nativa.
- [x] Verificación y restauración con confirmación; propietario de sesión que conserva operaciones al rotar y limpia al bloquear.
- [x] Mensualidades por regla/mes, omisiones preservadas y guardado/generación atómicos con reintentos estables.
- [x] Conversión a ingreso sin vínculo recurrente; migración/restore de vínculos antiguos sin alterar importes.
- [x] Importes exactos en cálculo, parsing y visualización; escalas comunes y fechas desde 1900.
- [x] Calendario sin registro de fechas ni savers internos en SavedState.
- [x] Filtros compartidos y guardados cifrados, páginas con saldo histórico y resúmenes sobre todos los resultados.
- [x] Repositorio del explorador sin entidades Room/JSON en UI y manejo recuperable de páginas inválidas.
- [x] Consulta de saldo con ventana SQLite, antes de aplicar los filtros; detección de overflow.
- [x] Comparaciones exactas sin mezclar monedas, curvas con ejes/gestos y tablas alternativas con acciones por intervalo.
- [x] Regresiones añadidas para finanzas, reintentos, promoción, migraciones, copias malformadas, UI paginada y propietario de sesión.
- [x] GPLv3, documentación, exclusiones de archivos privados, scanner local/CI, inventario y avisos de terceros.
- [x] Wrapper 9.5.0 coherente y verificación SHA-256 de dependencias, sin actualizar el toolchain.

## Verificación reproducible

Usar un JDK completo. El daemon está fijado a Java 25; la compatibilidad del código Java sigue siendo 11.

```bash
./gradlew test lint assembleDebug assembleDebugAndroidTest bundleRelease
python3 tools/check_public_files.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

La ejecución completa desde cero con `--rerun-tasks` ha pasado con **65 tests unitarios** sin fallos, además de lint, APK de debug, APK de tests y AAB release optimizado sin firma de producción. El bundle incorpora GPLv3 y avisos Apache-2.0, SQLCipher/LibTomCrypt. Los esquemas v1–v3 se conservaron byte por byte y se añadió v4. La comprobación estricta posterior también pasó: **0 errores de lint y 20 avisos de versiones disponibles**. Más detalles en [AUDIT_FOLLOWUP.md](AUDIT_FOLLOWUP.md).

El scanner y sus tres tests con datos ficticios pasan. Se comprobó que los archivos privados ignorados no aparezcan como candidatos y que añadirlos forzadamente al índice sea detectado. No hay historial Git válido en este entorno que auditar.

### Instrumentación real: ejecución parcial y bloqueo actual

En el intento inicialmente autorizado, sobre `dev.okami.luma.validation`, se completaron 19 de 32 tests: 14 pasaron y cinco fallaron, antes de que la suite quedara esperando a la interfaz. Los fallos revelaron una incompatibilidad JSON/core en Room Testing y una señal de cancelación nula rechazada por SQLite de plataforma. Ambos se corrigieron; también se añadieron dos pruebas del gestor real de sesión con operaciones de apertura/clave ficticias.

Las **34 pruebas instrumentadas actuales compilan**, pero los dos intentos autorizados de reinstalación posteriores fueron rechazados por Android con `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`, además de errores al retirar el paquete de validación. No se ejecutó la suite corregida completa. No se modificaron restricciones del teléfono ni se usó el identificador que contiene datos reales. Tras el último rechazo no se reintentó.

La consulta SQLCipher real sobre 100.000 filas ficticias pasó en la ejecución parcial, incluidas semántica del saldo con filas ocultas y detección de overflow. Una página de 40 resultados tardó **3.169 ms**; el plan no incluye la subconsulta correlacionada anterior. La consulta aún recorre el histórico por carga: falta medir otros tamaños/dispositivos y optimizar si el uso real lo exige.

Para instrumentación en un teléfono personal:

```bash
./gradlew -Pluma.validation=true connectedDebugAndroidTest
```

El parámetro utiliza `dev.okami.luma.validation`. La instalación/desinstalación del runner afecta solo a ese sandbox. Ante un rechazo, se necesita autorización nueva y aceptación de la instalación en el teléfono antes de reintentar. Volver a compilar sin el parámetro genera la app habitual.

Las verificaciones previas de alineación nativa muestran segmentos ELF `0x4000` en las ocho bibliotecas del AAB y `zipalign -c -P 16 4` correcto en APK debug. Las versiones nativas no cambiaron. Esto no sustituye probar un dispositivo con páginas de 16 KiB ni el APK final derivado del AAB firmado.

## Aceptación pendiente antes de Play

- [ ] Ejecutar la suite instrumentada corregida completa en un dispositivo/emulador autorizado.
- [ ] Prueba interactiva completa de autenticación, recuperación, bloqueo, rotación y copias con datos ficticios.
- [ ] Matriz Android 8/API 30+, TEE/StrongBox, respaldo por software y páginas de 16 KiB.
- [ ] Interrupciones reales de migración/importación/promoción, disco lleno y recuperación entre dispositivos.
- [ ] TalkBack, foco, pantallas pequeñas y texto ampliado sobre los flujos actuales.
- [ ] Medición de paginado/agregados sobre históricos grandes en hardware modesto.
- [ ] Revisión independiente de seguridad y componentes nativos, y validación funcional con R8 en release.
- [ ] Identidad/contacto/URL de privacidad pública y acceso desde la app; canal privado de seguridad.
- [ ] Firma de producción fuera del repositorio, Data Safety, declaración financiera y requisitos de la cuenta de Play.
- [ ] Comprobar el workflow remoto y auditar el índice real y elegir/revisar nombre y correo de autor antes del primer commit público.

La identidad y los contactos se mantienen pendientes por decisión expresa del titular. No interpretar la compilación ni las pruebas parciales como garantías ya verificadas. Ver [seguridad](SECURITY.md), [privacidad](PRIVACY.md) y [correcciones](AUDIT_FOLLOWUP.md).
