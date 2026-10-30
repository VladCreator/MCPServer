package com.vlad.mcpserver

import android.content.Context
import android.os.Environment
import com.vlad.mcpserver.mcp.RunReports
import com.vlad.mcpserver.mcp.RunStore
import com.vlad.mcpserver.mcp.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.UUID

internal sealed interface EvidenceSource {
    data class StoredFile(val file: File) : EvidenceSource
    data class Artifact(val runId: String, val artifactId: String) : EvidenceSource
    data class RunReport(val run: JsonObject, val format: String) : EvidenceSource
}

internal data class QaEvidence(
    val id: String, val name: String, val mime: String, val created: Long,
    val origin: String, val source: EvidenceSource
)

internal class EvidenceFiles(private val roots: List<File>) {
    fun list(): List<QaEvidence> = roots.flatMap { root ->
        if (!root.exists()) return@flatMap emptyList()
        check(root.isDirectory) { "Evidence location is not a directory: ${root.name}" }
        val files = checkNotNull(root.listFiles()) { "Unable to list evidence: ${root.name}" }
        files.filter { it.isFile && permitted(it) && mime(it.name) != null }.map { file ->
            QaEvidence(file.canonicalPath, file.name, checkNotNull(mime(file.name)), file.lastModified(),
                root.name, EvidenceSource.StoredFile(file))
        }
    }.distinctBy { it.id }

    fun checked(file: File): File {
        require(permitted(file) && file.isFile && mime(file.name) != null) {
            "Evidence was removed or is outside the approved evidence folders."
        }
        return file.canonicalFile
    }

    private fun permitted(file: File): Boolean = roots.any {
        file.canonicalFile.parentFile == it.canonicalFile
    }

    companion object {
        fun mime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "md" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "pdf" -> "application/pdf"
            else -> null
        }

        fun extension(mime: String): String? = when (mime) {
            "text/markdown" -> "md"
            "text/plain" -> "txt"
            "application/json" -> "json"
            "application/xml", "text/xml" -> "xml"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "application/pdf" -> "pdf"
            else -> null
        }
    }
}

internal class QaEvidenceRepository(context: Context) {
    private val context = context.applicationContext
    private val files = EvidenceFiles(buildList {
        add(File(context.filesDir, "qa/reports"))
        add(File(context.filesDir, "qa/evidence"))
        add(File(context.filesDir, "qa/baselines"))
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let(::add)
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let(::add)
    })
    private val store get() = RunStore.get(context)
    private val shareDir get() = File(context.cacheDir, "qa-share")

    fun list(): List<QaEvidence> {
        cleanShares()
        val legacy = files.list() + checkNotNull(context.filesDir.listFiles()) { "Unable to list legacy camera evidence." }
            .filter { it.isFile && Regex("mcp_[0-9]+\\.jpg").matches(it.name) &&
                it.canonicalFile.parentFile == context.filesDir.canonicalFile }
            .map { QaEvidence(it.canonicalPath, it.name, "image/jpeg", it.lastModified(),
                "Camera", EvidenceSource.StoredFile(it)) }
        val reports = store.localRuns().flatMap { run ->
            val id = checkNotNull(run.string("run_id"))
            listOf("markdown" to "md", "json" to "json", "junit" to "xml").map { (format, ext) ->
                QaEvidence("run:$id:$format", "run-$id.$ext",
                    if (format == "junit") "application/xml" else if (format == "json") "application/json" else "text/markdown",
                    run["created_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    "Run ${run.string("status")}", EvidenceSource.RunReport(run, format))
            }
        }
        val artifacts = store.localArtifacts().mapNotNull { artifact ->
            val ext = EvidenceFiles.extension(artifact.mime) ?: return@mapNotNull null
            QaEvidence("artifact:${artifact.id}", "${artifact.id}.$ext", artifact.mime,
                artifact.created, "Run ${artifact.runId}", EvidenceSource.Artifact(artifact.runId, artifact.id))
        }
        return (legacy + reports + artifacts).sortedByDescending { it.created }
    }

    fun stage(item: QaEvidence): File {
        check(shareDir.isDirectory || shareDir.mkdirs()) { "Unable to create evidence share folder." }
        val target = File(shareDir, "${UUID.randomUUID()}-${item.name}")
        try {
            when (val source = item.source) {
                is EvidenceSource.StoredFile -> {
                    val file = if (source.file.parentFile?.canonicalFile == context.filesDir.canonicalFile &&
                        Regex("mcp_[0-9]+\\.jpg").matches(source.file.name)) {
                        require(source.file.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Invalid camera evidence." }
                        source.file
                    } else files.checked(source.file)
                    require(file.length() <= MAX_SHARE_BYTES) { "Evidence exceeds the 256 MiB sharing limit." }
                    file.inputStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_SHARE_BYTES) { "Evidence exceeds the 256 MiB sharing limit." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                is EvidenceSource.Artifact -> {
                    val artifact = checkNotNull(store.readArtifact(source.runId, source.artifactId)) {
                        "This artifact has expired or was removed. Refresh QA Evidence."
                    }
                    check(artifact.first == item.mime) { "Artifact type changed. Refresh QA Evidence." }
                    target.writeBytes(artifact.second)
                }
                is EvidenceSource.RunReport -> target.writeText(RunReports.export(source.run, source.format).second)
            }
            return target
        } catch (error: Exception) {
            if (target.exists() && !target.delete()) error.addSuppressed(IllegalStateException("Unable to remove partial evidence copy."))
            throw error
        }
    }

    private fun cleanShares() {
        if (!shareDir.exists()) return
        checkNotNull(shareDir.listFiles()) { "Unable to inspect shared evidence copies." }.forEach {
            if (it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L) {
                check(it.delete()) { "Unable to remove expired evidence copy." }
            }
        }
    }

    companion object {
        private const val MAX_SHARE_BYTES = 256L * 1024 * 1024
    }
}
