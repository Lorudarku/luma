# Auditoría técnica de Luma

Fecha: 8 de octubre de 2026.

> Informe del estado previo a las correcciones. El estado actual, las pruebas y los pendientes están en [AUDIT_FOLLOWUP.md](AUDIT_FOLLOWUP.md); las rutas y líneas de este informe corresponden al código auditado originalmente.

Objetivo del producto: aplicación Android de finanzas personales, local y privada, con futura publicación en Google Play. Ese destino debe orientar las decisiones posteriores sobre recuperación, pruebas, accesibilidad, firma y privacidad.

## Dictamen

La base es suficientemente sólida para abrir un repositorio público **como proyecto en desarrollo**, después de preparar su licencia, documentación de entrada y exclusiones de archivos personales. No está preparada para distribuirse como aplicación estable en Google Play: hay fallos financieros y riesgos de recuperación que deben corregirse y verificarse antes.

La arquitectura actual puede evolucionar hacia ese objetivo sin una reescritura ni una división anticipada en módulos. La prioridad es cerrar los problemas de comportamiento y demostrar la seguridad en dispositivos.

Esta revisión combina lectura de código, análisis estructural local, compilaciones, tests unitarios y reproducciones pequeñas con datos ficticios. No constituye una certificación MASVS, un pentest del dispositivo ni una revisión exhaustiva de vulnerabilidades de todas las dependencias nativas.

## Alcance y evidencia

- Inventario: 64 archivos Kotlin de producción, 7.371 líneas; 17 archivos de tests unitarios y 6 de instrumentación.
- Revisados los límites entre dominio, repositorios, Room, ViewModels y Compose; autenticación, claves, sesiones, migración, copias, restauración, reglas recurrentes, filtros, consultas y configuración de distribución.
- Graphify actualizado en modo local `code-only` y usado para localizar relaciones; las conclusiones se contrastaron con el código.
- `./gradlew test lint assembleDebug assembleDebugAndroidTest bundleRelease`: correcto.
- `./gradlew :app:testDebugUnitTest --rerun-tasks`: correcto, ejecución nueva de los 51 tests, sin fallos ni errores.
- Lint: 0 errores y 24 advertencias; 20 sobre versiones disponibles, 2 sobre plurales y 2 sobre recursos del icono. Una versión más reciente no demuestra por sí misma una vulnerabilidad.
- Revisado el árbol `releaseRuntimeClasspath`: las versiones efectivas difieren de algunas declaraciones; por ejemplo, Lifecycle resuelve a 2.9.4 y Kotlin stdlib a 2.2.20. Evaluar las versiones resueltas, no solo el catálogo.
- El manifiesto combinado de release no declara Internet, micrófono ni acceso general a almacenamiento. Los componentes de Room y Startup no se exportan. El receptor transitivo de ProfileInstaller está protegido por `android.permission.DUMP`.
- Las ocho bibliotecas `.so` del AAB, para cuatro ABI, tienen segmentos `PT_LOAD` alineados a `0x4000`. `zipalign -c -P 16 4` pasa para el APK debug. Falta ejecución real en Android con páginas de 16 KiB y validación del APK que se derive del AAB firmado.
- El AAB generado no contiene firma de producción. Compilarlo no significa que esté listo para subirlo a Play.
- Búsqueda por patrones de secretos y extensiones de claves/copias en los archivos candidatos: no se encontraron credenciales habituales, almacenes de firma ni bases/copias financieras para publicar. Es una comprobación limitada, no una garantía de ausencia.
- ADB muestra únicamente un teléfono sin autorización. No se ejecutó instrumentación ni se reintentó la instalación rechazada descrita en `IMPLEMENTATION_STATUS.md`.
- `git status` no funciona: la carpeta `.git` visible está vacía. No se pudo auditar historial, archivos ya rastreados ni el contenido de un futuro commit.

## Decisiones acertadas

