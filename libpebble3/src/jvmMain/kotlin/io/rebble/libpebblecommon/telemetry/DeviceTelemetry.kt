package io.rebble.libpebblecommon.telemetry

import co.touchlab.kermit.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Fire-and-forget OTLP/JSON log sender for the phone's local telemetry broker (`pmd`,
 * http://127.0.0.1:4318 - see ~/own/ut/ut-telemetry-broker.md for the full contract). Only
 * reachable on a fleet device running phone-manager; on any other machine (a plain desktop
 * dev run, say) every send just fails to connect and is silently dropped, which is the
 * correct/expected behaviour, not an error to surface.
 *
 * A dead or absent broker must cost the app nothing (rule 1 of the contract): every send runs
 * on its own single-thread daemon executor with a short connect+request timeout, and every
 * failure - connection refused, timeout, anything - is swallowed. Uses the old
 * `java.net.HttpURLConnection` rather than `java.net.http.HttpClient`: the latter lives in the
 * `java.net.http` JPMS module, which isn't in this app's jlink-trimmed runtime image
 * (composeApp/build.gradle.kts's `nativeDistributions.modules(...)`) and threw
 * NoClassDefFoundError at startup on-device (confirmed live, 0.1.64) - HttpURLConnection is
 * `java.base`, always present, and a closer match to the contract's "dependency-free" reference
 * implementation anyway.
 */
object DeviceTelemetry {
    private const val ENDPOINT = "http://127.0.0.1:4318/v1/logs"
    private const val TIMEOUT_MS = 2_000

    private val logger = Logger.withTag("DeviceTelemetry")

    @Volatile
    private var serviceName: String? = null

    @Volatile
    private var serviceVersion: String = "unknown"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "device-telemetry").apply { isDaemon = true }
    }

    /**
     * Must be called once at process startup before [event] does anything useful - events fired
     * before this are dropped (no [serviceName] to tag them with, and the contract requires a
     * stable identity on every record).
     */
    fun init(serviceName: String, serviceVersion: String) {
        this.serviceName = serviceName
        this.serviceVersion = serviceVersion
    }

    /**
     * @param eventKind the `event.kind` attribute - one stable string per event/error class, e.g.
     *   `"app.start"`, `"error.FailedToDownloadPbw"`, `"ble.connect.success"`.
     * @param message human-readable summary; per the contract, never PII (phone numbers, contact
     *   data, message content, tokened URLs, precise location).
     * @param attributes extra string attributes; keep the value set small/bounded per attribute -
     *   anything set-shaped belongs here as a log attribute, not as a metric label.
     * @param durationMs set for timed operations (connect handshakes, syncs) - becomes the
     *   `duration.ms` attribute.
     */
    fun event(
        eventKind: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        durationMs: Long? = null,
    ) {
        val service = serviceName ?: return
        val version = serviceVersion
        executor.execute {
            try {
                send(service, version, eventKind, message, attributes, durationMs)
            } catch (e: Exception) {
                // Swallow everything - connection refused (no broker configured on this device)
                // is the normal case, not a fault. Logged at verbose only to avoid this becoming
                // its own noise source.
                logger.v(e) { "telemetry send failed (broker absent/unreachable - expected)" }
            }
        }
    }

    private fun send(
        service: String,
        version: String,
        eventKind: String,
        message: String,
        attributes: Map<String, String>,
        durationMs: Long?,
    ) {
        val body = buildPayload(service, version, eventKind, message, attributes, durationMs)
            .toByteArray(StandardCharsets.UTF_8)
        val connection = URI.create(ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body) }
            // Acceptance (a 200) only means the broker spooled it, not that it reached
            // ClickHouse - nothing useful to do with the response either way, but the status
            // still has to be read to let the connection complete/release cleanly.
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPayload(
        service: String,
        version: String,
        eventKind: String,
        message: String,
        attributes: Map<String, String>,
        durationMs: Long?,
    ): String {
        val allAttributes = buildMap {
            put("event.kind", eventKind)
            durationMs?.let { put("duration.ms", it.toString()) }
            putAll(attributes)
        }
        val attributesJson = allAttributes.entries.joinToString(",") { (key, value) ->
            // duration.ms is the one numeric attribute; everything else, including any caller-
            // supplied value, is sent as a string - simplest schema, and matches what the
            // contract's own reference payload does for its non-duration attribute.
            val valueField = if (key == "duration.ms") {
                "\"intValue\":\"${value.jsonEscape()}\""
            } else {
                "\"stringValue\":\"${value.jsonEscape()}\""
            }
            "{\"key\":\"${key.jsonEscape()}\",\"value\":{$valueField}}"
        }
        return """
            {"resourceLogs":[{"resource":{"attributes":[
              {"key":"service.name","value":{"stringValue":"${service.jsonEscape()}"}},
              {"key":"service.version","value":{"stringValue":"${version.jsonEscape()}"}}]},
              "scopeLogs":[{"scope":{"name":"${service.jsonEscape()}"},"logRecords":[{
                "timeUnixNano":"${System.currentTimeMillis() * 1_000_000}",
                "severityText":"INFO",
                "body":{"stringValue":"${message.jsonEscape()}"},
                "attributes":[$attributesJson]}]}]}]}
        """.trimIndent()
    }

    // No JSON library involved (matches the contract's own "dependency-free" reference) - this is
    // the caveat the contract explicitly calls out: anything interpolated into the payload must
    // be escaped by hand.
    private fun String.jsonEscape(): String = buildString(length) {
        this@jsonEscape.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
