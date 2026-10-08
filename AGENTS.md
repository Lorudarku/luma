# Guía de desarrollo para agentes

## Alcance y propósito

Estas instrucciones se aplican a todo el repositorio. Luma es una aplicación Android open source de finanzas personales para registrar y consultar ingresos y gastos. Debe ser útil como producto real, auditable por terceros y mantenible como proyecto profesional.

Prioriza, en este orden:

1. privacidad y seguridad;
2. corrección;
3. simplicidad;
4. mantenibilidad;
5. claridad;
6. facilidad de testing;
7. rendimiento razonable;
8. cantidad de código.

La aplicación no debe exigir una cuenta ni un backend para sus funciones principales. Los datos financieros deben permanecer en el dispositivo siempre que sea técnicamente posible. Evita sobreingeniería, abstracciones prematuras y tecnologías añadidas solo para demostrar sofisticación.

## Estado actual del repositorio

No confundas las decisiones futuras de esta guía con funcionalidades ya implementadas. Antes de trabajar, vuelve a comprobar los archivos de configuración porque esta sección puede quedar desactualizada conforme evolucione el proyecto.

- Proyecto Gradle con Kotlin DSL, catálogo de versiones y un único módulo `:app`.
- `namespace` y `applicationId`: `dev.okami.luma`.
- Android Gradle Plugin: `9.3.2`.
- Versión declarada para el plugin Kotlin Compose: `2.2.10`.
- Gradle Wrapper: `9.5.0`.
- `compileSdk`: 37; `targetSdk`: 37; `minSdk`: 26.
- Compatibilidad Java de código fuente y destino: Java 11.
- Jetpack Compose está habilitado. Las librerías Compose se versionan mediante BOM `2026.02.01` y se usa Material 3.
- Dependencias principales presentes: AndroidX Core KTX `1.10.1`, Lifecycle `2.6.1`, Activity Compose `1.8.0`, Room `2.8.4`, Coroutines `1.11.0`, KSP `2.3.11`, Compose UI/Graphics/Preview/Material 3 y tooling de debug.
- Testing presente: JUnit 4 `4.13.2`, Coroutines Test `1.11.0`, Room Testing `2.8.4`, AndroidX JUnit `1.1.5`, Espresso `3.5.1` y Compose UI Test mediante el BOM.
- La app incluye la vertical manual y su ampliación: modelo monetario exacto, validación, Room v4 sobre SQLCipher, alta, listado paginado, edición, eliminación confirmada, filtros compartidos, totales y balances por movimiento y moneda.
- La estructura actual separa `domain`, `data` y `ui`; la composición de dependencias es manual mediante `LumaApplication` y `AppContainer`.
- Hay navegación inferior local con iconos vectoriales, recurrencias, categorías con detalle, filtros y gráficas Canvas con ejes, gestos y tablas. Hay copias cifradas manuales y recuperación; no hay framework de inyección, IA, voz ni dependencias de red. Consultar `IMPLEMENTATION_STATUS.md` antes de dar por validado el flujo en dispositivo.
- El manifiesto declara biometría y protección frente a overlays, tiene `android:allowBackup="false"` y excluye backups y transferencias automáticas. Keystore exige autenticación; por decisión de compatibilidad se prefiere hardware seguro sin exigirlo y se acepta el respaldo por software. No recrees claves perdidas de bases existentes. Los límites y revisiones pendientes se documentan en `SECURITY.md`.

No cambies `minSdk`, `targetSdk`, `compileSdk`, Kotlin, AGP, Compose, Gradle ni dependencias importantes como parte incidental de otra tarea. Trata cada actualización como una tarea independiente y justificada.

## Arquitectura y evolución

Usa Kotlin como lenguaje principal, Jetpack Compose para UI, AndroidX y Gradle Kotlin DSL. Mantén una arquitectura sencilla, modular y coherente con el tamaño real del producto.