1. **Privacidad por diseño.** El producto funciona sin cuenta ni backend. No aparecen SDK de publicidad, analítica o red. Las copias usan el selector de Android y advierten sobre proveedores en la nube.
2. **Dinero exacto.** Unidades mínimas en `Long`, moneda separada, parsing explícito y aritmética comprobada. Los valores flotantes de gráficas se usan para coordenadas, no para persistir importes.
3. **Cifrado estándar.** SQLCipher para la base; clave aleatoria protegida con AES-GCM de Keystore, AAD y autenticación reciente. No se observan claves constantes ni criptografía propia.
4. **Conservación de datos.** Migraciones explícitas, ausencia de migración destructiva automática y rechazo de recrear la clave de una base existente. Los callbacks de corrupción conservan los archivos.
5. **Sesiones acotadas.** Bloqueo en `onStop`, invalidación de aperturas tardías, store de ViewModels por sesión, cierre de Room y protección de capturas/overlays.
6. **Consultas seguras frente a inyección habitual.** Texto y selecciones se parametrizan; columnas y órdenes proceden de enums. La restauración copia valores al esquema conocido, no importa instrucciones DDL del archivo.
7. **Documentación honesta.** `SECURITY.md`, `PRIVACY.md` e `IMPLEMENTATION_STATUS.md` explican límites, pérdida de claves y validaciones pendientes.

El respaldo de Keystore por software es un compromiso explícito de compatibilidad. Conserva autenticación y cifrado, pero ofrece menos aislamiento si el sistema está comprometido. No es por sí mismo un fallo nuevo de esta auditoría.

## Hallazgos prioritarios

Las prioridades describen el impacto en el producto. «Alta» debe resolverse antes de una distribución estable; «Media» debe resolverse o justificarse con evidencia antes de publicar en Play. Publicar código en desarrollo no equivale a distribuir un binario estable.

### S01 — Alta: cancelar una recuperación tras un fallo posterior a la activación puede destruir la base activa

