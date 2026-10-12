# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Qué es

Plugin de IntelliJ IDEA (Kotlin, IntelliJ Platform Gradle Plugin 2.x) que añade un botón en el panel de commit: envía el diff de los archivos marcados a la CLI `claude -p` (suscripción, sin API key) y prellena el mensaje en formato Conventional Commits. Todo el texto visible al usuario, el prompt y los comentarios están en español.

## Estructura

```text
claude-commit/
├── build.gradle.kts                 # IntelliJ Platform Gradle Plugin, versión del IDE destino, sinceBuild, deps de test
├── gradle.properties / settings.gradle.kts
├── gradle/wrapper/                  # solo gradle-wrapper.properties (Gradle 8.14.3), sin script gradlew
└── src/
    ├── main/kotlin/com/angelminga/claudecommit/
    │   ├── GenerateCommitAction.kt      # botón del panel de commit; orquesta todo el flujo
    │   ├── DiffCollector.kt             # cambios marcados → diff unificado
    │   ├── ClaudeClient.kt              # prompt + ejecución de `claude -p`
    │   ├── CommitMessageParser.kt       # respuesta de Claude → CommitSuggestion (sin deps de IntelliJ)
    │   ├── Shell.kt                     # ejecución de procesos con timeout y cancelación
    │   ├── ClaudeCommitSettings.kt      # estado persistente (modelo, idioma, timeout, sobrescritura)
    │   └── ClaudeCommitConfigurable.kt  # UI de Settings → Tools → Claude Commit
    ├── main/resources/
    │   ├── META-INF/plugin.xml                    # id, dependencias, acción, notificaciones, settings
    │   ├── META-INF/claudeCommit-platformVcs.xml  # descriptor vacío de la dependencia opcional (ver abajo)
    │   ├── META-INF/pluginIcon.svg / pluginIcon_dark.svg  # icono 40×40 de la lista de plugins (tema claro/oscuro)
    │   └── icons/claudeCommit.svg / _dark.svg     # icono 16×16 del botón (mismo diseño que pluginIcon)
    └── test/kotlin/com/angelminga/claudecommit/
        ├── CommitMessageParserTest.kt
        └── ShellTest.kt                 # stdin grande, Broken pipe, timeout
```

## Comandos

### Generar el .zip instalable

El repo no incluye el script `gradlew`. Dos opciones:

```bash
# A) Generar el wrapper una vez (requiere `gradle` instalado, p. ej. `brew install gradle`) y usarlo
gradle wrapper
./gradlew buildPlugin

# B) Sin wrapper: usar el Gradle 8.14.3 que IntelliJ ya descargó en la caché
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle)
$G clean test buildPlugin
```

Resultado: `build/distributions/claude-commit-<version>.zip` (la versión sale de `version` en `build.gradle.kts`). Se instala con *Settings → Plugins → ⚙ → Install Plugin from Disk…* y reiniciando el IDE. Para reinstalar, desinstalar antes la versión previa o subir `version`.

### Otros

```bash
./gradlew runIde        # abre un IntelliJ sandbox con el plugin cargado
./gradlew test          # tests JUnit 4
./gradlew test --tests 'com.angelminga.claudecommit.CommitMessageParserTest'   # un solo test
```

- Requiere JDK 21 (`jvmToolchain(21)`). Compila contra IntelliJ IDEA Community 2025.2; `sinceBuild = 252`. Dependencias de VCS en `plugin.xml`: `com.intellij.modules.vcs` (obligatoria; en ≤ 262 el VCS está en el núcleo) y `com.intellij.platform.vcs` opcional con `claudeCommit-platformVcs.xml` vacío (desde 263 el VCS es ese plugin y sin esta dependencia sus clases no son visibles). No quitar ninguna de las dos. Para comprobar compatibilidad con un IDE local: `java -jar verifier-cli-<ver>-all.jar check-plugin build/distributions/claude-commit-<version>.zip "<IDE>.app/Contents"`. Compilar contra 2026.3 requiere IntelliJ Platform Gradle Plugin ≥ 2.19 y Gradle 9.
- `buildSearchableOptions = false` y `kotlin.stdlib.default.dependency=false` (se usa la stdlib que trae la plataforma).
- `bundledModule("intellij.platform.vcs.impl")` es necesario: `AbstractCommitWorkflowHandler`, `IdeaTextPatchBuilder` y `UnifiedDiffWriter` viven en ese módulo y en 2025.2 no están en el classpath por defecto.