- Prefiere MVVM y límites claros entre UI, dominio y datos.
- Usa `ViewModel` para estado de UI y Coroutines/Flow cuando la funcionalidad los requiera.
- La persistencia estructurada actual usa Room sobre SQLite; conserva el esquema versionado y añade migraciones explícitas cuando cambie.
- Introduce casos de uso solo si encapsulan lógica reutilizable o simplifican dependencias; no replique delegaciones sin valor.
- Mantén la lógica de negocio fuera de composables y el acceso a persistencia fuera de la UI.
- La organización futura puede incluir `ui`, `domain`, `data`, `ai`, `speech` y `core`, pero no crees paquetes vacíos, módulos o capas por anticipado.
- No apliques Clean Architecture de forma ceremonial. Cada interfaz, capa y abstracción debe resolver una necesidad concreta.
- Reutiliza los patrones ya establecidos y favorece cambios pequeños. No reescribas componentes sanos ni hagas grandes refactorizaciones fuera del alcance solicitado.

## Privacidad, almacenamiento local y conectividad

La privacidad es un requisito funcional y debe formar parte del diseño y de los criterios de aceptación.

- No introduzcas telemetría, analytics, trackers ni profiling.
- No añadas Firebase, publicidad, cuentas, autenticación, backend o sincronización en la nube salvo petición explícita.
- No envíes información financiera, personal o grabaciones a servicios externos. Nunca la uses para publicidad o analytics.
- Prefiere procesamiento y almacenamiento local/on-device. La funcionalidad principal debe seguir disponible offline.
- Solicita aprobación antes de añadir una dependencia o función que pueda enviar datos por red. Explica su necesidad, qué datos transmite, destino, momento, consentimiento y alternativa local.
- Documenta claramente todo componente que necesite conectividad y aplica minimización de datos.
- Solicita solo permisos Android indispensables y en el momento adecuado. Revisa especialmente red, almacenamiento y micrófono.
- No guardes secretos, API keys, tokens ni credenciales en el repositorio.
- Usa únicamente datos ficticios en tests, previews, fixtures, capturas y ejemplos.
- No registres datos financieros o personales innecesariamente en Logcat, mensajes de error, métricas ni informes.
- Antes de persistir información sensible, define reglas explícitas para `backup_rules.xml` y `data_extraction_rules.xml`. No permitas que datos financieros entren de forma implícita en backups en la nube; exportaciones y copias deben quedar bajo control informado del usuario.

## Dinero y lógica financiera

La exactitud monetaria es obligatoria.

- Nunca representes cantidades monetarias persistentes ni cálculos financieros con `Float` o `Double`.
- Prefiere unidades monetarias mínimas enteras mediante `Long` y almacena la moneda por separado. Por ejemplo, `120,50 EUR` se representa conceptualmente como `amountMinor = 12050` y `currency = EUR`.
- Define de forma explícita escalas, reglas de redondeo, signos, límites y conversiones. Si un caso exige decimales, utiliza un tipo decimal exacto y una política documentada.
- No mezcles formateo o lógica de presentación con cálculos financieros.
- Haz que las operaciones sean deterministas, validadas y resistentes a overflow y entradas inválidas.
- Toda lógica financiera y todo bug financiero corregido deben tener tests significativos, incluidos límites y casos de error.

## IA y lenguaje natural

La entrada en lenguaje natural y la IA son directrices futuras; no están implementadas. La IA interpreta intención, pero nunca es autoridad sobre los datos del usuario.

El flujo obligatorio es:

`voz/texto -> interpretación -> estructura tipada -> validación -> confirmación del usuario -> persistencia`

- Un modelo nunca escribe directamente en Room/SQLite ni genera o ejecuta SQL sobre la base de datos.
- Considera no confiable toda salida de un modelo. Valida cantidades, fechas, monedas, tipos de transacción, categorías y campos obligatorios.
- Presenta el resultado interpretado para que el usuario pueda comprobarlo, editarlo y confirmarlo antes de guardar.
- Mantén desacoplada la interfaz del motor y su implementación para permitir sustituir el modelo sin reescribir la aplicación.
- Prefiere modelos on-device cuando sean viables y mecanismos deterministas o basados en reglas para casos sencillos.
- No introduzcas una dependencia obligatoria de APIs de IA en la nube sin una decisión explícita y una revisión de privacidad.
- Diseña fallos, cancelaciones y baja disponibilidad del modelo sin comprometer datos ni bloquear el registro manual.