Fuente: [VaultSessionManager.kt](app/src/main/java/dev/okami/luma/security/VaultSessionManager.kt#L111), líneas 111–155; [DatabaseKeyManager.kt](app/src/main/java/dev/okami/luma/security/DatabaseKeyManager.kt#L110).

`completeRecovery()` activa el nuevo slot antes de destruir la clave anterior. `recovering` solo cambia a `false` cuando termina toda la operación. Si `previous.destroy()` lanza una excepción, el puntero ya identifica el slot nuevo, pero el manager continúa en recuperación. Después de desbloquear otra vez, salir de esa pantalla llama a `cancelRecovery()`, que destruye `keyManager` y su base sin comprobar si ese slot ya está activo.

También debe contemplarse que la sincronización del directorio falle después de escribir el puntero: el error no implica necesariamente que la activación no ocurrió.

**Impacto:** borrado de la clave/base que acaba de convertirse en almacenamiento activo. La disponibilidad de la copia externa no justifica ese borrado accidental.

**Evidencia:** ruta deducida de las operaciones y estados del código; no se inyectó un fallo real de Keystore/fsync en el teléfono.

**Corrección:** hacer explícito el punto de activación, reconciliar el estado con el puntero durable y tratar la retirada del slot anterior como limpieza posterior recuperable. La cancelación nunca debe destruir el slot activo.

**Pruebas necesarias:** fallos antes/después de publicar el puntero, fallo al destruir la clave vieja, cierre del proceso, reentrada y cancelación posterior.

### F01 — Alta: un gasto recurrente eliminado vuelve a aparecer

Fuente: [TransactionsViewModel.kt](app/src/main/java/dev/okami/luma/ui/transactions/TransactionsViewModel.kt#L408), líneas 408–450; [RecurringExpenseRepositoryImpl.kt](app/src/main/java/dev/okami/luma/data/RecurringExpenseRepositoryImpl.kt#L150), líneas 150–180.

Eliminar una fila no conserva ninguna marca de omisión para su par regla-fecha. Cada sesión vuelve a recorrer todas las fechas vencidas; el índice único solo impide insertar una fila que aún existe.

**Reproducción:** generar una mensualidad, eliminarla y abrir otra sesión con la regla activa. La fecha sigue entre los vencimientos y la fila vuelve a insertarse. Verificados el helper real de fechas y la secuencia de borrado/generación del código.

**Impacto:** se revierte una eliminación confirmada y cambia el gasto/saldo del usuario.

**Corrección:** definir la semántica de «omitir esta mensualidad» y persistirla, o impedir expresamente esa operación con una alternativa clara. Desactivar toda la regla no sustituye omitir una sola cuota.

**Prueba necesaria:** eliminación seguida de generación y nueva sesión, sin resurrección de la cuota.

### F02 — Alta: editar el día de una regla puede duplicar gastos de meses anteriores

Fuente: [RecurringExpensesViewModel.kt](app/src/main/java/dev/okami/luma/ui/recurring/RecurringExpensesViewModel.kt#L181), líneas 181–198; [RecurringExpenseRepositoryImpl.kt](app/src/main/java/dev/okami/luma/data/RecurringExpenseRepositoryImpl.kt#L150).

La edición cambia la fecha de inicio/día y vuelve a generar todo el rango vencido. La unicidad usa regla y fecha concreta, no la mensualidad.

**Reproducción con el helper compilado:** una regla desde el 10/01/2026 genera 10/01 y 10/02. Cambiar su inicio al 15/01 genera 15/01 y 15/02. Las filas antiguas permanecen; las nuevas no colisionan con ellas.

**Impacto:** un cambio de calendario puede añadir un segundo gasto en meses ya contabilizados.

**Corrección:** establecer desde cuándo se aplica una edición del calendario y preservar las mensualidades históricas. Si se desea recalcular el pasado, necesita una operación explícita y una confirmación del impacto.

**Pruebas necesarias:** cambios de día, fin de mes, fecha inicial, importe y reactivación, verificando histórico y número de cuotas.

### F03 — Alta: guardar una regla y generar sus cuotas no forman una operación atómica

Fuente: [RecurringExpensesViewModel.kt](app/src/main/java/dev/okami/luma/ui/recurring/RecurringExpensesViewModel.kt#L193), líneas 193–204; [RecurringExpensesScreen.kt](app/src/main/java/dev/okami/luma/ui/recurring/RecurringExpensesScreen.kt).

Se inserta la regla y después se llama a `generateDue()` en otra transacción. Si la generación falla, la regla queda guardada, pero el formulario conserva `editingId = null`. El siguiente intento obtiene otro UUID e inserta otra regla. Además, el formulario no tiene estado de guardado que deshabilite envíos repetidos.

**Impacto:** ante disco lleno, otro error durante generación o envíos repetidos pueden persistirse reglas duplicadas y, posteriormente, gastos duplicados.

**Evidencia:** revisión de las dos operaciones y del estado del formulario; falta una prueba con fallo inyectado en el repositorio real.

**Corrección:** alta/edición y materialización en una operación transaccional; identidad estable del borrador y protección de envío mientras se guarda.

**Pruebas necesarias:** fallo después de insertar la regla, reintento y doble envío. El resultado debe ser una sola regla y sus cuotas correspondientes.

### F04 — Alta para importes con tres decimales: el formateador puede mostrar un importe redondeado

Fuente: [DisplayFormatters.kt](app/src/main/java/dev/okami/luma/ui/common/DisplayFormatters.kt#L18), líneas 18–23.

Cambiar la moneda de `NumberFormat` no adapta automáticamente sus dígitos fraccionarios a esa moneda. El formateador conserva la escala de la moneda del locale.

**Reproducción sobre la función compilada en la JVM:** locale `es-ES`, `formatMoneyDisplay(1234, "BHD")` devuelve `1,23 BHD`; el valor exacto es `1,234 BHD`. Contrastado también con las fuentes del SDK Android 37: `java.text.DecimalFormat.setCurrency()` conserva explícitamente los dígitos fraccionarios previos. Los datos almacenados siguen siendo exactos. La interfaz de altas usa EUR, pero el dominio y las copias aceptan otras monedas. Falta la regresión ejecutada en Android.

**Corrección:** fijar explícitamente la escala del formateador según la moneda y no permitir redondeo inadvertido de unidades mínimas.

**Pruebas necesarias:** monedas de 0, 2 y 3 decimales, locales distintos, signos y límites de `Long`.

### F05 — Alta: convertir un gasto recurrente en ingreso puede producir una copia que la app rechaza

Fuente: [TransactionsViewModel.kt](app/src/main/java/dev/okami/luma/ui/transactions/TransactionsViewModel.kt#L129), `edit()`, `updateType()` y `reviewTransaction()`; [Transaction.kt](app/src/main/java/dev/okami/luma/domain/model/Transaction.kt); [EncryptedBackupRepository.kt](app/src/main/java/dev/okami/luma/security/EncryptedBackupRepository.kt#L155).

El formulario de edición permite cambiar un gasto generado a ingreso. Conserva `recurringExpenseId` y `recurrenceDate`; el cambio de tipo solo desactiva `repeatMonthly`. El dominio exige que ambos metadatos estén presentes o ausentes juntos, pero no exige que un movimiento vinculado sea un gasto.

**Reproducción:** el validador compilado devuelve `Valid` para un ingreso con ambos metadatos recurrentes. El editor permite construir ese estado y la actualización lo persiste. Exportación copia la fila sin comprobar esta invariante; importación rechaza explícitamente una fila con `recurrenceDate != null` y tipo distinto de `EXPENSE`.

**Impacto:** una edición permitida puede dejar una base que se abre normalmente y se exporta con mensaje de éxito, pero cuya copia no puede restaurarse. La fila también sigue ocupando la clave única de la cuota de gasto aunque ahora sea un ingreso.

**Corrección:** definir la operación de cambio de tipo de una cuota y hacer coincidir las invariantes de edición, dominio y restauración. Evitar tanto copias irrecuperables como una regeneración imprevista al desvincularla.

**Pruebas necesarias:** editar una cuota a ingreso, exportar/verificar/restaurar y volver a generar vencimientos, comprobando la semántica elegida y que la copia se recupera.

### S02 — Media: se ejecutan consultas sobre una copia antes de rechazar vistas y tablas virtuales

Fuente: [EncryptedBackupRepository.kt](app/src/main/java/dev/okami/luma/security/EncryptedBackupRepository.kt#L116), líneas 116–130.

Los primeros `COUNT(*)` ocurren antes de comprobar que los nombres financieros correspondan a tablas ordinarias permitidas. Un archivo puede definir `transactions` como una vista con una CTE recursiva sin término. El límite de filas se comprueba cuando termina el recuento y, por tanto, no acota ese trabajo.

**Reproducción:** en SQLite local, una vista pequeña con recursión sin fin hizo que `SELECT COUNT(*) FROM transactions` continuara hasta interrumpirlo con un límite de un millón de pasos. Falta reproducir el archivo cifrado completo en SQLCipher Android.

**Impacto:** denegación de servicio al verificar una copia elegida por el usuario. No se ha demostrado ejecución de código ni salida de datos. `trusted_schema=OFF` no impide por sí mismo esa recursión.

**Corrección:** validar primero el esquema y rechazar vistas/tablas virtuales antes de consultar sus contenidos; añadir límites de trabajo y cancelación efectiva para fuentes externas.

### S03 — Media: la validación de enteros importados puede aceptar valores fuera de rango por truncamiento

Fuente: [EncryptedBackupRepository.kt](app/src/main/java/dev/okami/luma/security/EncryptedBackupRepository.kt#L134), líneas 134–146.

`isDefault`, `dayOfMonth` y `active` se leen con `getInt()` antes de comprobar sus valores. Un entero SQLite de 64 bits puede truncarse a 32 bits: `4294967297` se convierte en `1`. La comprobación de tipo SQLite no comprueba el rango original, y `copyTable()` copia después el entero completo.

**Impacto:** aceptación de reglas/booleanos distintos de los valores que se validaron. Por ejemplo, una regla puede mostrarse activa por su conversión a `Int` y no satisfacer la consulta SQL `active = 1`.

**Corrección:** leer con `getLong()`, comprobar el rango original y convertir después. Validar también los enteros originales de los filtros JSON sin coerciones silenciosas.

**Evidencia:** comprobación de la reducción numérica y lectura del código; las fuentes Android de `CursorWindow.getInt()` realizan el cast de `getLong()`. Las fuentes de `org.json.JSON.toLong()` también confirman las coerciones numéricas. Pendiente una regresión instrumentada con el archivo cifrado malformado.

### S04 — Media: el dominio acepta monedas que el editor rechaza

Fuente: [Money.kt](app/src/main/java/dev/okami/luma/domain/model/Money.kt), [MoneyInputParser.kt](app/src/main/java/dev/okami/luma/domain/validation/MoneyInputParser.kt), [MoneyInputFormatter.kt](app/src/main/java/dev/okami/luma/ui/transactions/MoneyInputFormatter.kt).

`Money` acepta cualquier código reconocido por `Currency`, sin exigir una escala soportada. `XXX`, por ejemplo, tiene escala `-1`: el constructor lo acepta y la importación usa ese constructor. El editor exige escala `0..9`.

**Reproducción:** `Money(1, "XXX")` se construye; `formatMinorForInput(1, "XXX")` lanza `IllegalArgumentException`. La acción de edición no captura ese error.

**Impacto:** una copia puede introducir movimientos que provocan un cierre al intentar editarlos, además de una representación monetaria sin escala válida para el producto.

**Corrección:** centralizar las monedas/escalas admitidas en el dominio y aplicar la misma regla en formulario, persistencia e importación.

### S05 — Media: DatePicker guarda fechas financieras en SavedState

Fuente: [FormComponents.kt](app/src/main/java/dev/okami/luma/ui/common/FormComponents.kt#L76); `rememberDatePickerState` de Material 3 1.4.0 resuelto en el build.

Aunque el formulario evita `rememberSaveable` para sus textos, el helper de Material usa internamente `rememberSaveable`. Su Saver guarda `selectedDateMillis` y `displayedMonthMillis`. Se crea incluso cuando el calendario está cerrado y puede inicializarse con la fecha de una transacción editada.

**Evidencia:** inspección del código fuente de la dependencia presente en la caché local. No se extrajo el Bundle de un teléfono.

**Impacto:** la afirmación de `SECURITY.md` de que no hay datos financieros en SavedState es demasiado amplia. La fecha puede cruzar ese límite sin pasar por SQLCipher. Esto no demuestra acceso por parte de cualquier otra aplicación.

**Corrección:** usar un estado de calendario que no se guarde para estos formularios, o definir y documentar explícitamente qué metadatos se acepta guardar. Verificarlo inspeccionando el estado de la Activity.

### P01 — Media: el paginado no acota el coste del saldo de cada fila

Fuente: [FilterQuery.kt](app/src/main/java/dev/okami/luma/data/FilterQuery.kt#L53).

Cada movimiento calcula su saldo mediante una subconsulta correlacionada sobre el histórico de su moneda. El plan local muestra `CORRELATED SCALAR SUBQUERY` y una búsqueda cuyo filtro principal de índice es la moneda. Las 40 filas de una página reciente pueden recorrer repetidamente gran parte del histórico.

**Impacto:** coste creciente de carga e invalidación con bases grandes. El test de 100.000 agregados en memoria no mide esta consulta SQL. No se ha medido latencia en Android ni se afirma un ANR observado.

**Corrección:** medir la consulta real sobre un dispositivo modesto y revisar el cálculo del saldo para evitar el recorrido repetido. Conservar semántica histórica, filtros, desempates y detección de overflow; no introducir caches persistentes sin necesidad demostrada.

### Q01 — Media: los tests no validan todavía las garantías principales de sesión y recuperación

Fuente: `app/src/test`, `app/src/androidTest`, [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

Hay tests valiosos de dinero, validación, agregados, migraciones y cifrado. Faltan pruebas de los fallos anteriores y del ciclo de vida real de `VaultSessionManager`, Keystore, autenticación y las operaciones de `SecurityScreen`. No hay tests unitarios de `ExplorerViewModel` ni de `RecurringExpensesViewModel`. Parte de los tests visuales cubre las pantallas anteriores, mientras que la navegación real usa el explorador.

**Corrección:** cubrir primero reglas/reintentos, promoción de slots, cierre y aperturas tardías, corrupción/copies malformadas y la UI efectivamente utilizada. Después ejecutar instrumentación en el identificador de validación y una matriz de dispositivos. Compilar el APK de tests no ejecuta esos tests.

## Arquitectura, estructura y mantenibilidad

**Valoración: adecuada para el tamaño del producto, con desviaciones concretas.** Un módulo, composición manual y paquetes de dominio/datos/UI son razonables. No se necesita Hilt, un backend, más capas ceremoniales ni un framework de navegación para corregir los hallazgos.

- El dominio financiero es mayormente puro y testeable; repositorios y Room separan gran parte de la persistencia.
- `ExplorerViewModel` depende directamente de `LumaDatabase`, `ExplorerDao`, `SavedFilterEntity` y `AggregateRow` con tipos de almacenamiento. La UI del explorador convierte entidades Room a dominio. Conviene que un repositorio de consultas/filtros exponga los modelos que necesita ese flujo y permita probarlo con dobles pequeños.
- `SecurityScreen` coordina staging, verificación, restauración y limpieza con un scope de composición. Son operaciones importantes para que su propietario y estado de progreso dependan de la sesión de forma explícita y se puedan probar, especialmente con rotación, salida y cancelación.
- `TransactionsViewModel` tiene 584 líneas y su pantalla 689. La longitud no es un fallo automático; sí mezcla suficientes responsabilidades como para dificultar las regresiones. Extraer responsabilidades cuando una corrección lo necesite, manteniendo diffs acotados.
- Se mantienen implementaciones anteriores de estadísticas/listas junto al explorador nuevo. Los modos `paged` y `contentOverride` facilitan compatibilidad, pero también permiten que tests y aplicación real recorran caminos diferentes. Reducir esa duplicación gradualmente.
- Los textos y colores tienen buena centralización y las gráficas ofrecen tablas alternativas. Falta verificar TalkBack, selección de barras sin gesto táctil, orden del foco, texto ampliado y diálogos en pantallas pequeñas. La gráfica de barras depende principalmente de gestos para abrir su detalle; la tabla alternativa debe seguir siendo accesible.
- Existe un `collect` de categorías sin traducción de errores en `TransactionsViewModel.observeCategories()`. Revisar esa ruta para que errores de datos no terminen como excepciones sin manejar.
- El validador acepta fechas anteriores a 1900, mientras que la importación las rechaza. El calendario actual usa el rango predeterminado 1900–2100, por lo que **no se ha demostrado que un usuario pueda crear esas fechas mediante la UI actual**. Centralizar la regla evita divergencias futuras.
- Exportación no aplica los límites de tamaño/recuentos de importación. Una base que exceda esos límites puede producir una copia que la propia aplicación no restaure. Definir una política consistente y advertir antes de exportar una copia no recuperable.

## Preparación del primer commit público

Antes de subirlo:

1. **Añadir una licencia propia.** No hay `LICENSE` en la raíz. Los avisos de SQLCipher no definen la licencia de Luma. Elegirla corresponde al titular y debe cubrir también los recursos propios. GitHub explica la necesidad de una licencia para permitir reutilización como open source en su [guía de licencias](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).
2. **Añadir README.** Explicar propósito, estado de desarrollo, requisitos reales de SDK/JDK, cómo compilar/probar, necesidad de bloqueo de pantalla y riesgo de pérdida de claves. Enlazar este informe y los documentos existentes.
3. **Excluir configuración personal.** `.idea/deploymentTargetSelector.xml` contiene un identificador de un teléfono y no está excluido por las reglas actuales. Excluir ese archivo o las preferencias personales de `.idea`; no publicar el identificador. Revisar también `.kotlin/` y reglas preventivas para claves de firma, bases, copias y volcados. `local.properties`, builds, `.gradle` y Graphify ya tienen exclusiones.
4. **Revisar los archivos que realmente se van a añadir.** No usar indiscriminadamente `git add .`. El historial/índice no se pudo inspeccionar en este entorno. Revisar secretos y datos personales sobre la selección final antes del primer commit.
5. **Definir canal privado de seguridad y política de contribución.** El documento actual describe la protección técnica, pero no proporciona un contacto operativo para reportar vulnerabilidades.

Recomendado para que la calidad sea reproducible: CI con tests/lint/build, revisión de dependencias y validación del wrapper; fijar/verificar artefactos y dependencias transitivas. No existe `.github/workflows` ni metadata de verificación/lock de dependencias Gradle. No es necesario completar la matriz de Play para publicar el código como desarrollo.

El SHA-256 de la distribución Gradle coincide con el oficial de 9.5.0. El JAR del wrapper coincide con el oficial de 9.1.0: es un launcher anterior reconocido, no evidencia de manipulación. Actualizarlo de forma coherente en una tarea de build independiente. Fuente: [checksums oficiales de Gradle](https://gradle.org/release-checksums/).

El AAB incluye el aviso de SQLCipher y varios avisos AndroidX. Falta cerrar el inventario completo de componentes/avisos, incluidos los del proveedor criptográfico nativo. El upstream documenta LibTomCrypt como proveedor predeterminado; no asumir OpenSSL por la descripción genérica de SQLCipher. Fuente: [README de SQLCipher Android 4.18.0](https://github.com/sqlcipher/sqlcipher-android/blob/v4.18.0/README.md).

## Requisitos para la futura publicación en Google Play

Comprobados en documentación oficial el 8 de octubre de 2026; volver a revisarlos al preparar la publicación.

- **Target API:** la política vigente exige API 36 o superior para nuevas apps/actualizaciones desde el 31 de agosto de 2026. El `targetSdk = 37` actual supera ese mínimo. Esto no demuestra compatibilidad de todos los comportamientos Android. [Política oficial](https://support.google.com/googleplay/android-developer/answer/11926878).
- **Privacidad y Data Safety:** completar identidad/contacto, retención/borrado y URL pública estable, además de acceso a la política desde la app. El documento actual se declara incompleto; el resumen de la pantalla no incorpora todos esos datos. Declarar el comportamiento real de app, SDK y exportación voluntaria, usando las definiciones de Play. [Política de datos de usuario](https://support.google.com/googleplay/android-developer/answer/10144311).
- **Funciones financieras:** completar la declaración, obligatoria incluso para apps que declaran no ofrecerlas. Describir con precisión el registro local de finanzas; no confundirlo con funcionalidades de préstamos, pagos o banca. La elección exacta debe contrastarse con las opciones de Console al publicar. [Declaración oficial](https://support.google.com/googleplay/android-developer/answer/13849271).
- **Firma y artefacto:** establecer firma de subida/Play App Signing fuera del repositorio y validar el AAB de producción optimizado. El bundle actual está sin firmar. Verificar los avisos de licencias y comprobar el comportamiento con R8.
- **16 KiB:** los indicadores estáticos actuales son favorables, pero falta probar las bibliotecas nativas y la app en ese entorno. [Guía oficial de páginas de memoria](https://developer.android.com/guide/practices/page-sizes).
- **Prueba cerrada, si aplica a la cuenta:** las cuentas personales creadas después del 13 de noviembre de 2023 necesitan al menos 12 testers inscritos continuamente durante 14 días antes de solicitar acceso a producción. No se conoce el tipo/fecha de esta cuenta. [Requisitos oficiales de pruebas](https://support.google.com/googleplay/android-developer/answer/14151465).
- **Validación del producto:** autenticación, pantalla apagada, segundo plano, rotación, cancelación, dispositivos sin hardware seguro, recuperación entre dispositivos, disco lleno, copias malformadas, accesibilidad y rendimiento del histórico. Usar datos ficticios y `-Pluma.validation=true` en teléfonos personales.

## Orden de trabajo recomendado

1. Preparar el repositorio público: licencia, README, exclusiones y canal de seguridad.
2. Corregir S01 y F01–F05 con regresiones significativas. Corregir los límites del importador y la política de SavedState.
3. Ejecutar pruebas instrumentadas y la matriz de seguridad/recuperación sobre datos ficticios. Resolver la divergencia entre caminos testeados y utilizados.
4. Medir las consultas reales y revisar accesibilidad; resolver los compromisos que afecten al uso diario.
5. Completar identidad/privacidad/declaraciones, firma y validación del release antes de pedir revisión de Play.

Esta auditoría añade únicamente el informe; no modifica el comportamiento de la aplicación ni aplica las correcciones propuestas.
