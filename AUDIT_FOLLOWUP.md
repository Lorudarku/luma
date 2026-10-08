# Correcciones y segunda revisión de Luma

Fecha: 9 de octubre de 2026. Continúa la [auditoría inicial](AUDIT.md), cuyo código y números de línea corresponden al estado anterior. El objetivo sigue siendo publicar primero el código y, después de validar el producto, distribuirlo en Google Play.

## Cambios aplicados

| Hallazgo | Corrección | Evidencia y alcance |
| --- | --- | --- |
| S01: destrucción del slot activo al cancelar | Promoción reconciliada con el puntero, retirada posterior al commit y cancelación que conserva un slot activo o incierto. Se contempla el respaldo AtomicFile. | Regresiones de promoción antes/después de activar, fallo de retirada e incertidumbre. Falta inyección real de fsync/Keystore y cierre del proceso. |
| F01/F02: cuotas eliminadas o duplicadas al cambiar día | Room v4 registra meses ya tratados por regla; el borrado y la edición preservan esa marca. | Regresiones Room de cuota omitida, cambio de día/importe, conservación de histórico y copia cifrada. |
| F03: guardado parcial y reintentos | Regla y generación en una transacción; ID del borrador estable y protección síncrona contra doble envío. | Tests de ViewModel con fallo/reintento y doble clic; rollback Room por conflicto de cuota. |
| F04: redondeo visual | Formateo exacto con la escala de cada moneda y prohibición de redondeo implícito. | JPY/EUR/BHD, locales distintos y extremos de Long. |
| F05: ingreso aún vinculado | Convertir a ingreso desvincula metadatos conservando el mes procesado; v3→v4 repara vínculos antiguos sin cambiar importes. | Regresiones de edición, migración y restauración v3. |
| S02: SQL externo antes de validar esquema | Inspección de esquema antes de contar; rechazo de vistas/triggers/columnas generadas. Recuentos acotados y cancelación nativa. | Copia con vista recursiva; rechazo antes del timeout y destino conservado. |
| S03: coerción/truncamiento | Tipos SQLite y enteros originales JSON, comparación en Long antes de convertir días/booleanos. | Valores 4294967297, decimales, strings, exponentes y límites de Long. |
| S04: monedas inconsistentes | Una única política de escalas 0..9 para dominio, edición, visualización y copias; rechazo de XXX. | Regresiones de escala y copias con moneda no admitida. |
| S05: fechas en SavedState | Estado de calendario en memoria y registro saveable deshabilitado en todo su subárbol. | Prueba Compose sobre el calendario real y el registro del padre. |
| P01: SUM correlacionado por fila | Una ventana SQLite calcula saldos históricos antes de aplicar filtros. | SQLCipher real con 100.000 filas, filas ocultas, plan sin subconsulta correlacionada y overflow. |
| Q01: cobertura y propietarios | Nuevos tests de explorador, reglas, promoción y sesión; copias coordinadas por ViewModel de sesión. | Pruebas de verificación/confirmación/limpieza, cierre y apertura tardía; prueba de la ruta paginada utilizada por la app. La matriz de hardware continúa pendiente. |

El explorador ahora recibe modelos de dominio a través de un repositorio; Room y JSON quedan en datos. Las páginas con entidades inválidas devuelven un error recuperable. No se añadieron módulos, inyección automática, backend, permisos de red ni servicios de análisis.

También se unificaron las fechas desde 1900, se traducen errores de observación de categorías y la exportación aplica los límites de la importación antes de enviar bytes. Se corrigieron plurales y variantes del icono señalados por lint. Las gráficas conservan tablas alternativas con botones para abrir intervalos sin depender de tocar las barras; falta validar TalkBack y tamaños de texto en dispositivo.

### Límites de la migración

La migración preserva importes y movimientos existentes: no elimina posibles duplicados históricos de versiones anteriores. Tampoco puede reconstruir una omisión realizada y ya borrada en v3, porque esa versión no guardaba la marca. Desde v4 se conserva explícitamente la mensualidad procesada. La política de retención de estas marcas está en [PRIVACY.md](PRIVACY.md).

### Fallos descubiertos al ejecutar en teléfono

La primera instrumentación alcanzó 19 pruebas, con cinco fallos, antes de quedar esperando a la UI. Cuatro fueron una incompatibilidad binaria del JSON de Room Testing con el core compartido por AGP; se fija JSON 1.7.3 exclusivamente en tests. El quinto descubrió una llamada con `CancellationSignal=null` que SQLite de plataforma rechaza; se elige ahora la sobrecarga sin señal cuando no se solicita cancelación. Esta corrección afecta a la conversión de datos heredados. No se han ocultado ni eliminado pruebas para obtener un resultado verde.

