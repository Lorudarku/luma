# Seguridad de Luma

Esta implementación no es una certificación, una auditoría independiente ni una garantía de invulnerabilidad. No debe publicarse como «blindada ante cualquier extracción». Revisión técnica: 9 de octubre de 2026.

## Modelo de amenaza

Se protege el almacenamiento frente a la copia de archivos de una app cerrada, backups automáticos, apertura sin autenticación, capturas habituales y alteraciones de páginas cifradas. No se puede garantizar confidencialidad ante un SO/TEE comprometido, instrumentación del proceso desbloqueado, un teclado o servicio de accesibilidad malicioso, cámaras externas, una credencial conocida por el atacante ni coerción física. Tamaño y existencia de los archivos no se ocultan.

## Diseño implementado

- Room v4 usa SQLCipher Community 4.18.0. Se mantienen las migraciones 1→2→3→4; no hay `fallbackToDestructiveMigration`.
- Una clave aleatoria de 32 bytes se protege con una KEK AES-256-GCM no exportable de Android Keystore. El sobre autentica también su contexto mediante AAD. El nonce lo genera el proveedor; no hay claves en el repositorio.
- Se exige bloqueo de pantalla, pero desde el 6 de septiembre de 2026 el hardware seguro es opcional por decisión de compatibilidad. StrongBox es preferente; si no está disponible se utiliza Android Keystore con el respaldo que proporcione el dispositivo (TEE o software). No se reemplazan claves existentes ni se almacena la base sin cifrar. El respaldo por software ofrece menos aislamiento frente a un sistema comprometido: no es una garantía equivalente a TEE/StrongBox. Android 8–10 usa confirmación de credencial; API 30+ admite biometría fuerte o credencial. Se mantiene `minSdk=26`; no se promete compatibilidad con cualquier versión o fabricante.
- La KEK requiere autenticación reciente, con ventana de 15 segundos. La UI exige un nuevo diálogo para cada apertura de sesión. Esta ventana de Keystore no constituye protección ante un proceso ya comprometido.
- Al pasar a segundo plano se invalidan aperturas tardías, se desmonta la UI sensible, se limpian los ViewModels de sesión y se programa el cierre de Room. La rotación conserva la sesión. Las operaciones de disco ya iniciadas se completan/cierra su resultado antes de destruir su scope.
- `FLAG_SECURE` y, desde API 31, ocultación de overlays. Los formularios financieros y el calendario, incluidos los savers internos de Material, no se registran en SavedState. No se añaden logs de contenido financiero, red, analítica ni informes remotos. Se desactivan el logger Java y el log de SQLCipher; esto no certifica que ninguna dependencia nativa o el sistema emita diagnósticos. Los strings de la JVM y copias internas del framework no ofrecen borrado verificable de RAM; limpiar buffers es una reducción de exposición, no una garantía forense.
- El almacenamiento temporal de SQLite se fuerza a RAM. Los journals contienen páginas cifradas; no se exporta SQLite en claro. Backups y transferencias automáticos están deshabilitados y excluidos explícitamente.