## Reconocimiento de voz

La voz también es una capacidad futura y opcional.

- Prefiere reconocimiento on-device cuando Android y el dispositivo lo permitan.
- Trata toda transcripción como entrada externa no confiable y sométela a validación y confirmación.
- No guardes audio salvo razón funcional explícita y aprobada; no envíes grabaciones a servidores propios o de terceros de forma implícita.
- Solicita solo el permiso de micrófono y únicamente cuando el usuario inicie la función.
- Mantén una alternativa completa por texto/manual: la aplicación debe ser utilizable sin voz.

## Kotlin y calidad de código

- Prioriza código claro, nombres descriptivos, funciones pequeñas y cohesionadas, inmutabilidad razonable y null-safety.
- Usa sealed classes/interfaces, enums y value objects cuando aporten seguridad de tipos; prefiere composición a herencia.
- Diseña APIs explícitas, responsabilidades acotadas, alta cohesión, bajo acoplamiento y manejo visible de errores.
- Evita clases gigantes, funciones largas, booleanos ambiguos, números o strings mágicos, duplicación y estado global mutable.
- Evita singletons innecesarios, `!!` salvo justificación excepcional, `catch` vacíos y excepciones ignoradas.
- No introduzcas un patrón de diseño si no resuelve un problema actual.
- Valida toda entrada externa, incluidos imports y resultados de IA. Usa primitivas criptográficas estándar cuando sean necesarias; nunca diseñes criptografía propia ni hardcodees claves.

## Jetpack Compose y UX

- Mantén composables pequeños y reutilizables cuando aporte claridad; pasa estado y callbacks explícitos y favorece state hoisting.
- Separa estado de UI, lógica de negocio y persistencia.
- Contempla estados de carga, vacío y error cuando correspondan.
- Evita recomposiciones innecesarias evidentes y trabajo pesado en el hilo principal.
- Cuida accesibilidad, semántica, contraste, áreas táctiles y tamaños de texto razonables.
- No añadas dependencias de UI si Compose/AndroidX resuelven adecuadamente la necesidad.
- Minimiza las acciones necesarias para registrar un ingreso o gasto. IA y voz deben reducir fricción.
- El usuario conserva el control de qué se guarda, modifica, elimina o sale del dispositivo. No uses dark patterns.

## Dependencias y open source

Antes de añadir una dependencia:

1. demuestra que la plataforma, Kotlin o AndroidX no cubren ya el caso de forma suficiente;
2. evalúa mantenimiento, estabilidad, tamaño y compatibilidad;
3. revisa privacidad, seguridad y comportamiento de red;
4. verifica licencia y compatibilidad con un proyecto open source;
5. evita librerías abandonadas o una librería para algo trivial que pueda resolverse claramente con pocas líneas.

Para dependencias importantes o con comunicación externa, explica y acuerda primero su necesidad. Favorece alternativas open source, mantenibles y auditables. Respeta licencias, no copies código sin permiso y conserva builds reproducibles por terceros. Registra decisiones técnicas relevantes cuando afecten arquitectura, seguridad o privacidad.

## Testing

Todo comportamiento importante debe poder probarse. Prioriza:

1. lógica financiera;
2. parsing y validación de transacciones;
3. repositorios;
4. casos de uso;
5. lógica de ViewModel;
6. UI crítica cuando el test aporte valor.

Usa tests deterministas, independientes de servicios externos salvo necesidad explícita. Añade tests de regresión para bugs cuando sea razonable. No escribas tests triviales solo para aumentar cobertura ni elimines o debilites tests para conseguir un build verde.

## Flujo de trabajo de los agentes

Antes de modificar código:

