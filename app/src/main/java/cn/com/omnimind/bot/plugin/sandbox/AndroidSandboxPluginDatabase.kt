package cn.com.omnimind.bot.plugin.sandbox

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

object AndroidSandboxPluginDatabaseFactory : SandboxPluginDatabaseFactory {
    override fun open(databaseFile: File): SandboxPluginDatabase =
        AndroidSandboxPluginDatabase(
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null),
        )
}

private class AndroidSandboxPluginDatabase(
    private val database: SQLiteDatabase,
) : SandboxPluginDatabase {
    override fun initialize(schemaSql: String) {
        val statements = SandboxSqlPolicy.statements(schemaSql)
        database.beginTransaction()
        try {
            statements.forEach(database::execSQL)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun insert(table: String, values: Map<String, Any?>): Long {
        val contentValues = values.toContentValues()
        val rowId = database.insertOrThrow(table, null, contentValues)
        require(rowId >= 0) { "SQLite insert failed" }
        return rowId
    }

    override fun query(
        table: String,
        where: Map<String, Any?>,
        orderBy: String?,
        limit: Int,
        offset: Int,
    ): List<Map<String, Any?>> {
        val selection = where.entries.joinToString(" AND ") { (column, value) ->
            if (value == null) "$column IS NULL" else "$column = ?"
        }.ifBlank { null }
        val selectionArgs = where.values.mapNotNull { value -> value?.toString() }
            .takeIf(List<String>::isNotEmpty)
            ?.toTypedArray()
        return database.query(
            table,
            null,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            "$offset,$limit",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.row())
                }
            }
        }
    }

    override fun update(table: String, id: Any, values: Map<String, Any?>): Int =
        database.update(table, values.toContentValues(), "id = ?", arrayOf(id.toString()))

    override fun delete(table: String, id: Any): Int =
        database.delete(table, "id = ?", arrayOf(id.toString()))

    override fun close() = database.close()

    private fun Cursor.row(): Map<String, Any?> = buildMap {
        columnNames.forEachIndexed { index, column ->
            put(
                column,
                when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                    Cursor.FIELD_TYPE_BLOB -> getBlob(index)
                    else -> getString(index)
                },
            )
        }
    }

    private fun Map<String, Any?>.toContentValues(): ContentValues =
        ContentValues(size).also { contentValues ->
            forEach { (column, value) ->
                when (value) {
                    null -> contentValues.putNull(column)
                    is String -> contentValues.put(column, value)
                    is Boolean -> contentValues.put(column, value)
                    is Int -> contentValues.put(column, value)
                    is Long -> contentValues.put(column, value)
                    is Float -> contentValues.put(column, value)
                    is Double -> contentValues.put(column, value)
                    else -> throw IllegalArgumentException(
                        "Unsupported SQLite value for $column: ${value.javaClass.simpleName}",
                    )
                }
            }
        }
}
