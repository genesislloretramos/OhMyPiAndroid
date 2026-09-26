package com.omp.terminal.android

import omp.shell.cmd.Cmds
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * `curl`, over [java.net.HttpURLConnection] with the platform trust manager: the same TLS store
 * every other app on the device uses, which is why no `TrustManager` is written here.
 *
 * Failures are reported in curl's own wording because a script reading this terminal should be
 * able to recognise them; the status the shell returns stays inside its own set
 * (0, 1, 2, 126, 127, 130), so the number in the message is the curl code, not the exit status.
 */
@CommandSpec(
    name = "curl",
    synopsis = "[-sS] [-I] [-X METHOD] [-H 'K: V'] [-o FILE] URL",
    group = "android",
    notes = "HttpURLConnection, redirects followed, 15 s timeout, platform TLS; -sS is accepted " +
        "and ignored because there is no progress meter to silence, and -H takes one value " +
        "because the option table keeps one per name",
)
object Curl : FileCommand() {

    override val flagSpec = "sSI"
    override val valueSpec = "X:H:o:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) {
            ctx.errLine("curl: no URL specified")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val rawHeader = options["H"]
        if (rawHeader != null && !rawHeader.contains(':')) {
            ctx.errLine("curl: bad header '$rawHeader'; expected 'Key: value'")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val headOnly = flags.contains('I')
        val method = if (headOnly) "HEAD" else (options["X"] ?: "GET").uppercase()
        val headers: List<Pair<String, String>> = if (rawHeader == null) {
            emptyList()
        } else {
            val colon = rawHeader.indexOf(':')
            listOf(rawHeader.substring(0, colon).trim() to rawHeader.substring(colon + 1).trim())
        }
        val output = options["o"]

        var status = ExecContext.EXIT_OK
        for (url in operands) {
            val code = request(ctx, url, method, headers, output, headOnly)
            if (code != ExecContext.EXIT_OK) status = code
        }
        return status
    }

    private fun request(
        ctx: ExecContext,
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        output: String?,
        headOnly: Boolean,
    ): Int {
        val target = try {
            URL(url)
        } catch (e: MalformedURLException) {
            return ctx.fail("curl: (3) URL using bad/illegal format or missing URL: $url")
        }
        val result = try {
            ctx.services.httpGet(url, method, headers)
        } catch (e: UnknownHostException) {
            return ctx.fail("curl: (6) Could not resolve host: ${target.host}")
        } catch (e: ConnectException) {
            return ctx.fail("curl: (7) Failed to connect to ${target.host} port ${portOf(target)}")
        } catch (e: SocketTimeoutException) {
            return ctx.fail("curl: (28) Operation timed out after 15000 milliseconds")
        } catch (e: SSLException) {
            return ctx.fail("curl: (35) SSL connect error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IOException) {
            return ctx.fail(
                "curl: (7) Failed to connect to ${target.host} port ${portOf(target)}: ${e.message}",
            )
        }

        if (headOnly) {
            ctx.outLine("HTTP/1.1 ${result.code}")
            for ((key, value) in result.headers) ctx.outLine("$key: $value")
        }
        if (output != null) {
            if (!writeTo(ctx, output, result.body)) return ExecContext.EXIT_GENERAL_ERROR
        } else {
            ctx.out(result.body)
        }
        // With -o the body carries the whole answer, which is what the real tool does; with the body
        // on the terminal an error status is called out so a script is not left reading it as data.
        if (result.code >= 400 && output == null) {
            ctx.errLine("curl: (22) HTTP error ${result.code}")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        return ExecContext.EXIT_OK
    }

    private fun writeTo(ctx: ExecContext, target: String, body: ByteArray): Boolean {
        val path = Cmds.resolve(ctx, target) ?: return false
        val file = File(path)
        return try {
            file.writeBytes(body)
            true
        } catch (e: IOException) {
            ctx.errLine("curl: (23) Failed writing body to $target: ${e.message}")
            false
        }
    }

    private fun portOf(url: URL): Int = when {
        url.port != -1 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
