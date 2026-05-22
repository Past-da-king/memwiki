package com.example.data

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Models list response (fetched live from Gemini API; never hardcoded) ---

@JsonClass(generateAdapter = true)
data class GeminiModelListResponse(
    @Json(name = "models") val models: List<GeminiModelInfo>?
)

@JsonClass(generateAdapter = true)
data class GeminiModelInfo(
    @Json(name = "name") val name: String, // e.g. "models/gemini-2.5-flash-lite"
    @Json(name = "displayName") val displayName: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "supportedGenerationMethods") val supportedGenerationMethods: List<String>? = null,
    @Json(name = "inputTokenLimit") val inputTokenLimit: Int? = null,
    @Json(name = "outputTokenLimit") val outputTokenLimit: Int? = null
) {
    // Strip the "models/" prefix to get the bare model ID we send into the generateContent path.
    val id: String get() = name.removePrefix("models/")
}

// --- Gemini REST request/response models ---

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
    @Json(name = "tools") val tools: List<GeminiTool>? = null
)

// Grounding tools — when the user drops a URL into a note we want the model to actually
// fetch the URL (urlContext) and optionally search the web (googleSearch) for related context.
// Both tools take empty config objects in the REST API — represented here as empty maps so
// Moshi serialises them as {} rather than omitting the field.
@JsonClass(generateAdapter = true)
data class GeminiTool(
    @Json(name = "urlContext") val urlContext: Map<String, String>? = null,
    @Json(name = "googleSearch") val googleSearch: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String? = null,
    @Json(name = "inlineData") val inlineData: GeminiInlineData? = null,
    // Gemini 2.5+ "thinking" responses set this true on parts that are the model's chain of thought
    // rather than its final answer. Lets the UI render thinking differently from the answer.
    @Json(name = "thought") val thought: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @Json(name = "mimeType") val mimeType: String,
    @Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "responseMimeType") val responseMimeType: String? = null,
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "maxOutputTokens") val maxOutputTokens: Int? = null,
    @Json(name = "thinkingConfig") val thinkingConfig: GeminiThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class GeminiThinkingConfig(
    // -1 = dynamic (model decides). 0 = disable. Positive = max thinking tokens.
    @Json(name = "thinkingBudget") val thinkingBudget: Int? = null,
    // true → include the model's chain-of-thought as separate parts (with thought=true) in the response stream.
    @Json(name = "includeThoughts") val includeThoughts: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>?
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent?
)

// --- Structured schemas extracted from LLM responses ---

@JsonClass(generateAdapter = true)
data class WikiUpdatePlan(
    @Json(name = "pagesToCreateOrUpdate") val pagesToCreateOrUpdate: List<PlanPage>,
    @Json(name = "pagesToDelete") val pagesToDelete: List<String>? = emptyList(),
    @Json(name = "logSummary") val logSummary: String,
    @Json(name = "logDetail") val logDetail: String,
    @Json(name = "remindersToCreate") val remindersToCreate: List<PlanReminder>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class PlanPage(
    @Json(name = "title") val title: String,
    @Json(name = "content") val content: String,
    @Json(name = "tags") val tags: String
)

@JsonClass(generateAdapter = true)
data class PlanReminder(
    @Json(name = "title") val title: String,
    @Json(name = "category") val category: String, // "Reminder", "Task", "Event"
    @Json(name = "dateText") val dateText: String, // "Tomorrow", "Next Friday at 10am", "2026-05-23" etc.
    @Json(name = "description") val description: String // Action detail/context
)

// Router pass — model sees only page titles+tags index and decides which pages this note touches.
@JsonClass(generateAdapter = true)
data class WikiRouterPlan(
    @Json(name = "pagesToUpdate") val pagesToUpdate: List<String>? = emptyList(),
    @Json(name = "pagesToCreate") val pagesToCreate: List<RouterPageStub>? = emptyList(),
    @Json(name = "reasoning") val reasoning: String? = null
)

@JsonClass(generateAdapter = true)
data class RouterPageStub(
    @Json(name = "title") val title: String,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class WikiLintReport(
    @Json(name = "issues") val issues: List<WikiLintIssue>
)

// All fields tolerant of missing/null values — Gemini occasionally drops a key (e.g. no severity
// supplied). Defaults keep the parser from blowing up on otherwise-valid responses.
@JsonClass(generateAdapter = true)
data class WikiLintIssue(
    @Json(name = "type") val type: String? = "issue",
    @Json(name = "severity") val severity: String? = "medium",
    @Json(name = "summary") val summary: String? = "",
    @Json(name = "detail") val detail: String? = "",
    @Json(name = "suggestedAction") val suggestedAction: String? = ""
)

// --- Core Retrofit API Client ---

interface GeminiApiService {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse

    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): GeminiModelListResponse
}

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Bumped read timeout to 5 min — streamGenerateContent SSE responses can be long-running.
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val apiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    // Helper to extract JSON updates from Gemini
    fun getMoshiInstance() = moshi
}
