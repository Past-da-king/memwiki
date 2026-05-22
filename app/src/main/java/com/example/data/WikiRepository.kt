package com.example.data

import android.util.Log
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// One emitted piece of a streamed Gemini response. Either thinking-of-thought text or
// the model's actual answer text. The UI shows them with different styling.
data class StreamChunk(val text: String, val isThinking: Boolean)

class WikiRepository(
    private val context: Context,
    private val dao: WikiDao
) {

    val allPages: Flow<List<WikiPage>> = dao.getAllPagesFlow()
    val allSources: Flow<List<RawSource>> = dao.getAllSourcesFlow()
    val allLogs: Flow<List<ActivityLog>> = dao.getAllLogsFlow()
    val allReminders: Flow<List<Reminder>> = dao.getAllRemindersFlow()
    val activeReminders: Flow<List<Reminder>> = dao.getActiveRemindersFlow()

    private val moshi: Moshi = GeminiClient.getMoshiInstance()

    suspend fun getPage(title: String): WikiPage? = withContext(Dispatchers.IO) {
        dao.getPageByTitle(title)
    }

    suspend fun clearWiki() = withContext(Dispatchers.IO) {
        dao.clearAllPages()
        dao.clearAllReminders()
        dao.insertLog(
            ActivityLog(
                type = "maintenance",
                summary = "Cleared all Wiki pages and Reminders",
                detail = "All compiled entity/concept pages and automated remind/task listings have been reset."
            )
        )
    }

    suspend fun clearReminders() = withContext(Dispatchers.IO) {
        dao.clearAllReminders()
    }

    suspend fun toggleReminder(id: Int, isCompleted: Boolean) = withContext(Dispatchers.IO) {
        dao.updateReminderStatus(id, isCompleted)
    }

    suspend fun deleteReminder(id: Int) = withContext(Dispatchers.IO) {
        dao.deleteReminderById(id)
    }

    // Below this number of pages the wiki is small enough that one-pass ingest is cheaper
    // than two API round-trips. Above it, two-pass retrieval pays off.
    private val TWO_PASS_THRESHOLD = 6

    // Gemini intermittently throws 503 (overloaded) and 429 (rate-limited). Auto-retry these
    // with exponential backoff so the user doesn't see a transient hiccup as a hard failure.
    private suspend fun <T> withRetry(
        attempts: Int = 4,
        initialDelayMs: Long = 1500,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        var delayMs = initialDelayMs
        repeat(attempts) { attemptIndex ->
            try {
                return block()
            } catch (e: Exception) {
                val isTransient = when (e) {
                    is retrofit2.HttpException -> e.code() == 503 || e.code() == 429 || e.code() == 500 || e.code() == 502 || e.code() == 504
                    else -> e.message?.contains("HTTP 503") == true ||
                            e.message?.contains("HTTP 429") == true ||
                            e.message?.contains("HTTP 500") == true ||
                            e.message?.contains("HTTP 502") == true ||
                            e.message?.contains("HTTP 504") == true
                }
                lastError = e
                if (!isTransient || attemptIndex == attempts - 1) throw e
                Log.w("WikiRepository", "Transient Gemini error (retrying in ${delayMs}ms): ${e.message?.take(150)}")
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
        }
        throw lastError ?: Exception("Retry loop exited without an error")
    }

    private fun resolveKey(apiKey: String): String? {
        val k = if (apiKey.isNotBlank()) apiKey.trim() else BuildConfig.GEMINI_API_KEY
        return if (k == "MY_GEMINI_API_KEY" || k.isBlank()) null else k
    }

    // Matches http(s) URLs anywhere in a string. Used to auto-enable the URL context tool
    // so the model can actually fetch and read the page contents into the note.
    private val urlRegex = Regex("""https?://[^\s)\]>"']+""", RegexOption.IGNORE_CASE)

    private fun containsUrl(text: String): Boolean = urlRegex.containsMatchIn(text)

    private fun extractUrls(text: String): List<String> = urlRegex.findAll(text).map { it.value }.toList()

    // Build the grounding tools list for a request. When URLs are present we attach BOTH
    // urlContext (fetch the URL directly) and googleSearch (general web grounding for context
    // around what the URL discusses). Returns null when no URL → no tools attached → cheaper call.
    private fun groundingToolsFor(text: String): List<GeminiTool>? {
        if (!containsUrl(text)) return null
        return listOf(
            GeminiTool(urlContext = emptyMap()),
            GeminiTool(googleSearch = emptyMap())
        )
    }

    // Hits streamGenerateContent with alt=sse, parses each `data: {json}` line as it arrives,
    // emits each part to onChunk (with isThinking flag), and returns the accumulated answer text
    // (only non-thinking parts) — which the caller parses as the final JSON.
    private suspend fun streamGenerateContent(
        apiKey: String,
        modelId: String,
        request: GeminiRequest,
        onChunk: suspend (StreamChunk) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val moshi = GeminiClient.getMoshiInstance()
        val requestAdapter = moshi.adapter(GeminiRequest::class.java)
        val responseAdapter = moshi.adapter(GeminiResponse::class.java)
        val bodyJson = requestAdapter.toJson(request)
        val httpReq = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse&key=$apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        // Wrap execute() with retry — if Gemini returns 503/429, retry with backoff.
        val resp = withRetry {
            val r = GeminiClient.okHttpClient.newCall(httpReq).execute()
            if (r.code == 503 || r.code == 429 || r.code == 500 || r.code == 502 || r.code == 504) {
                val errBody = r.body?.string().orEmpty()
                r.close()
                throw Exception("HTTP ${r.code} from Gemini stream: ${errBody.take(500)}")
            }
            r
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                val errBody = r.body?.string().orEmpty()
                throw Exception("HTTP ${r.code} from Gemini stream: ${errBody.take(500)}")
            }
            val accumulator = StringBuilder()
            val source = r.body?.source() ?: throw Exception("Empty SSE response body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val streamResp = try {
                    responseAdapter.fromJson(payload)
                } catch (e: Exception) {
                    Log.w("WikiRepository", "Skipping unparseable SSE chunk: ${payload.take(200)}", e)
                    null
                } ?: continue
                streamResp.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                    val text = part.text ?: return@forEach
                    val isThinking = part.thought == true
                    if (!isThinking) accumulator.append(text)
                    onChunk(StreamChunk(text = text, isThinking = isThinking))
                }
            }
            accumulator.toString()
        }
    }

    // Fetch the list of generateContent-capable models the user's API key can access.
    suspend fun listAvailableModels(apiKey: String): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        val key = resolveKey(apiKey)
            ?: return@withContext Result.failure(Exception("Gemini API key is not configured."))
        try {
            val resp = GeminiClient.apiService.listModels(key)
            val supported = (resp.models ?: emptyList())
                .filter { it.supportedGenerationMethods?.contains("generateContent") == true }
                .sortedBy { it.id }
            Result.success(supported)
        } catch (e: Exception) {
            Log.e("WikiRepository", "Model list error", e)
            Result.failure(e)
        }
    }

    // --- operation 1: INGEST (compiles note into wiki + extracts tasks/events/reminders) ---
    // Now uses streamGenerateContent + SSE so the UI can show thinking/answer text live.
    suspend fun ingestNote(
        apiKey: String,
        modelId: String,
        content: String,
        audioPath: String? = null,
        imagePaths: List<String> = emptyList(),
        onChunk: suspend (StreamChunk) -> Unit = {}
    ): Result<WikiUpdatePlan> = withContext(Dispatchers.IO) {
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty() && audioPath == null && imagePaths.isEmpty()) {
            return@withContext Result.failure(Exception("Note is empty (no text, audio, or images selected)"))
        }

        val finalContentStr = if (trimmedContent.isEmpty()) {
            "[Draft note generated from attached file assets]"
        } else {
            trimmedContent
        }

        val resolvedApiKey = resolveKey(apiKey)
            ?: return@withContext Result.failure(Exception("Gemini API key is not configured. Please add or import yours in the settings top sheet."))
        if (modelId.isBlank()) {
            return@withContext Result.failure(Exception("No model selected. Please pick a model in Settings."))
        }

        try {
            // 1. Persist the raw note source as immutable source of truth
            val insertedId = dao.insertSource(
                RawSource(
                    content = finalContentStr,
                    audioPath = audioPath,
                    imagePaths = RawSource.joinPaths(imagePaths)
                )
            )

            // 2. Fetch existing wiki pages — but only send the SUBSET the model actually needs.
            // Strategy: small wiki → send all pages (cheap). Large wiki → run a router pass first
            // that sees only titles + tags, decides which pages this note touches, then we load only those.
            val allPages = dao.getAllPages()
            val pagesForEditor: List<WikiPage> = if (allPages.size <= TWO_PASS_THRESHOLD) {
                allPages
            } else {
                routePagesForNote(
                    apiKey = resolvedApiKey,
                    modelId = modelId,
                    note = finalContentStr,
                    allPages = allPages
                )
            }
            val existingContext = if (pagesForEditor.isEmpty()) {
                "(The Wiki is currently completely empty. No pages exist yet.)"
            } else {
                pagesForEditor.joinToString("\n\n") { page ->
                    "=== PAGE: ${page.title} ===\nTags: ${page.tags}\nContent:\n${page.content}"
                }
            }
            // Always give the model the FULL title+tag index so it can write [[links]] to pages
            // it isn't editing this turn — keeps the graph fully linked without sending full content.
            val fullIndex = if (allPages.isEmpty()) {
                "(no pages)"
            } else {
                allPages.joinToString("\n") { "- ${it.title} [${it.tags}]" }
            }

            // 3. Assemble compile instructions
            val imageAttachmentPrompt = if (imagePaths.isNotEmpty()) {
                val markdownTags = imagePaths.joinToString("\n") { "`![Image](${it})`" }
                "\n\n5. CRITICAL DECORATIVE DIRECTIVE:\n${imagePaths.size} image file(s) are attached to this draft note. To render them correctly inside the wiki page(s) you build or update, you MUST insert each one as a markdown image tag, using EXACTLY these paths:\n$markdownTags\nEmbed each tag inside the most relevant page(s). Each attached image must appear in at least one page; the user wants visual records preserved."
            } else ""

            val systemDoc = """
                You are a meticulous personal LLM Wiki database maintainer. Your job is to compile raw incoming notes into a structured, permanently interlinked personal wiki.
                
                Analyze the new incoming note against the existing wiki pages. Decide which pages to CREATE and which existing pages to UPDATE to seamlessly integrate this new information. Keep content consolidated, detailed, and clean. Do not duplicate information.
                
                You are a multimodal AI. Beside textual input, you may also receive actual image and audio attachments coupled with the note.
                1. If an image file is attached (as inlineData in the contents), you can analyze its content. Any information in the image should be fully digested and integrated into the appropriate wiki pages.
                2. If an audio file is attached (as inlineData in the contents), you MUST listen to and transcribe it, extracting any insights or facts spoken in it, and integrate them fully into the wiki pages.
                3. If the note contains http(s) URLs, you have the urlContext and googleSearch tools available — USE THEM. Fetch every URL the user dropped, read the page content, and integrate the relevant facts into the wiki pages. Cite the URL inside the page content so it's traceable.
                
                CRITICAL DIRECTIVES:
                1. Use double brackets [[Page Title]] to reference OTHER pages in the wiki (Obsidian wiki style). For example, if you mention Bob, and Bob has a page or should have a page, write [[Bob]]. Ensure any major entities, projects, hobbies, or concepts are linked.
                2. Do not insert double brackets pointing to the current page itself.
                3. Update relevant tags (tags should be short, comma-separated tokens like 'friend, medical, finance, tech').
                
                4. NEW REMINDERS / TASKS / EVENTS EXTRACTION:
                Scan the incoming note, transcribed audio text, or image attachments for ANY mentioned action item, task, deadline, schedule, calendar event, appointment, or reminder (for instance: "tomorrow I have to do this", "remind me to wash the car", or custom event flyers).
                If any are found, you MUST populate the "remindersToCreate" JSON list.
                Each item inside "remindersToCreate" must strictly use this JSON format:
                {
                  "title": "Need to do X/Call Y/Event Z",
                  "category": "Reminder" | "Task" | "Event",
                  "dateText": "Tomorrow at 9 AM" or "Friday" or "2026-05-23" or whatever time reference found,
                  "description": "Short explanation of the reminder/task or relevant context from the note"
                }
                
                5. Return structured updates in JSON. You MUST return a valid JSON matching this schema:
                {
                  "pagesToCreateOrUpdate": [
                    {
                      "title": "Unique Page Title",
                      "content": "Fully synthesized markdown-format text for this page. Interweave existing page information with the new insights. Keep it rich and beautifully structured.",
                      "tags": "friend, project"
                    }
                  ],
                  "pagesToDelete": ["Old title to delete if renaming occurred, otherwise keep empty array"],
                  "logSummary": "Brief visual summary like 'Ingested notes about Bob's SolarGrid project'",
                  "logDetail": "Bulleted list of updates: Created Bob, updated SolarGrid, added cross-linking.",
                  "remindersToCreate": []
                }$imageAttachmentPrompt
                
                Do NOT output markdown wrapper codeblocks (like ```json). Return ONLY raw JSON text.
            """.trimIndent()

            val userText = """
                FULL WIKI INDEX (every page title with its tags — use these for [[links]] even to pages whose body you don't see):
                $fullIndex

                =================================================
                RELEVANT PAGE CONTENTS (subset selected for this update):
                $existingContext

                =================================================
                NEW INCOMING NOTE TO INGEST AND COMPILE:
                $finalContentStr
            """.trimIndent()

            val parts = mutableListOf<GeminiPart>()
            parts.add(GeminiPart(text = userText))

            // Attach every image inline. Order is preserved so the model can match each image to
            // the matching markdown tag in the prompt.
            for (imagePath in imagePaths) {
                try {
                    val file = java.io.File(imagePath)
                    if (!file.exists()) {
                        Log.w("WikiRepository", "Image file does not exist at path: $imagePath")
                        continue
                    }
                    val bytes = file.readBytes()
                    val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val ext = file.extension.lowercase()
                    val mime = when (ext) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/jpeg"
                    }
                    parts.add(GeminiPart(inlineData = GeminiInlineData(mimeType = mime, data = base64Data)))
                    Log.d("WikiRepository", "Attached image file: $imagePath as $mime to Gemini request")
                } catch (e: Exception) {
                    Log.e("WikiRepository", "Failed to attach image to Gemini request", e)
                }
            }

            // Check if audioPath is provided, load and encode to Base64
            if (audioPath != null) {
                try {
                    val file = java.io.File(audioPath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val ext = file.extension.lowercase()
                        val mime = when (ext) {
                            "wav" -> "audio/wav"
                            "mp3" -> "audio/mp3"
                            else -> "audio/mp4" // M4A/AAC format uses audio/mp4/m4a
                        }
                        parts.add(GeminiPart(inlineData = GeminiInlineData(mimeType = mime, data = base64Data)))
                        Log.d("WikiRepository", "Attached audio file: $audioPath as $mime to Gemini request")
                    } else {
                        Log.w("WikiRepository", "Audio file does not exist at path: $audioPath")
                    }
                } catch (e: Exception) {
                    Log.e("WikiRepository", "Failed to attach audio to Gemini request", e)
                }
            }

            val groundingTools = groundingToolsFor(finalContentStr)
            if (groundingTools != null) {
                val urls = extractUrls(finalContentStr)
                Log.d("WikiRepository", "URL(s) detected in note — enabling urlContext + googleSearch grounding: $urls")
            }

            // Gemini rejects responseMimeType=application/json when tools (urlContext/googleSearch)
            // are attached. When grounding tools are in play, drop the JSON mime; the system
            // prompt still insists on raw JSON output and cleanJsonString below strips any
            // markdown fences the model might add.
            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = parts)),
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemDoc))),
                generationConfig = GeminiGenerationConfig(
                    responseMimeType = if (groundingTools == null) "application/json" else null,
                    temperature = 0.2f,
                    maxOutputTokens = 60000,
                    thinkingConfig = GeminiThinkingConfig(includeThoughts = true)
                ),
                tools = groundingTools
            )

            // Stream the response via SSE so we can emit chunks (thinking + answer) to the UI live.
            // The full answer text is accumulated for JSON parsing once the stream completes.
            val jsonText = streamGenerateContent(resolvedApiKey, modelId, request, onChunk)
                .ifBlank { throw Exception("Empty response from Gemini") }

            // Clean json response if required
            val cleanedJson = cleanJsonString(jsonText)

            Log.d("WikiRepository", "Gemini response: $cleanedJson")

            // 5. Parse update plan
            val adapter = moshi.adapter(WikiUpdatePlan::class.java)
            val plan = adapter.fromJson(cleanedJson) ?: throw Exception("Failed to parse Update Plan JSON")

            // 6. Execute update plan in the DB
            plan.pagesToCreateOrUpdate.forEach { planPage ->
                dao.insertPage(
                    WikiPage(
                        title = planPage.title.trim(),
                        content = planPage.content,
                        tags = planPage.tags.trim(),
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }

            // Handle deletions/renames
            plan.pagesToDelete?.forEach { titleToDelete ->
                if (titleToDelete.isNotBlank()) {
                    dao.deletePageByTitle(titleToDelete.trim())
                }
            }

            // Handle reminders to create
            plan.remindersToCreate?.forEach { planRem ->
                if (planRem.title.isNotBlank()) {
                    val rem = Reminder(
                        noteId = insertedId.toInt(),
                        title = planRem.title.trim(),
                        category = planRem.category.trim().ifBlank { "Reminder" },
                        dateText = planRem.dateText.trim().ifBlank { "Upcoming" },
                        description = planRem.description.trim(),
                        attachedImagePath = imagePaths.firstOrNull()
                    )
                    dao.insertReminder(rem)

                    // Send automatic system notification right away on user's device!
                    try {
                        triggerSystemNotification(
                            title = "Reminder Added: ${planRem.title}",
                            body = "[${planRem.category}] scheduled for ${planRem.dateText}"
                        )
                    } catch (e: Exception) {
                        Log.e("WikiRepository", "Failed to post instant notification reminder", e)
                    }
                }
            }

            // Save Activity Log
            dao.insertLog(
                ActivityLog(
                    type = "ingest",
                    summary = plan.logSummary,
                    detail = plan.logDetail
                )
            )

            Result.success(plan)
        } catch (e: Exception) {
            Log.e("WikiRepository", "Ingestion error", e)
            Result.failure(e)
        }
    }

    // Router pass — sends only page titles+tags, asks model which pages this note actually touches.
    // Cheap because the prompt scales with N titles, not N full page bodies.
    private suspend fun routePagesForNote(
        apiKey: String,
        modelId: String,
        note: String,
        allPages: List<WikiPage>
    ): List<WikiPage> {
        val index = allPages.joinToString("\n") { "- ${it.title} [${it.tags}]" }
        val routerSystem = """
            You are a routing assistant for a personal wiki. You are given the new incoming note and the FULL INDEX of existing page titles and tags. Your job is to identify which existing pages should be updated to absorb the note, and which brand-new pages should be created.

            Return ONLY raw JSON matching this schema (no markdown fences):
            {
              "pagesToUpdate": ["Existing Title 1", "Existing Title 2"],
              "pagesToCreate": [ { "title": "New Page Name", "reason": "Why this should be a new page" } ],
              "reasoning": "Optional one-liner"
            }

            Rules:
            - pagesToUpdate MUST contain only titles that appear verbatim in the index.
            - If nothing existing applies, return empty pagesToUpdate.
            - Be conservative — only list pages that are clearly relevant to the note.
        """.trimIndent()
        val routerUser = """
            EXISTING WIKI INDEX (title [tags]):
            $index

            =================================================
            NEW INCOMING NOTE:
            $note
        """.trimIndent()
        return try {
            val req = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = routerUser)))),
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = routerSystem))),
                generationConfig = GeminiGenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.1f,
                    maxOutputTokens = 60000
                )
            )
            val resp = withRetry { GeminiClient.apiService.generateContent(modelId, apiKey, req) }
            val jsonText = resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText.isNullOrBlank()) return allPages // fallback: send everything
            val plan = moshi.adapter(WikiRouterPlan::class.java).fromJson(cleanJsonString(jsonText))
                ?: return allPages
            val titleSet = (plan.pagesToUpdate ?: emptyList()).map { it.trim() }.toSet()
            val selected = allPages.filter { it.title in titleSet }
            Log.d("WikiRepository", "Router selected ${selected.size}/${allPages.size} pages: ${selected.map { it.title }}")
            selected.ifEmpty { emptyList() }
        } catch (e: Exception) {
            Log.e("WikiRepository", "Router pass failed — falling back to full context", e)
            allPages
        }
    }

    // --- operation 2: CHAT QUERY (queries the wiki with contextual grounding) ---
    suspend fun queryWiki(apiKey: String, modelId: String, userQuery: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedQuery = userQuery.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.failure(Exception("Query is empty"))
        }

        val resolvedApiKey = resolveKey(apiKey)
            ?: return@withContext Result.failure(Exception("Gemini API Key is not configured. Please add or import yours in the settings bottom sheet."))
        if (modelId.isBlank()) {
            return@withContext Result.failure(Exception("No model selected. Please pick a model in Settings."))
        }

        try {
            val allPages = dao.getAllPages()
            // Two-pass: ask the model which pages are relevant to this question, then send only those bodies.
            val pagesForAnswer = if (allPages.size <= TWO_PASS_THRESHOLD) {
                allPages
            } else {
                routePagesForNote(
                    apiKey = resolvedApiKey,
                    modelId = modelId,
                    note = trimmedQuery,
                    allPages = allPages
                ).ifEmpty { allPages.take(TWO_PASS_THRESHOLD) }
            }
            val existingContext = pagesForAnswer.joinToString("\n\n") { page ->
                "=== PAGE: ${page.title} ===\nTags: ${page.tags}\nContent:\n${page.content}"
            }
            val fullIndex = if (allPages.isEmpty()) "(no pages)" else allPages.joinToString("\n") { "- ${it.title}" }

            val systemDoc = """
                You are 'WikiScout', a friendly personal research bot.
                Your job is to explore and answer inquiries about the user's personal wiki knowledge base.

                You MUST synthesize answers drawing exclusively from the wiki context provided (and any general reasoning derived directly from it).

                CRITICAL STYLING DIRECTIVES:
                1. When you reference topics, concepts, or entities that are documented pages in the wiki, you MUST format them as Obsidian wiki links, e.g. [[Bob]] or [[SolarGrid]]. This makes the links clickable in our frontend layout.
                2. If a relevant page contains markdown image tags like `![Image](/data/...)`, and the image is helpful context for your answer, INCLUDE the exact same markdown image tag verbatim in your reply. The frontend renders them inline and the user can tap to fullscreen. Use the same `![label](path)` syntax with the SAME path string as in the source page.
                3. If the user asks about something missing, indicate politely that there are no notes about it yet. Keep answer formatting bold, structured, and pleasant.
            """.trimIndent()

            val userText = """
                FULL WIKI INDEX (all known page titles — use these for [[links]]):
                $fullIndex

                =================================================
                RELEVANT PAGE CONTENTS:
                $existingContext

                =================================================
                QUESTION:
                $trimmedQuery
            """.trimIndent()

            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = userText)))),
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemDoc))),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.5f,
                    maxOutputTokens = 60000
                ),
                tools = groundingToolsFor(trimmedQuery)
            )

            val response = withRetry { GeminiClient.apiService.generateContent(modelId, resolvedApiKey, request) }
            val answer = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: throw Exception("Empty response from Gemini")

            // Log query event
            dao.insertLog(
                ActivityLog(
                    type = "query",
                    summary = "Searched: \"$trimmedQuery\"",
                    detail = "Answered using ${pagesForAnswer.size} of ${allPages.size} pages (router-selected)."
                )
            )

            Result.success(answer)
        } catch (e: Exception) {
            Log.e("WikiRepository", "Query error", e)
            Result.failure(e)
        }
    }

    // --- operation 3: LINT (scans the database for inconsistencies and missing links) ---
    // Now streams thinking/answer chunks via onChunk so the UI can show live progress.
    suspend fun lintWiki(
        apiKey: String,
        modelId: String,
        onChunk: suspend (StreamChunk) -> Unit = {}
    ): Result<WikiLintReport> = withContext(Dispatchers.IO) {
        val resolvedApiKey = resolveKey(apiKey)
            ?: return@withContext Result.failure(Exception("Gemini API Key is not configured. Please add or import yours in the settings bottom sheet."))
        if (modelId.isBlank()) {
            return@withContext Result.failure(Exception("No model selected. Please pick a model in Settings."))
        }

        try {
            val existingPages = dao.getAllPages()
            if (existingPages.isEmpty()) {
                return@withContext Result.success(WikiLintReport(issues = emptyList()))
            }

            val existingContext = existingPages.joinToString("\n\n") { page ->
                "=== PAGE: ${page.title} ===\nTags: ${page.tags}\nContent:\n${page.content}"
            }

            val systemDoc = """
                You are a highly analytical database auditor. Your job is to run quality-assurance/health lint checks across the user's personal wiki.
                
                You must perform these core analyses:
                1. CONTRADICTIONS: Look for direct logical contradictions across pages (e.g., Page A says Bob works in Seattle; Page B says Bob works in Paris).
                2. UNLINKED MENTIONS: See if page Titles or Concepts are mentioned in text *without* being enclosed in [[Wiki Link]] style when they exist in the database.
                3. ORPHAN FILES / DEAD ENDS: Find references inside [[Double Brackets]] that point to a page that doesn't exist yet, or files with no links at all.
                
                Return a valid JSON matching this schema:
                {
                  "issues": [
                    {
                      "type": "contradiction" | "missing_link" | "missing_page",
                      "severity": "high" | "medium" | "low",
                      "summary": "Short headline",
                      "detail": "Comprehensive diagnostic details detailing what pages are affected and where",
                      "suggestedAction": "Suggested fix to execute"
                    }
                  ]
                }
                
                Do NOT write markdown wrap blocks (like ```json). Return ONLY raw JSON text.
            """.trimIndent()

            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = existingContext)))),
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemDoc))),
                generationConfig = GeminiGenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.1f,
                    maxOutputTokens = 60000,
                    thinkingConfig = GeminiThinkingConfig(includeThoughts = true)
                )
            )

            // Stream so the UI can render thinking/answer chunks live.
            val jsonText = streamGenerateContent(resolvedApiKey, modelId, request, onChunk)
                .ifBlank { throw Exception("No response from Gemini") }

            val cleanedJson = cleanJsonString(jsonText)
            Log.d("WikiRepository", "Lint Response: $cleanedJson")

            // Some lint runs still get clipped (model exceeds budget). Try parsing the response
            // as-is, and if it's truncated, salvage the issues that DID complete.
            val adapter = moshi.adapter(WikiLintReport::class.java)
            val report = try {
                adapter.fromJson(cleanedJson) ?: throw Exception("Failed to parse Lint Report JSON")
            } catch (e: Exception) {
                Log.w("WikiRepository", "Lint JSON parse failed (likely truncated). Trying recovery.", e)
                val recovered = recoverTruncatedLint(cleanedJson, adapter)
                    ?: throw Exception("Lint response was malformed: ${e.localizedMessage}")
                recovered
            }

            // Log diagnostic action
            dao.insertLog(
                ActivityLog(
                    type = "lint",
                    summary = "Diagnostics Audit Performed",
                    detail = "Scanned ${existingPages.size} pages. Uncovered ${report.issues.size} issues."
                )
            )

            Result.success(report)
        } catch (e: Exception) {
            Log.e("WikiRepository", "Lint error", e)
            Result.failure(e)
        }
    }

    // --- operation 4: APPLY LINT FIXES ---
    // Take a previously-generated lint report and ask the model to actually rewrite pages so
    // the issues are resolved. Returns a WikiUpdatePlan (same shape as ingest) and persists it.
    suspend fun applyLintFixes(
        apiKey: String,
        modelId: String,
        report: WikiLintReport,
        onChunk: suspend (StreamChunk) -> Unit = {}
    ): Result<WikiUpdatePlan> = withContext(Dispatchers.IO) {
        val resolvedApiKey = resolveKey(apiKey)
            ?: return@withContext Result.failure(Exception("Gemini API Key is not configured."))
        if (modelId.isBlank()) {
            return@withContext Result.failure(Exception("No model selected. Please pick a model in Settings."))
        }
        if (report.issues.isEmpty()) {
            return@withContext Result.failure(Exception("No audit issues to fix."))
        }

        try {
            val allPages = dao.getAllPages()
            val pagesText = allPages.joinToString("\n\n") { page ->
                "=== PAGE: ${page.title} ===\nTags: ${page.tags}\nContent:\n${page.content}"
            }
            val issuesText = report.issues.mapIndexed { idx, issue ->
                "${idx + 1}. [${issue.type ?: "issue"} · ${issue.severity ?: "medium"}] ${issue.summary.orEmpty()}\n   ${issue.detail.orEmpty()}\n   Suggested fix: ${issue.suggestedAction.orEmpty()}"
            }.joinToString("\n\n")

            val systemDoc = """
                You are a database surgeon. The user's wiki has been audited and these specific issues were found. Your job is to rewrite the affected pages so the issues are RESOLVED.

                Apply the suggested fixes literally where possible. Specifically:
                - For UNLINKED MENTIONS: rewrite the body of the source page to wrap the mention in [[Page Title]] brackets.
                - For ORPHAN / MISSING PAGE links: either create the missing page with at least one short stub paragraph and a couple of [[backlinks]] to where it's referenced, OR remove the broken link if it was a mistake.
                - For CONTRADICTIONS: reconcile the conflict. If you can't be certain which is correct, mark the contradiction in both pages with a short italic note rather than deleting either claim.

                Preserve unrelated page content verbatim — only change what's needed to fix the issue.

                Return ONLY raw JSON in this exact shape (no markdown fences):
                {
                  "pagesToCreateOrUpdate": [
                    { "title": "Page Title", "content": "Full rewritten markdown", "tags": "comma, separated" }
                  ],
                  "pagesToDelete": [],
                  "logSummary": "Brief one-line summary of the fix pass",
                  "logDetail": "Bulleted detail of what was changed",
                  "remindersToCreate": []
                }
            """.trimIndent()

            val userText = """
                EXISTING WIKI PAGES:
                $pagesText

                =================================================
                AUDIT ISSUES TO FIX:
                $issuesText
            """.trimIndent()

            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = userText)))),
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemDoc))),
                generationConfig = GeminiGenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.2f,
                    maxOutputTokens = 60000,
                    thinkingConfig = GeminiThinkingConfig(includeThoughts = true)
                )
            )

            val jsonText = streamGenerateContent(resolvedApiKey, modelId, request, onChunk)
                .ifBlank { throw Exception("Empty response from Gemini") }
            val cleanedJson = cleanJsonString(jsonText)

            val adapter = moshi.adapter(WikiUpdatePlan::class.java)
            val plan = adapter.fromJson(cleanedJson) ?: throw Exception("Failed to parse fix plan JSON")

            plan.pagesToCreateOrUpdate.forEach { planPage ->
                dao.insertPage(
                    WikiPage(
                        title = planPage.title.trim(),
                        content = planPage.content,
                        tags = planPage.tags.trim(),
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
            plan.pagesToDelete?.forEach { title ->
                if (title.isNotBlank()) dao.deletePageByTitle(title.trim())
            }

            dao.insertLog(
                ActivityLog(
                    type = "lint",
                    summary = plan.logSummary.ifBlank { "Applied audit fixes" },
                    detail = plan.logDetail.ifBlank { "Fixed ${report.issues.size} audit issue(s)." }
                )
            )

            Result.success(plan)
        } catch (e: Exception) {
            Log.e("WikiRepository", "Lint-fix error", e)
            Result.failure(e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wiki Reminders"
            val descriptionText = "Notifications for extracted wiki reminders and scheduled tasks"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("wiki_reminders_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun triggerSystemNotification(title: String, body: String) {
        try {
            createNotificationChannel()
            val notificationId = System.currentTimeMillis().toInt()

            val builder = NotificationCompat.Builder(context, "wiki_reminders_channel")
                .setSmallIcon(com.example.R.drawable.ic_launcher_mono)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)

            val notificationManager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("WikiRepository", "Notification permission POST_NOTIFICATIONS is not yet approved by user")
                }
            }
            notificationManager.notify(notificationId, builder.build())
            Log.d("WikiRepository", "System Notification Triggered: $title - $body")
        } catch (e: Exception) {
            Log.e("WikiRepository", "Could not trigger system notification", e)
        }
    }

    // Salvage a truncated lint JSON by trimming back to the last fully-closed `}` inside the
    // issues array and reconstructing a valid envelope. Returns null if no issues survive.
    private fun recoverTruncatedLint(
        cleaned: String,
        adapter: com.squareup.moshi.JsonAdapter<WikiLintReport>
    ): WikiLintReport? {
        val arrStart = cleaned.indexOf("\"issues\"")
        if (arrStart == -1) return null
        val bracketStart = cleaned.indexOf('[', arrStart)
        if (bracketStart == -1) return null
        // Walk objects inside the array; keep only those that finished cleanly.
        val good = StringBuilder("[")
        var depth = 0
        var inString = false
        var escape = false
        var objStart = -1
        var i = bracketStart + 1
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (escape) { escape = false; i++; continue }
            if (c == '\\' && inString) { escape = true; i++; continue }
            if (c == '"') { inString = !inString; i++; continue }
            if (!inString) {
                if (c == '{') {
                    if (depth == 0) objStart = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && objStart != -1) {
                        val obj = cleaned.substring(objStart, i + 1)
                        if (good.length > 1) good.append(',')
                        good.append(obj)
                        objStart = -1
                    }
                } else if (c == ']' && depth == 0) {
                    break
                }
            }
            i++
        }
        good.append(']')
        val rebuilt = """{"issues":$good}"""
        return try { adapter.fromJson(rebuilt) } catch (e: Exception) { null }
    }

    // Helper to extract JSON if LLM returned block markdown wrappers
    private fun cleanJsonString(input: String): String {
        var str = input.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json")
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```")
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```")
        }
        return str.trim()
    }
}
