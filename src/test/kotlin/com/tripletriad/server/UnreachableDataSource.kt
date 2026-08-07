package com.tripletriad.server

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A [DataSource] that always fails to connect.
 *
 * Hand-written rather than mocked: the readiness probe's whole job is to behave well when the
 * database is gone, and a stub that throws on `getConnection` reproduces that in three lines
 * without a mocking framework in the dependency set.
 */
internal object UnreachableDataSource : DataSource {
    override fun getConnection(): Connection = throw SQLException("connection refused")

    override fun getConnection(username: String?, password: String?): Connection = connection

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