1. inspecciona los archivos relacionados, la configuración y el estado del repositorio;
2. comprende el diseño existente y delimita el alcance;
3. reutiliza convenciones actuales y realiza el cambio mínimo coherente;
4. añade o actualiza los tests relevantes;
5. ejecuta las comprobaciones aplicables y corrige los fallos causados por el cambio;
6. revisa el diff final para detectar cambios accidentales;
7. informa de lo modificado, verificaciones realizadas y limitaciones pendientes.

No aproveches tareas pequeñas para refactorizaciones amplias. Si detectas un problema arquitectónico importante fuera de alcance, descríbelo y solicita una tarea específica en lugar de reestructurar el proyecto unilateralmente.

## Verificación

Después de cambios relevantes, usa las tareas Gradle disponibles. Como mínimo, cuando sean aplicables:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Ejecuta además tests instrumentados o de UI cuando el cambio los requiera y haya dispositivo/emulador disponible. No des por correcto un cambio porque compile un archivo aislado. Si falla una comprobación, determina si el cambio la causó, corrígela dentro del alcance y comunica con exactitud cualquier bloqueo preexistente o ambiental. No alteres lint ni configuración de tests solo para silenciar errores.

En teléfonos personales usa `./gradlew -Pluma.validation=true connectedDebugAndroidTest`: el identificador aislado `dev.okami.luma.validation` evita que el runner sustituya o desinstale la app con datos reales. Si Android rechaza la instalación, no cambies restricciones del dispositivo ni reintentes sin autorización.

## Git y alcance de los cambios

- No hagas `git commit` ni `git push` salvo solicitud explícita.
- No modifiques el historial ni uses operaciones destructivas como `git reset --hard` o `git clean -fd` salvo petición explícita.
- No sobrescribas cambios del usuario ni archivos no relacionados; trabaja incluso si el árbol ya contiene cambios ajenos.
- Comprueba el estado antes de cambios grandes y mantén diffs pequeños y revisables.
- Cuando se pidan commits, usa mensajes claros, preferiblemente Conventional Commits, por ejemplo `feat: add transaction validation` o `fix: prevent monetary rounding error`.

## Rendimiento y documentación

- No hagas microoptimizaciones prematuras, pero evita trabajo pesado en el hilo principal, consultas evidentemente ineficientes y cargar conjuntos completos sin necesidad.
- Usa coroutines con ciclo de vida y dispatcher apropiados cuando se incorporen; cuida memoria, batería y dispositivos con recursos limitados.
- Trata la IA local como un recurso potencialmente costoso y proporciona cancelación y degradación razonable.
- Haz que nombres y diseño expliquen el código. Además, toda función nueva o modificada debe llevar inmediatamente encima un comentario estructurado —preferiblemente KDoc— que indique su propósito, entradas, salida/efectos y los pasos relevantes de su implementación. En funciones con varias ramas o fases, comenta también dentro del cuerpo esas partes con bloques breves y numerados. Mantén los comentarios sincronizados con el código; no describas comportamiento inexistente ni uses comentarios línea por línea para código trivial.
- Actualiza la documentación cuando cambien decisiones de arquitectura, seguridad o privacidad.

## Graphify usage

- Graphify está disponible mediante `.graphify-venv/bin/graphify` como índice estructural local del repositorio. Úsalo cuando una tarea requiera comprender relaciones entre múltiples archivos, clases o capas; para tareas pequeñas y locales, inspecciona directamente los archivos relevantes.
- Usa el grafo para localizar candidatos, dependencias, vecinos, llamadas y rutas. Después, lee siempre el código fuente real antes de modificarlo.
- Considera las relaciones inferidas o ambiguas como indicios, no como hechos definitivos.
- Si el grafo parece desactualizado, actualízalo con `GRAPHIFY_QUERY_LOG_DISABLE=1 .graphify-venv/bin/graphify extract . --code-only` antes de basar una decisión importante en él.
- No utilices Graphify como sustituto de tests, lint, build, lectura del código o validación manual.
- Mantén Graphify en modo local `code-only`. No habilites backends semánticos, servicios externos, MCP, watchers ni hooks Git sin aprobación explícita.
