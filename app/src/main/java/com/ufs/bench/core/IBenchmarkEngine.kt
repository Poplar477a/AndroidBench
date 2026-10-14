package com.ufs.bench.core

import com.ufs.bench.model.BenchmarkSummary
import com.ufs.bench.model.IometerAccessSpec
import com.ufs.bench.model.RealtimeMetric
import kotlinx.coroutines.flow.Flow

interface IBenchmarkEngine {
    val name: String
    val isAvailable: Boolean

    suspend fun prepareTargetFile(filePath: String, sizeMb: Int, cacheMode: Int = 0): Boolean
    fun runBenchmark(filePath: String, spec: IometerAccessSpec): Flow<RealtimeMetric>
    fun stopBenchmark()
    fun getLastSummary(): BenchmarkSummary?
}
