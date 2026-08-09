package com.androidperformancestudio.platform.perfetto

public data class TraceQueryResult(
    public val columns: List<String>,
    public val rows: List<TraceQueryRow>,
) {
    public companion object {
        public fun parse(csv: String): TraceQueryResult {
            val values = parseCsv(csv)
            if (values.isEmpty()) return TraceQueryResult(emptyList(), emptyList())
            val columns = values.first()
            require(columns.none(String::isBlank)) { "trace query returned a blank column name" }
            return TraceQueryResult(
                columns = columns,
                values.drop(1).filter(List<String>::isNotEmpty).map { row ->
                    require(row.size == columns.size) { "trace query row does not match its header" }
                    TraceQueryRow(columns.zip(row).toMap())
                },
            )
        }

        private fun parseCsv(input: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val row = mutableListOf<String>()
            val value = StringBuilder()
            var quoted = false
            var index = 0

            while (index < input.length) {
                when (val character = input[index]) {
                    '"' ->
                        if (quoted && input.getOrNull(index + 1) == '"') {
                            value.append('"')
                            index++
                        } else {
                            quoted = !quoted
                        }
                    ',' -> if (quoted) value.append(character) else {
                        row += value.toString()
                        value.clear()
                    }
                    '\n' -> if (quoted) value.append(character) else {
                        row += value.toString()
                        value.clear()
                        rows += row.toList()
                        row.clear()
                    }
                    '\r' -> Unit
                    else -> value.append(character)
                }
                index++
            }
            require(!quoted) { "trace query returned unterminated CSV quoting" }
            if (value.isNotEmpty() || row.isNotEmpty()) {
                row += value.toString()
                rows += row.toList()
            }
            return rows
        }
    }
}

public class TraceQueryRow internal constructor(
    private val values: Map<String, String>,
) {
    public operator fun <T> get(column: TraceColumn<T>): T? = column[this]

    public fun string(column: String): String? = value(column)

    public fun long(column: String): Long? = value(column)?.toLongOrNull()

    public fun double(column: String): Double? = value(column)?.toDoubleOrNull()

    private fun value(column: String): String? =
        values[column]?.takeUnless { it == "[NULL]" }
}

public class TraceColumn<T> private constructor(
    public val name: String,
    private val value: (TraceQueryRow) -> T?,
) {
    public operator fun get(row: TraceQueryRow): T? = value(row)

    public companion object {
        public fun string(name: String): TraceColumn<String> = TraceColumn(name) { row -> row.string(name) }

        public fun long(name: String): TraceColumn<Long> = TraceColumn(name) { row -> row.long(name) }

        public fun double(name: String): TraceColumn<Double> = TraceColumn(name) { row -> row.double(name) }
    }
}

public data class TraceQuerySchema(
    public val traceProcessorVersion: String,
    public val columns: List<TraceColumn<*>>,
) {
    init {
        require(columns.isNotEmpty()) { "typed trace queries need at least one column" }
        require(columns.map(TraceColumn<*>::name).distinct().size == columns.size) {
            "typed trace query columns must be unique"
        }
    }

    public companion object {
        public const val PINNED_TRACE_PROCESSOR_VERSION: String = "v57.2"

        public fun v57_2(vararg columns: TraceColumn<*>): TraceQuerySchema =
            TraceQuerySchema(PINNED_TRACE_PROCESSOR_VERSION, columns.toList())
    }
}

public class TraceQuery<T>(
    public val sql: String,
    public val schema: TraceQuerySchema,
    private val map: (TraceQueryRow) -> T,
) {
    init {
        require(sql.isNotBlank()) { "trace SQL must not be blank" }
    }

    public fun map(result: TraceQueryResult): List<T> {
        require(result.columns == schema.columns.map(TraceColumn<*>::name)) {
            "trace query result does not match its pinned schema"
        }
        return result.rows.map(map)
    }
}
