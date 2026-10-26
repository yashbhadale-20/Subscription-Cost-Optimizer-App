package com.subscriptionoptimizer.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit description of the FastAPI routes the app uses. Paths are relative to the base URL.
 * User-scoped routes take the session's `Authorization: Bearer …` header; see [SessionManager].
 */
interface BackendApi {
    @GET("health")
    suspend fun health(): HealthDto

    @GET("health/ollama")
    suspend fun ollamaHealth(): OllamaHealthDto

    @POST("auth/session")
    suspend fun createSession(
        @Body body: SessionRequestDto,
        @Header("Authorization") existing: String? = null,
    ): SessionResponseDto

    @POST("compare")
    suspend fun compare(@Header("Authorization") auth: String, @Body body: CompareRequestDto): CompareResponseDto

    @POST("chat")
    suspend fun chat(@Header("Authorization") auth: String, @Body body: ChatRequestDto): ChatResponseDto

    @GET("conversations")
    suspend fun conversations(@Header("Authorization") auth: String): ConversationListDto

    @GET("conversations/{threadId}")
    suspend fun conversation(@Header("Authorization") auth: String, @Path("threadId") threadId: String): ConversationDto

    @DELETE("conversations/{threadId}")
    suspend fun deleteConversation(@Header("Authorization") auth: String, @Path("threadId") threadId: String)

    @GET("history")
    suspend fun history(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int = 0,
    ): HistoryListDto

    @GET("history/{id}")
    suspend fun historyItem(@Header("Authorization") auth: String, @Path("id") id: String): SavedComparisonDto

    @GET("history/{id}/trace")
    suspend fun trace(@Header("Authorization") auth: String, @Path("id") id: String): TraceResponseDto

    @DELETE("history/{id}")
    suspend fun deleteHistoryItem(@Header("Authorization") auth: String, @Path("id") id: String)

    @DELETE("history")
    suspend fun clearHistory(@Header("Authorization") auth: String)

    @GET("preferences")
    suspend fun preferences(@Header("Authorization") auth: String): PreferencesDto

    @PUT("preferences")
    suspend fun setPreferences(@Header("Authorization") auth: String, @Body body: PreferencesRequestDto): PreferencesDto

    @GET("memory/{userId}")
    suspend fun profile(@Header("Authorization") auth: String, @Path("userId") userId: String): MemoryResponseDto

    @POST("policy/ask")
    suspend fun askPolicy(@Body body: PolicyAskRequestDto): PolicyAskResponseDto

    @GET("policy/status")
    suspend fun policyStatus(): PolicyStatusDto
}
