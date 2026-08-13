package com.mtzallqmy.aiagent.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.mtzallqmy.aiagent.native_runtime.RustExecutionRequest
import com.mtzallqmy.aiagent.native_runtime.RustRuntimeClient
import com.mtzallqmy.aiagent.tool.terminal.TerminalCommandExecutor
import com.mtzallqmy.aiagent.tool.terminal.TerminalCommandExecutors
import com.mtzallqmy.aiagent.tool.terminal.TerminalToolSet

/** Installs the fail-closed terminal executor before Application.onCreate. */
class IsolatedTerminalRuntimeInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        TerminalCommandExecutors.install(
            TerminalCommandExecutor { argv, timeoutMs, maxOutputBytes ->
                val client = RustRuntimeClient(appContext)
                client.connect()
                try {
                    val start = client.start(
                        RustExecutionRequest(
                            program = "/system/bin/toybox",
                            arguments = argv,
                            timeoutMs = timeoutMs,
                            stdoutLimitBytes = maxOutputBytes,
                            stderrLimitBytes = maxOutputBytes,
                        ),
                    )
                    val executionId = start.executionId
                    if (start.status != "started" || executionId == null) {
                        TerminalToolSet.TerminalResult(
                            exitCode = -1,
                            stdout = "",
                            stderr = start.error ?: "Isolated runtime rejected the command",
                        )
                    } else {
                        try {
                            val result = client.awaitResult(executionId, timeoutMs + 1_000L)
                            val diagnostic = listOf(result.stderr, result.error.orEmpty())
                                .filter { it.isNotBlank() }
                                .joinToString("\n")
                            TerminalToolSet.TerminalResult(
                                exitCode = result.exitCode ?: -1,
                                stdout = result.stdout,
                                stderr = diagnostic,
                            )
                        } finally {
                            client.release(executionId)
                        }
                    }
                } finally {
                    client.close()
                }
            },
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
