# Commit Messages for Claude Code (plugin para IDEs de JetBrains)

Añade un botón ✳ en el panel de commit. Al pulsarlo:

1. Toma los archivos marcados para el commit.
2. Envía el diff a `claude -p` (usa tu suscripción Pro/Max, sin API key).
3. Muestra una notificación con el **tipo detectado** (`feat`, `fix`, `refactor`…) y el motivo.
4. Prellena el mensaje en formato Conventional Commits.

## Requisitos
- macOS con Claude Code instalado y sesión iniciada (`claude` → opción 1, suscripción).
- JDK 21 (IntelliJ puede descargarlo: *Project Structure → SDK → Download JDK*).
- IntelliJ IDEA, WebStorm u otro IDE de JetBrains 2025.2 o superior (verificado en 2026.2 y 2026.3).

## Compilar

**Opción A – desde IntelliJ (más fácil)**
1. *File → Open* → selecciona la carpeta `claude-commit`.
2. Espera a que importe Gradle (usa JDK 21 si lo pregunta).
3. Panel **Gradle** → `Tasks → intellij platform → buildPlugin`.

**Opción B – terminal**
```bash
brew install gradle      # solo la primera vez
cd claude-commit
gradle wrapper           # genera ./gradlew
./gradlew buildPlugin
```

El plugin queda en `build/distributions/claude-commit-<version>.zip`.

Para probarlo sin instalar: `./gradlew runIde` abre un IntelliJ de pruebas con el plugin cargado.

## Instalar
*Settings → Plugins → ⚙ → Install Plugin from Disk…* → elige el `.zip` → reinicia.

## Uso
1. Abre el panel de commit (`⌘K`).
2. Marca los archivos.
3. Pulsa el botón **Generar Commit con Claude** junto al campo del mensaje.
4. Revisa el mensaje y confirma.

Atajo opcional: *Settings → Keymap* → busca "Generar Commit con Claude".

## Personalizar
El prompt (idioma, tipos, formato) está en
`src/main/kotlin/com/angelminga/claudecommit/ClaudeClient.kt`.
