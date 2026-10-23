package ai.clu.platform

import ai.clu.core.db.SqlDriver
import ai.clu.core.db.SqlRow
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram

/** SqlDriver over android.database.sqlite; schema/migrations are run by :core (Schema.migrate). */
class AndroidSqlDriver(context: Context, name: String = "clu.db") : SqlDriver {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {}
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val db: SQLiteDatabase = helper.writableDatabase

    private fun bind(p: SQLiteProgram, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> p.bindNull(idx)
                is String -> p.bindString(idx, a)
                is Long -> p.bindLong(idx, a)
                is Int -> p.bindLong(idx, a.toLong())
                is Boolean -> p.bindLong(idx, if (a) 1 else 0)
                is Double -> p.bindDouble(idx, a)
                is Float -> p.bindDouble(idx, a.toDouble())
                is ByteArray -> p.bindBlob(idx, a)
                else -> throw IllegalArgumentException("unsupported SQL argument ${a::class.java}")
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        val st = db.compileStatement(sql)
        try { bind(st, args); st.execute() } finally { st.close() }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val cursor = db.rawQueryWithFactory({ d, driver, table, query ->
            bind(query, args)
            android.database.sqlite.SQLiteCursor(driver, table, query)
        }, sql, null, "")
        cursor.use { c ->
            val row = Row(c)
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(row)
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally { db.endTransaction() }
    }

    override var userVersion: Int
        get() = db.version
        set(v) { db.version = v }

    override fun close() = helper.close()

    private class Row(val c: Cursor) : SqlRow {
        override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
        override fun long(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
        override fun double(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
        override fun blob(i: Int): ByteArray? = if (c.isNull(i)) null else c.getBlob(i)
        override fun isNull(i: Int) = c.isNull(i)
    }
}