Los dos intentos aislados autorizados después de corregir los fallos fueron rechazados por Android con `INSTALL_FAILED_USER_RESTRICTED`. No interpretar la compilación de los tests ni la ejecución parcial como una suite instrumentada aprobada. Los resultados finales y el bloqueo, si persiste, se registran en [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## Verificación final

| Comprobación | Resultado |
| --- | --- |
| Tests unitarios | 65, sin fallos ni errores; ejecutados desde cero |
| Lint | 0 errores; 20 advertencias de versiones disponibles, sin silenciarlas ni actualizar dependencias incidentalmente |
| Build estricto | `test lint assembleDebug assembleDebugAndroidTest bundleRelease` correcto con verificación SHA-256 activa |
| Suite instrumentada corregida | 34 tests compilados; ejecución completa bloqueada por rechazo de instalación del teléfono |
| Scanner público | 172 candidatos, 0 hallazgos; 3 tests del scanner correctos |
| Esquemas y recursos | v1–v3 intactos, v4 exportado; iconos duplicados archivados sin cambiar sus bytes |
| Manifiesto release | Sin Internet/micrófono/almacenamiento general; backup automático deshabilitado |
| AAB release | Optimizado, sin firma de producción; cinco archivos de licencias/avisos incluidos |

Los tres metadatos de herramientas ausentes de la primera generación automática de hashes se contrastaron con Maven Central por HTTPS antes de incorporarlos. El build final pasó sin modo de generación de hashes ni excepciones de verificación.

## Privacidad del material público

- Revisados los candidatos que seleccionan las reglas Git, incluyendo archivos nuevos, código, tests, esquemas, scripts, documentación y recursos. Se revisaron patrones de claves privadas/tokens/credenciales, rutas de usuario, extensiones de firma/bases/copias, enlaces simbólicos y metadatos de recursos.
- El identificador de un teléfono encontrado en `.idea/deploymentTargetSelector.xml` queda excluido junto con `.idea/`. Se excluyen `.kotlin/`, herramientas locales, claves, copias, bases y volcados. No se borró la configuración privada del equipo.
- No se encontraron credenciales ni información financiera real en los candidatos públicos. Un patrón semejante a un correo dentro de bytes comprimidos PNG se comprobó como coincidencia binaria; el recurso muestra únicamente el icono. Seis PNG conservan fechas técnicas de creación/modificación, sin nombre, correo, geolocalización ni rutas personales en sus metadatos revisados.
- `tools/check_public_files.py` comprueba también archivos privados forzados al índice y oculta los valores en su salida. Sus tests usan repositorios temporales y secretos ficticios. No es un detector universal: una búsqueda por patrones no demuestra ausencia absoluta de secretos.
- Hay nombre y correo de autor configurados globalmente en Git, fuera del repositorio. Sus valores no se imprimieron ni se cambiaron. El primer commit podría publicarlos aunque el scanner de archivos esté limpio: elegir/revisar la identidad de autor y un correo destinado al proyecto antes de crear el commit. No se presupone que la identidad actual sea la elegida para publicación.
- `.git` sigue vacío y no constituye un repositorio válido: no hay historial ni selección final de commit que se pueda auditar aquí. El scanner usa un índice temporal sin inicializar el repositorio del usuario. Al crear el repositorio, ejecutarlo sobre el índice real antes de hacer el commit.
- Los informes locales de instrumentación contienen identificadores del dispositivo y diagnósticos de Android; permanecen dentro de `app/build/`, ignorado. No adjuntar esos informes completos a un issue público sin revisar y redactar.

## Preparación pública

Se añadieron GPL-3.0-only, README, guía de contribución, inventario de runtime y avisos de terceros dentro del APK/AAB. LibTomCrypt se referencia al commit nativo de SQLCipher Android 4.18.0 y usa su opción de dominio público. El wrapper se regeneró con la misma versión 9.5.0; los checksums de distribución y JAR corresponden a los oficiales. La verificación SHA-256 de dependencias detecta cambios posteriores; sus hashes iniciales proceden de la resolución existente y no prueban retrospectivamente su origen.

CI fija acciones por commit, usa permisos de lectura y caché básica, y ejecuta scanner, tests de scanner, tests de app, lint, compilaciones y comprobación del esquema. No se ha ejecutado en GitHub porque todavía no hay repositorio remoto. Consultar [DEPENDENCIES.md](DEPENDENCIES.md) y [RUNTIME_DEPENDENCIES.md](RUNTIME_DEPENDENCIES.md).

Por decisión del titular no se añaden identidad ni contacto inventados. El canal privado de reporte, la política de privacidad pública con URL estable y el acceso a esa política desde la app se completarán antes de distribuir binarios. La distribución GPL requiere facilitar el código fuente correspondiente a la versión distribuida.

## Estado para GitHub y Google Play

El árbol está preparado para un primer commit público **como proyecto en desarrollo**, sujeto a revisar la selección real con el scanner y la identidad del autor del commit. No se creó un commit, no se modificó historial ni se publicaron archivos.

Google Play sigue pendiente de pruebas de autenticación/hardware, interrupciones reales y espacio insuficiente, recuperación entre dispositivos, ejecución con páginas de 16 KiB, accesibilidad, revisión del release optimizado y firma de producción. También quedan identidad/contacto/URL, Data Safety, declaración financiera y requisitos de pruebas de la cuenta que correspondan. Los requisitos oficiales revisados se enlazan en [AUDIT.md](AUDIT.md); deben verificarse de nuevo al publicar.

La ventana elimina el recorrido repetido de saldo por cada fila, pero todavía recorre el histórico por carga: en el teléfono de validación, una página de 40 filas sobre 100.000 movimientos ficticios tardó **3.169 ms** en una ejecución. No es un benchmark de múltiples dispositivos ni una promesa de latencia. El trabajo se realiza fuera del hilo de UI; una optimización adicional debe medirse y preservar exactitud, filtros y overflow.
