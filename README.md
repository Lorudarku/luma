# Luma

Aplicación Android de finanzas personales: ingresos, gastos, categorías, mensualidades, filtros y gráficos. Funciona sin cuenta ni backend; los datos se almacenan localmente con Room y SQLCipher. No incluye publicidad, analítica ni permiso de Internet.

**Estado: en desarrollo.** El destino previsto es Google Play. La publicación del código no significa que la aplicación esté validada para distribuirla como versión estable. Consulta [las correcciones de la auditoría](AUDIT_FOLLOWUP.md) y [las verificaciones pendientes](IMPLEMENTATION_STATUS.md).

## Funciones

- Registro, edición y eliminación confirmada de movimientos, con importes exactos por moneda.
- Reglas mensuales que preservan el histórico y las cuotas omitidas.
- Listados paginados, saldo histórico, filtros guardados y resúmenes completos de la selección.
- Gráficas con tablas alternativas y detalle de los movimientos.
- Autenticación del sistema, bloqueo al salir y copias cifradas manuales con contraseña independiente.

No hay conversión entre monedas, sincronización, IA ni reconocimiento de voz implementados.

## Compilar

Requiere Android SDK Platform 37.0, Build Tools 36.0.0 y un JDK completo. El daemon Gradle utiliza Java 25 mediante `gradle/gradle-daemon-jvm.properties`; la compatibilidad del código Java sigue siendo 11. El wrapper fija Gradle 9.5.0. Android mínimo: API 26, con bloqueo de pantalla configurado.

Configura el SDK mediante Android Studio, `ANDROID_HOME` o un `local.properties` privado. El build descarga herramientas y dependencias desde sus repositorios; la aplicación funciona offline.

```bash
./gradlew test lint assembleDebug assembleDebugAndroidTest bundleRelease
python3 tools/check_public_files.py
python3 -m unittest discover -s tools -p 'test_*.py'
```

Los artefactos se generan en `app/build/outputs`. El bundle release no está firmado para producción. La firma de subida se gestiona fuera del repositorio.

En un teléfono personal, la instrumentación debe usar un identificador separado:

```bash
./gradlew -Pluma.validation=true connectedDebugAndroidTest
```

Esto utiliza `dev.okami.luma.validation`. Los tests trabajan con datos ficticios; no ejecutes el runner normal sobre una instalación que contenga tus finanzas. Si Android rechaza la instalación, requiere autorización en el teléfono; no deben cambiarse sus restricciones automáticamente.

CI verifica candidatos públicos, wrapper, tests, lint, builds y estabilidad del esquema Room. Las acciones están fijadas por SHA y tienen permisos de lectura. La instrumentación requiere un dispositivo autorizado y no se presenta como ejecutada por ese workflow.

## Arquitectura

Un módulo `:app`, Kotlin y Compose; composición manual de dependencias por sesión. `domain` contiene dinero, validación y cálculo financiero; `data` contiene Room, migraciones y repositorios; `ui` contiene ViewModels y componentes; `security` controla claves, sesiones y copias. El explorador obtiene modelos validados mediante un repositorio, sin entidades Room en la UI.

Room v4 añade un registro de meses recurrentes ya tratados. Eliminar o convertir una cuota conserva ese registro; cambiar el calendario solo genera meses aún pendientes. La migración no modifica importes ni elimina movimientos históricos, incluidos duplicados que pudieran haberse creado con versiones anteriores. Las copias v3 se adaptan al restaurarlas. No se pueden reconstruir omisiones ya borradas en v3, que no guardaba esa información.

## Privacidad y recuperación

Consulta [PRIVACY.md](PRIVACY.md) y [SECURITY.md](SECURITY.md). Sin la clave local, la recuperación requiere una copia externa y su contraseña; Luma no puede recuperar una contraseña perdida. Las copias automáticas de Android están excluidas. Un proveedor de documentos elegido por ti puede almacenar el archivo cifrado en la nube.

La identidad/contacto del titular y la URL pública de privacidad quedan pendientes por decisión del titular. Deben completarse antes de publicar en Play.

## Contribuir y licencia

Lee [CONTRIBUTING.md](CONTRIBUTING.md) y [AGENTS.md](AGENTS.md). No adjuntes finanzas reales, documentos privados, claves ni identificadores del teléfono a issues, capturas o tests.

Luma y sus recursos propios se distribuyen bajo **GPL-3.0-only**: [LICENSE](LICENSE). Las dependencias y herramientas conservan sus licencias originales; consulta [DEPENDENCIES.md](DEPENDENCIES.md) y los avisos incluidos en los assets.

Al distribuir un binario GPLv3 debe facilitarse el código fuente correspondiente de esa versión. No publiques una compilación sin conservar esa correspondencia.
