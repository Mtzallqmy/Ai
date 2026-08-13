package com.mtzallqmy.aiagent.local_llm.internal

internal interface LlamaNativeBridge {
    fun loadModel(path: String, useMemoryMap: Boolean): Long
    fun modelInfo(modelHandle: Long): NativeModelInfo
    fun unloadModel(modelHandle: Long)
    fun startGeneration(
        modelHandle: Long,
        prompt: String,
        contextSize: Int,
        threads: Int,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
    ): Long
    fun nextToken(generationHandle: Long): ByteArray?
    fun cancelGeneration(generationHandle: Long)
    fun generationUsage(generationHandle: Long): LongArray
    fun freeGeneration(generationHandle: Long)
}

internal data class NativeModelInfo(
    val description: String,
    val parameterCount: Long,
    val tensorBytes: Long,
)

internal class LlamaCppJniBridge : LlamaNativeBridge {
    init {
        System.loadLibrary("aegis_llama")
    }

    override external fun loadModel(path: String, useMemoryMap: Boolean): Long
    private external fun modelDescription(modelHandle: Long): String
    private external fun modelParameterCount(modelHandle: Long): Long
    private external fun modelTensorBytes(modelHandle: Long): Long
    override fun modelInfo(modelHandle: Long) = NativeModelInfo(
        description = modelDescription(modelHandle),
        parameterCount = modelParameterCount(modelHandle),
        tensorBytes = modelTensorBytes(modelHandle),
    )
    override external fun unloadModel(modelHandle: Long)
    override external fun startGeneration(
        modelHandle: Long,
        prompt: String,
        contextSize: Int,
        threads: Int,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
    ): Long
    override external fun nextToken(generationHandle: Long): ByteArray?
    override external fun cancelGeneration(generationHandle: Long)
    override external fun generationUsage(generationHandle: Long): LongArray
    override external fun freeGeneration(generationHandle: Long)
}
