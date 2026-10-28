package com.angelminga.iconifypreview

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.messages.Topic
import java.awt.Color
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon

private val LOG = logger<IconifyClient>()

fun interface IconifyListener {
    /** Some icons finished loading (or settings changed): editors should look up their icons again. */
    fun iconsChanged()
}

/**
 * Downloads icons from the Iconify API and keeps them on disk, so each icon is fetched once and previews work
 * offline afterwards. Lookups never block: [icon] returns null and starts a download, then publishes [TOPIC].
 *
 * Disk layout under `<system dir>/iconify-preview/`:
 * - `collections.txt`: known prefixes, one per line, refreshed weekly.
 * - `svg/<prefix>/<name>.svg`: the icon as served (16 px high, `currentColor` fills).
 * - `render/<rrggbb>/<prefix>/<name>.svg`: the same with `currentColor` replaced by the editor's text color, because
 *   [IconLoader] only reads SVG from a URL.
 */
@Service(Service.Level.APP)
class IconifyClient : Disposable {

    private sealed interface IconState
    private data object Loading : IconState
    private data object Missing : IconState
    private class Failed(val retryAt: Long) : IconState
    private class Loaded(val icon: Icon) : IconState

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Iconify Preview", 4)
    private val cacheDir: Path = PathManager.getSystemDir().resolve("iconify-preview")

    // Keyed by "prefix:name#rrggbb" for rendered icons and by "prefix:name" for icons the API does not have.
    private val icons = ConcurrentHashMap<String, IconState>()

    @Volatile private var prefixes: Set<String>? = null
    @Volatile private var prefixesRetryAt = 0L
    private val prefixesLoading = AtomicBoolean()

    /** The icon drawn in [color], or null while it loads, if it doesn't exist, or if the API can't be reached. */
    fun icon(match: IconMatch, color: Color): Icon? {
        val known = knownPrefixes() ?: return null
        if (match.prefix !in known || icons[match.id] === Missing) return null

        val hex = "%06x".format(color.rgb and 0xFFFFFF)
        val key = "${match.id}#$hex"
        val started = when (val state = icons[key]) {
            is Loaded -> return state.icon
            Loading, Missing -> return null
            is Failed -> System.currentTimeMillis() >= state.retryAt && icons.replace(key, state, Loading)
            null -> icons.putIfAbsent(key, Loading) == null
        }
        if (started) executor.execute { load(match, hex, key) }
        return null
    }

    /** Forgets everything loaded in memory (the disk cache stays), e.g. after the API URL changes. */
    fun reset() {
        icons.clear()
        prefixes = null
        prefixesRetryAt = 0
        publish()
    }

    private fun load(match: IconMatch, hex: String, key: String) {
        val state = try {
            val svg = svg(match)
            if (svg == null) {
                icons[match.id] = Missing
                Missing
            } else {
                Loaded(render(match, svg, hex))
            }
        } catch (e: IOException) {
            LOG.debug("Could not load ${match.id}", e)
            Failed(System.currentTimeMillis() + RETRY_MS)
        }
        icons[key] = state
        if (state is Loaded) publish()
    }

    /** The icon's SVG, from the disk cache or the API; null if the API doesn't have it. */
    private fun svg(match: IconMatch): String? {
        val file = cacheDir.resolve("svg").resolve(match.prefix).resolve("${match.name}.svg")
        if (Files.isRegularFile(file)) return Files.readString(file)

        val url = "${IconifyPreviewSettings.getInstance().apiUrl}/${match.prefix}/${match.name}.svg?height=16"
        val svg = try {
            request(url)
        } catch (e: HttpRequests.HttpStatusException) {
            if (e.statusCode == 404) return null
            throw e
        }
        if (!svg.trimStart().startsWith("<svg")) return null
        write(file, svg)
        return svg
    }

    private fun render(match: IconMatch, svg: String, hex: String): Icon {
        val file = cacheDir.resolve("render").resolve(hex).resolve(match.prefix).resolve("${match.name}.svg")
        if (!Files.isRegularFile(file)) write(file, svg.replace("currentColor", "#$hex"))
        return IconLoader.findIcon(file.toUri().toURL()) ?: throw IOException("Could not read $file")
    }

    private fun knownPrefixes(): Set<String>? {
        prefixes?.let { return it }
        if (System.currentTimeMillis() >= prefixesRetryAt && prefixesLoading.compareAndSet(false, true)) {
            executor.execute(::loadPrefixes)
        }
        return null
    }

    private fun loadPrefixes() {
        try {
            val file = cacheDir.resolve("collections.txt")
            val cached = Files.isRegularFile(file)
            val fresh = cached && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < PREFIXES_MAX_AGE_MS
            val lines = if (fresh) {
                Files.readAllLines(file)
            } else {
                try {
                    fetchPrefixes().also { write(file, it.joinToString("\n")) }
                } catch (e: IOException) {
                    // Offline: a stale list is better than no previews.
                    if (cached) Files.readAllLines(file) else throw e
                }
            }
            prefixes = lines.filter { it.isNotBlank() }.toSet()
            publish()
        } catch (e: Exception) {
            LOG.info("Could not load the Iconify collection list", e)
            prefixesRetryAt = System.currentTimeMillis() + RETRY_MS
        } finally {
            prefixesLoading.set(false)
        }
    }

    private fun fetchPrefixes(): List<String> {
        val json = request("${IconifyPreviewSettings.getInstance().apiUrl}/collections")
        return JsonParser.parseString(json).asJsonObject.keySet().toList()
    }

    private fun request(url: String): String =
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(TIMEOUT_MS)
            .readTimeout(TIMEOUT_MS)
            .readString()

    private fun write(file: Path, text: String) {
        Files.createDirectories(file.parent)
        // Write then move, so a concurrent reader never sees half a file.
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun publish() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).iconsChanged()
    }

    // Parent of every editor preview, so unloading the plugin removes all inlays.
    override fun dispose() = Unit

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<IconifyListener> = Topic.create("Iconify icons changed", IconifyListener::class.java)

        private const val TIMEOUT_MS = 10_000
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(1)
        private val PREFIXES_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

        fun getInstance(): IconifyClient = service()

        /** The icon's page on the Iconify website. */
        fun pageUrl(match: IconMatch): String = "https://icon-sets.iconify.design/${match.prefix}/${match.name}/"
    }
}
