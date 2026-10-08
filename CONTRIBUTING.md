# Contribuir a Luma

Prioriza privacidad, exactitud financiera y cambios pequeños. Consulta [AGENTS.md](AGENTS.md) para las convenciones del proyecto y [README.md](README.md) para compilarlo.

## Cambios

- Describe el comportamiento, el motivo y cómo se ha comprobado.
- Usa solo datos ficticios. No incluyas credenciales, bases, copias, volcados, rutas del equipo ni identificadores del dispositivo.
- Representa importes y cálculos con enteros exactos/decimales exactos; añade regresiones para lógica financiera y errores corregidos.
- Conserva los esquemas Room anteriores y añade migraciones explícitas.
- Documenta propósito, entradas y efectos de funciones modificadas. Evita refactorizaciones ajenas al cambio.
- Justifica dependencias y cambios de versiones por separado. No añadas red, telemetría ni servicios externos al producto sin una decisión explícita de privacidad.

Antes del primer commit, revisa el nombre y el correo que Git usará como autor: los commits públicos incluyen esos metadatos. Usa una identidad y un correo destinados al proyecto si quieres mantener privados los personales. El scanner de archivos no sustituye esa revisión.

Antes de proponer un cambio:

```bash
python3 tools/check_public_files.py
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew test lint assembleDebug assembleDebugAndroidTest
```

Si afecta a consultas, migraciones, cifrado o UI, ejecuta además instrumentación sobre un emulador o el identificador `dev.okami.luma.validation`, con autorización del dispositivo. Indica exactamente qué pruebas no se pudieron ejecutar.

## Seguridad

No publiques detalles explotables ni datos reales en un issue. El titular no ha definido todavía contacto privado; cuando se cree el repositorio, debe habilitar **Private vulnerability reporting** en su pestaña Security antes de distribuir binarios públicamente. No se afirma que ese canal ya exista.

## Licencia

Las contribuciones de código y recursos propios se ofrecen bajo GPL-3.0-only, salvo un componente identificado expresamente con otra licencia compatible. Conserva avisos y atribuciones de terceros. No incluyas material sin permiso de distribución.
