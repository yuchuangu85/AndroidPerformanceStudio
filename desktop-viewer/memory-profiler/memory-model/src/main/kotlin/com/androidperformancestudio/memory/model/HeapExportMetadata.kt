package com.androidperformancestudio.memory.model

data class HeapExportMetadata(
    val format: String,
    val idSize: Int,
    val timestampMillis: Long,
    val instanceCount: Int,
    val objectArrayCount: Int,
    val primitiveArrayCount: Int,
    val gcRootCount: Int,
    val warnings: List<MemoryWarning> = emptyList(),
)

data class HeapExportInstance(
    val objectId: Long,
    val className: String,
    val shallowSize: Long?,
    val retainedSize: Long?,
)
