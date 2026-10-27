package io.github.jackwener.dsh.core.capability.tools

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.capability.strs
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Shared-storage file operations ("All files access"). Paths may be absolute or relative to /sdcard. */
@Singleton
class FileTools @Inject constructor(@ApplicationContext private val context: Context) : ToolSet {
    private val root: File = Environment.getExternalStorageDirectory()

    private fun need() { if (!Environment.isExternalStorageManager()) throw PermissionMissingException("All files access") }

    private fun resolve(path: String): File {
        val f = (if (path.startsWith("/")) File(path) else File(root, path)).canonicalFile
        val allowed = listOf(root.canonicalPath, "/storage", "/sdcard")
        if (allowed.none { f.path.startsWith(it) } || f.path.startsWith(File(root, "Android/data").canonicalPath) ||
            f.path.startsWith(File(root, "Android/obb").canonicalPath))
            throw InvalidArgsException("Path outside shared storage is not accessible: ${f.path}")
        return f
    }

    private fun File.describe(): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastModified()))
        return if (isDirectory) "d  ${"-".padStart(9)}  $date  $path/" else "-  ${human(length()).padStart(9)}  $date  $path"
    }

    private fun human(n: Long): String = when {
        n >= 1L shl 30 -> "%.1fG".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1fM".format(n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> "%.1fK".format(n / (1L shl 10).toDouble())
        else -> "${n}B"
    }

    override val tools = listOf(
        object : Tool("file_search", ToolGroup.Files, Risk.read, false,
            "Find files on shared storage by name pattern and/or text content. Fast (ripgrep). Use before reading or modifying files.", {
                string("name", "Glob on file name, e.g. '*.pdf' or '*invoice*'")
                string("content", "Text/regex the file must contain (text files only)")
                string("dir", "Directory to search, default /sdcard")
                int("limit", "Max results, default 100")
                string("sort", "Order of results", enum = listOf("recent", "largest", "name"))
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                need()
                val dir = resolve(args.str("dir") ?: root.path)
                val limit = (args.int("limit") ?: 100).coerceIn(1, 1000)
                val cmd = mutableListOf(File(context.filesDir, "bin/rg").path, "--files", "--hidden", "--no-messages",
                    "-g", "!Android/data/**", "-g", "!Android/obb/**")
                args.str("name")?.let { cmd += listOf("--iglob", it) }
                val content = args.str("content")
                val files: List<File> = if (content != null) {
                    val c = mutableListOf(File(context.filesDir, "bin/rg").path, "-l", "-i", "--no-messages", "-g", "!Android/data/**")
                    args.str("name")?.let { c += listOf("--iglob", it) }
                    c += listOf(content, dir.path)
                    run(c)
                } else run(cmd + dir.path)
                val sorted = when (args.str("sort")) {
                    "largest" -> files.sortedByDescending { it.length() }
                    "name" -> files.sortedBy { it.name.lowercase() }
                    else -> files.sortedByDescending { it.lastModified() }
                }
                val shown = sorted.take(limit)
                ToolResult.text(if (shown.isEmpty()) "No files found." else
                    "${files.size} match(es)${if (files.size > limit) ", showing $limit" else ""}:\n" + shown.joinToString("\n") { it.describe() })
            }

            private fun run(cmd: List<String>): List<File> {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                val out = p.inputStream.bufferedReader().readLines()
                p.waitFor(60, TimeUnit.SECONDS)
                return out.filter { it.isNotBlank() }.map { File(it) }
            }
        },
        object : Tool("file_list", ToolGroup.Files, Risk.read, false, "List a directory (size, modified time).", {
            string("path", "Directory, default /sdcard")
            bool("recursive", "Include subdirectories (max 500 entries)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val dir = resolve(args.str("path") ?: root.path)
                if (!dir.isDirectory) throw InvalidArgsException("Not a directory: ${dir.path}")
                val entries = if (args.bool("recursive") == true) dir.walkTopDown().drop(1).take(500).toList()
                else dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.toList() ?: emptyList()
                ToolResult.text(if (entries.isEmpty()) "(empty)" else entries.joinToString("\n") { it.describe() })
            }
        },
        object : Tool("file_read", ToolGroup.Files, Risk.read, false,
            "Read a file. Text files return text (use offset/limit lines for big files); images return the image.", {
                string("path", "File path", required = true)
                int("offset", "First line (0-based)")
                int("limit", "Number of lines, default 400")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val f = resolve(args.reqStr("path"))
                if (!f.isFile) throw InvalidArgsException("Not a file: ${f.path}")
                val ext = f.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
                when {
                    mime.startsWith("image/") && f.length() < 8 shl 20 ->
                        ToolResult.image(mime, Base64.encodeToString(Images.downscale(f), Base64.NO_WRAP), f.describe())
                    looksBinary(f) -> ToolResult.text("Binary file (${mime.ifEmpty { ext }}), ${human(f.length())}. Cannot show content.")
                    else -> {
                        val off = args.int("offset") ?: 0
                        val lim = (args.int("limit") ?: 400).coerceIn(1, 4000)
                        val lines = f.bufferedReader().useLines { it.drop(off).take(lim + 1).toList() }
                        val more = lines.size > lim
                        ToolResult.text(lines.take(lim).joinToString("\n") + if (more) "\n… (more lines; use offset=${off + lim})" else "")
                    }
                }
            }

            private fun looksBinary(f: File): Boolean = f.inputStream().use { s ->
                val buf = ByteArray(4096); val n = s.read(buf)
                (0 until maxOf(n, 0)).any { buf[it] == 0.toByte() }
            }
        },
        object : Tool("file_stat", ToolGroup.Files, Risk.read, false, "Get size/type/modified time of files or folders (folder size is computed).", {
            strings("paths", "Paths", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                ToolResult.text(args.strs("paths").joinToString("\n") { p ->
                    val f = resolve(p)
                    when {
                        !f.exists() -> "$p: not found"
                        f.isDirectory -> { val files = f.walkTopDown().filter { it.isFile }.toList()
                            "${f.path}/: folder, ${files.size} files, ${human(files.sumOf { it.length() })}" }
                        else -> f.describe()
                    }
                })
            }
        },
        object : Tool("file_write", ToolGroup.Files, Risk.write, false, "Create or overwrite a text file (or append).", {
            string("path", "File path", required = true)
            string("content", "Text content", required = true)
            bool("append", "Append instead of overwrite")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val f = resolve(args.reqStr("path"))
                f.parentFile?.mkdirs()
                if (args.bool("append") == true) f.appendText(args.str("content") ?: "") else f.writeText(args.str("content") ?: "")
                ToolResult.text("Wrote ${f.path} (${human(f.length())})")
            }
        },
        object : Tool("file_mkdir", ToolGroup.Files, Risk.write, false, "Create folders (with parents).", {
            strings("paths", "Folders to create", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need(); ToolResult.text(args.strs("paths").joinToString("\n") { val f = resolve(it); f.mkdirs(); "ok ${f.path}" })
            }
        },
        object : Tool("file_move", ToolGroup.Files, Risk.write, false,
            "Move or rename files/folders. Each item is {from,to}; 'to' may be an existing folder.", {
                raw("items", kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("array"))
                    put("items", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        put("properties", kotlinx.serialization.json.buildJsonObject {
                            put("from", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("string")) })
                            put("to", kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("string")) })
                        })
                        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("from"), kotlinx.serialization.json.JsonPrimitive("to"))))
                    })
                }, required = true)
                bool("copy", "Copy instead of move")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val copy = args.bool("copy") == true
                val items = (args["items"] as? kotlinx.serialization.json.JsonArray) ?: throw InvalidArgsException("missing items")
                ToolResult.text(items.joinToString("\n") { el ->
                    val o = el as JsonObject
                    val from = resolve(o.reqStr("from"))
                    var to = resolve(o.reqStr("to"))
                    if (to.isDirectory) to = File(to, from.name)
                    if (to.exists()) return@joinToString "skip ${from.path}: ${to.path} exists"
                    to.parentFile?.mkdirs()
                    if (copy) { from.copyRecursively(to); "copied ${from.path} → ${to.path}" }
                    else if (from.renameTo(to)) "moved ${from.path} → ${to.path}"
                    else { from.copyRecursively(to); from.deleteRecursively(); "moved ${from.path} → ${to.path}" }
                })
            }
        },
        object : Tool("file_delete", ToolGroup.Files, Risk.dangerous, false,
            "Permanently delete files/folders. The user sees the exact list before approving, so pass every path explicitly.", {
                strings("paths", "Exact paths to delete", required = true)
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val paths = args.strs("paths")
                if (paths.isEmpty()) throw InvalidArgsException("no paths")
                ToolResult.text(paths.joinToString("\n") { p ->
                    val f = resolve(p)
                    if (f.canonicalPath == root.canonicalPath) "refused: ${f.path} (storage root)"
                    else if (f.deleteRecursively()) "deleted ${f.path}" else "failed ${f.path}"
                })
            }
        },
        object : Tool("archive_create", ToolGroup.Files, Risk.write, false, "Zip files/folders into an archive.", {
            strings("paths", "Inputs", required = true)
            string("output", "Output .zip path", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val out = resolve(args.reqStr("output"))
                out.parentFile?.mkdirs()
                ZipOutputStream(out.outputStream().buffered()).use { zip ->
                    args.strs("paths").map(::resolve).forEach { src ->
                        val base = src.parentFile!!
                        src.walkTopDown().filter { it.isFile }.forEach { f ->
                            zip.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
                ToolResult.text("Created ${out.describe()}")
            }
        },
        object : Tool("archive_extract", ToolGroup.Files, Risk.write, false, "Extract a .zip archive.", {
            string("archive", "Zip path", required = true)
            string("dest", "Destination folder, default next to the archive")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext) = withContext(Dispatchers.IO) {
                need()
                val zipFile = resolve(args.reqStr("archive"))
                val dest = resolve(args.str("dest") ?: File(zipFile.parentFile, zipFile.nameWithoutExtension).path).apply { mkdirs() }
                var n = 0
                ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { e ->
                        val out = File(dest, e.name).canonicalFile
                        if (!out.path.startsWith(dest.canonicalPath)) return@forEach
                        if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) }; n++ }
                    }
                }
                ToolResult.text("Extracted $n files to ${dest.path}")
            }
        },
    )
}
