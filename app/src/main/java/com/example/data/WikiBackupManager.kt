package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@JsonClass(generateAdapter = true)
data class BackupData(
    val pages: List<WikiPage>,
    val sources: List<RawSource>,
    val logs: List<ActivityLog>
)

object WikiBackupManager {
    private const val TAG = "WikiBackupManager"

    suspend fun exportToZipAndShare(
        context: Context,
        pages: List<WikiPage>,
        sources: List<RawSource>,
        logs: List<ActivityLog>,
        moshi: Moshi
    ) = withContext(Dispatchers.IO) {
        try {
            val cacheDir = context.cacheDir
            val zipFile = File(cacheDir, "personal_wiki_backup.zip")
            if (zipFile.exists()) {
                zipFile.delete()
            }

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // Write main JSON DB dump
                val backupData = BackupData(pages = pages, sources = sources, logs = logs)
                val jsonString = moshi.adapter(BackupData::class.java).indent("  ").toJson(backupData)

                zos.putNextEntry(ZipEntry("wiki_database_backup.json"))
                zos.write(jsonString.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // Write human-readable Obsidian-style markdown pages
                pages.forEach { page ->
                    val cleanTitle = page.title.replace("/", "_").replace("\\", "_")
                    val mdContent = """
                        ---
                        tags: ${page.tags}
                        last_updated: ${page.lastUpdated}
                        ---
                        # ${page.title}
                        
                        ${page.content}
                    """.trimIndent()
                    zos.putNextEntry(ZipEntry("Pages/$cleanTitle.md"))
                    zos.write(mdContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                // Write human-readable simple notes
                sources.forEach { source ->
                    val sdContent = """
                        ---
                        id: ${source.id}
                        timestamp: ${source.timestamp}
                        ---
                        ${source.content}
                    """.trimIndent()
                    zos.putNextEntry(ZipEntry("Notes/note_${source.id}.md"))
                    zos.write(sdContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }

            // Trigger sharesheet via main thread context launcher
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zipFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Share Personal Wiki ZIP")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to export zip", e)
        }
    }

    suspend fun importFromZip(
        context: Context,
        uri: Uri,
        moshi: Moshi,
        dao: WikiDao
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val contentResolver = context.contentResolver
            val inputStream: InputStream = contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open source URI ZIP archive.")

            var backupData: BackupData? = null

            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "wiki_database_backup.json") {
                        val jsonBytes = zis.readBytes()
                        val jsonString = String(jsonBytes, Charsets.UTF_8)
                        backupData = moshi.adapter(BackupData::class.java).fromJson(jsonString)
                        break
                    }
                    entry = zis.nextEntry
                }
            }

            if (backupData == null) {
                throw Exception("Could not find a valid database backup entry inside chosen ZIP archive.")
            }

            val data = backupData!!

            // Run database clean slate operations and inserts in a single transaction-like safety flow
            dao.clearAllPages()
            dao.clearAllSources()
            dao.clearAllLogs()

            if (data.pages.isNotEmpty()) {
                dao.insertPages(data.pages)
            }
            if (data.sources.isNotEmpty()) {
                dao.insertSources(data.sources)
            }
            if (data.logs.isNotEmpty()) {
                dao.insertLogs(data.logs)
            }

            dao.insertLog(
                ActivityLog(
                    type = "maintenance",
                    summary = "ZIP Wiki Import Successful",
                    detail = "Fully reconstructed database: restored ${data.pages.size} wiki pages, ${data.sources.size} notes, and ${data.logs.size} activity logs."
                )
            )

            "Success: Restored ${data.pages.size} pages and ${data.sources.size} raw sources."
        }
    }
}