## Arquitectura

Flujo de `GenerateCommitAction` (registrada en `plugin.xml` dentro de `Vcs.MessageActionGroup`):

1. En el EDT obtiene los cambios incluidos (`handler.ui.getIncludedChanges()`) y los archivos sin versionar marcados (`getIncludedUnversionedFiles()`); si el panel no está disponible usa la changelist activa; si hay panel y no hay nada marcado, avisa y no hace nada. Guarda los documentos abiertos (`saveAllDocuments`) y marca el proyecto con la clave `RUNNING`, que deshabilita el botón hasta `onFinished`.
2. Lanza un `Task.Backgroundable` que:
   - `DiffCollector.collect` → construye un diff unificado con `IdeaTextPatchBuilder` + `UnifiedDiffWriter` a partir de las revisiones de IntelliJ (independiente del VCS, respeta los fragmentos excluidos en commits parciales). Los archivos sin versionar se convierten en `Change(null, CurrentContentRevision)`. Binarios → `[archivo binario]`. Trunca a 15 000 caracteres por archivo y 100 000 en total.
   - Obtiene la rama con `git rev-parse --abbrev-ref HEAD` (el prompt pide incluir IDs de ticket tipo `ABC-123`).
   - `ClaudeClient.suggest` → ejecuta `claude -p --tools "" --no-session-persistence [--model X]` mediante un login shell (`$SHELL -l -c`) en un directorio temporal del IDE (`PathManager.getTempPath()/claude-commit`), no en el repo, para que no cargue el CLAUDE.md, hooks ni MCP del proyecto. El diff va por stdin; el prompt y el modelo por variables de entorno (`CLAUDE_COMMIT_PROMPT`, `CLAUDE_COMMIT_MODEL`) para no interpolar texto en el script. `--model` se añade con `set --` + `"$@"`: `${VAR:+--model "$VAR"}` no funciona en zsh (lo pasa como un solo argumento). Antepone al `PATH` `~/.local/bin`, `~/.claude/local`, `/opt/homebrew/bin` y `/usr/local/bin` porque IntelliJ lanzado desde el Dock no hereda el PATH de la terminal. Exit 127 / "command not found" → error de CLI no instalada.
3. `onSuccess` pide confirmación si ya hay un mensaje escrito (salvo que el ajuste lo desactive), lo escribe vía `CommitMessageI.setCommitMessage` y muestra una notificación del grupo `"Claude Commit"` (declarado en `plugin.xml`; el id debe coincidir).

Ajustes: `ClaudeCommitSettings` (servicio de aplicación, se guarda en `claudeCommit.xml`) con modelo, idioma, timeout y sobrescritura sin preguntar; UI en `ClaudeCommitConfigurable` (Settings → Tools → Claude Commit).

`Shell.run` es el único punto de ejecución de procesos: lee stdout/stderr y escribe stdin en hilos de `AppExecutorUtil` (UTF-8; un `IOException` al escribir se ignora y manda el exit code), hace polling cada 200 ms y mata el proceso si el `ProgressIndicator` se cancela (lanza `ProcessCanceledException`) o se supera el timeout.

### Contrato del prompt ↔ parser

El prompt en `ClaudeClient` exige la respuesta con líneas `TIPO:`, `RAZON:` y `MENSAJE:`; `CommitMessageParser.parse` las extrae con regex. Si se cambia el formato del prompt hay que actualizar el parser y sus tests (y viceversa). `CommitMessageParser` no depende de IntelliJ a propósito, para poder testearlo con JUnit puro. Si Claude no respeta el formato, se usa toda la salida como mensaje y el tipo se deduce del prefijo del título; tipos fuera de la lista `TYPES` se marcan como "(no estándar)".