SQLCipher cifra páginas con AES-256-CBC, autentica con HMAC-SHA512 y deriva claves de contraseñas mediante PBKDF2-HMAC-SHA512 (configuración predeterminada v4). Esto es distinto del AES-GCM que protege el sobre Keystore. No se ha diseñado un algoritmo criptográfico propio. [Diseño SQLCipher](https://www.zetetic.net/sqlcipher/design/), [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## Migración y recuperación

La instalación heredada se abre únicamente para migrarla. Se copia a un archivo cifrado, se comparan huellas por tabla, se verifica integridad, se cierran conexiones y se sincroniza/promociona el destino. El original se elimina solo después de abrir y validar la base cifrada. Los callbacks de corrupción no borran automáticamente ninguno de los dos archivos. Una interrupción antes de la promoción conserva el original. El borrado de datos antiguos en memoria flash no garantiza eliminación física de bloques históricos: el cifrado nuevo no protege retrospectivamente copias que ya se extrajeron.

Las copias manuales `.lumabackup` también son SQLCipher. Requieren contraseña independiente de al menos 20 caracteres; se recomienda una frase aleatoria larga. Solo bytes cifrados salen al proveedor elegido en el selector de Android. Ese proveedor puede sincronizarlos fuera del teléfono; el usuario decide el destino.

La importación abre el origen en solo lectura y comprueba primero el esquema para rechazar tablas desconocidas, vistas, triggers y columnas generadas antes de leer filas. Rechaza tipos coercionables, referencias inválidas, enteros truncables y valores financieros no válidos; los números JSON deben ser enteros originales. Las consultas reciben una señal de cancelación nativa y el recorrido comprueba cancelación entre filas. Límites defensivos: 256 MiB, un millón de movimientos y un millón de marcas de mensualidades, 10.000 categorías/reglas, 1.000 filtros y fechas financieras de 1900 hasta hoy. Las contraseñas se acotan a 1.024 caracteres. La exportación aplica los mismos límites antes de escribir al proveedor y usa una instantánea transaccional. No ejecuta el esquema de la copia sobre el destino. Se admiten copias v3 y v4; las v3 se adaptan con la migración local. Se verifica antes y después de la confirmación, y se copia a una base nueva con clave local nueva. El puntero de almacenamiento cambia atómicamente antes de retirar la base anterior. Se relee para reconciliar fallos posteriores al commit, incluido fsync. La retirada es limpieza posterior: un error no autoriza destruir el nuevo slot. Si no se puede leer el puntero, se conservan ambos slots. Cancelar comprueba que el staging no sea el almacenamiento activo antes de eliminarlo. Una contraseña incorrecta no autoriza empezar desde cero.

Sin la clave del dispositivo, solo una copia cifrada independiente permite recuperar la información. Luma no puede recuperar su contraseña. «Borrar todos los datos» elimina las claves y artefactos privados de Luma, pero no las copias externas elegidas por el usuario.

Las copias y su contraseña temporal pertenecen a un ViewModel de sesión: la rotación conserva el progreso, el bloqueo cancela y limpia, y la restauración requiere confirmación explícita tras verificar. Se bloquean envíos repetidos. Las mensualidades procesadas se registran por regla/mes, incluso si se elimina el movimiento, para evitar su regeneración; consultar la retención en [PRIVACY.md](PRIVACY.md).

## Verificación y publicación

Consultar [seguimiento de implementación](IMPLEMENTATION_STATUS.md) y [correcciones de la auditoría](AUDIT_FOLLOWUP.md). Compilar o pasar tests unitarios no verifica TEE, biometría, fallos de alimentación ni resistencia a instrumentación. Antes de publicación:

1. Probar migración en una instalación desechable con datos ficticios y fallos de espacio/cierre del proceso.
2. Probar dispositivos TEE/StrongBox y ausencia de hardware, Android 8 y 11+, cancelación de autenticación, bloqueo, rotación y pantalla apagada.
3. Probar backups con contraseña incorrecta, páginas alteradas, contenido malformado y recuperación en otro dispositivo.
4. Revisar RAM, Logcat, backups, overlays y capturas sobre el artefacto release. El APK debug no es un artefacto de distribución segura.
5. Revisar dependencias y licencias, firma release fuera del repositorio, alineación nativa de 16 KiB y comportamiento en ese entorno.
6. Realizar una evaluación independiente basada en [OWASP MASVS](https://mas.owasp.org/MASVS/) y registrar hallazgos y correcciones. No se afirma cumplimiento MASVS por incorporar esta lista.
7. Completar política pública de privacidad, datos de contacto, Data Safety y declaración de funciones financieras de Play Console con el comportamiento real. No hay aprobación automática ni asesoramiento legal implícito. [Política de datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311), [páginas de 16 KiB](https://developer.android.com/guide/practices/page-sizes).

No publicar una vulnerabilidad junto con datos reales del usuario. Por decisión del titular, la identidad y el contacto siguen pendientes. Antes de distribuir binarios, establecer un canal privado de reporte; si se publica en GitHub, habilitar Private vulnerability reporting en los ajustes del repositorio. No se afirma que ese ajuste esté ya activo.
