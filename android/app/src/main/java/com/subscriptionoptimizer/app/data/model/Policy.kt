package com.subscriptionoptimizer.app.data.model

/** A policy chunk the backend's ChromaDB search actually returned. */
data class PolicyEvidence(
    val chunk: Int,
    val title: String,
    val text: String,
    val source: String,
    /** 0–1, higher is closer. */
    val relevance: Double,
    /** False when the indexed text differs from the current policy document. */
    val matchesCurrentPolicy: Boolean,
    /** Whether the answer cites this chunk. */
    val cited: Boolean,
)

data class PolicyAnswer(
    val question: String,
    val answer: String,
    /** False when no policy text was relevant; the model was not asked. */
    val answeredFromPolicy: Boolean,
    val evidence: List<PolicyEvidence>,
    val citedChunks: List<Int>,
    val unsupportedCitations: List<Int>,
    val traceEvents: List<TraceEvent>,
    val elapsedSeconds: Double,
)

/** How the vector index compares with the policy file on the server. */
data class PolicyIndexStatus(
    val available: Boolean,
    val indexedChunks: Int,
    val fileChunks: Int,
    val staleChunks: List<Int>,
    val missingChunks: List<Int>,
)
