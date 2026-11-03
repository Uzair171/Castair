package com.castair.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1
const val DEFAULT_PORT = 8765
const val MDNS_SERVICE_TYPE = "_castair._tcp."

val CastairJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---------- shared data ----------

/** One thing to play. The receiver streams it directly from [url]. */
@Serializable
data class CastItem(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(), // Referer, User-Agent, Cookie
    val subtitleUrl: String? = null,
    val startPositionMs: Long = 0,
)

@Serializable
data class Track(
    val id: String,
    val label: String,
    val language: String? = null,
)

enum class PlayStatus { IDLE, CONNECTED, LOADING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }

// ---------- phone -> receiver ----------

@Serializable
sealed interface Command {

    /** First connection only. [pairKey] comes from the QR code shown on the TV. */
    @Serializable @SerialName("pair")
    data class Pair(val pairKey: String, val deviceId: String, val deviceName: String) : Command

    /** Every later connection. [secret] was given by the receiver when pairing. */
    @Serializable @SerialName("hello")
    data class Hello(val deviceId: String, val secret: String, val version: Int = PROTOCOL_VERSION) : Command

    @Serializable @SerialName("play")
    data class Play(val item: CastItem) : Command

    @Serializable @SerialName("queue_add")
    data class QueueAdd(val item: CastItem) : Command

    @Serializable @SerialName("pause") data object Pause : Command
    @Serializable @SerialName("resume") data object Resume : Command
    @Serializable @SerialName("stop") data object Stop : Command
    @Serializable @SerialName("next") data object Next : Command
    @Serializable @SerialName("get_state") data object GetState : Command

    @Serializable @SerialName("seek")
    data class Seek(val positionMs: Long) : Command

    @Serializable @SerialName("volume")
    data class SetVolume(val percent: Int) : Command // 0..100

    /** [height] = 2160, 1440, 1080, 720... ; null = Auto. */
    @Serializable @SerialName("quality")
    data class SetQuality(val height: Int?) : Command

    @Serializable @SerialName("audio_track")
    data class SetAudioTrack(val id: String) : Command

    /** null = subtitles off. */
    @Serializable @SerialName("subtitle_track")
    data class SetSubtitleTrack(val id: String?) : Command

    @Serializable @SerialName("speed")
    data class SetSpeed(val speed: Float) : Command
}

// ---------- receiver -> phone ----------

@Serializable
sealed interface Event {

    @Serializable @SerialName("welcome")
    data class Welcome(val deviceName: String, val version: Int = PROTOCOL_VERSION) : Event

    /** Reply to [Command.Pair]. The phone stores [secret] for later. */
    @Serializable @SerialName("paired")
    data class Paired(val secret: String, val deviceName: String) : Event

    @Serializable @SerialName("state")
    data class State(
        val status: PlayStatus,
        val title: String = "",
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val volumePercent: Int = 100,
        val qualityHeight: Int? = null, // null = Auto
        val speed: Float = 1f,
        val queueSize: Int = 0,
        val connectedDeviceName: String? = null,
    ) : Event

    /** Only lists what this box can really play. */
    @Serializable @SerialName("tracks")
    data class Tracks(
        val qualities: List<Int> = emptyList(),
        val audio: List<Track> = emptyList(),
        val subtitles: List<Track> = emptyList(),
    ) : Event

    /** Codes: unauthorized, bad_pair_key, too_many_attempts, link_expired, playback_failed, busy */
    @Serializable @SerialName("error")
    data class Error(val code: String, val message: String = "") : Event
}

// ---------- JSON helpers ----------

fun Command.toJson(): String = CastairJson.encodeToString(Command.serializer(), this)
fun Event.toJson(): String = CastairJson.encodeToString(Event.serializer(), this)

fun parseCommand(text: String): Command? =
    runCatching { CastairJson.decodeFromString(Command.serializer(), text) }.getOrNull()

fun parseEvent(text: String): Event? =
    runCatching { CastairJson.decodeFromString(Event.serializer(), text) }.getOrNull()

// ---------- QR pairing ----------

/**
 * What the TV shows as a QR code.
 * [pairKey] = one-time random key (hex). [certSha256] = fingerprint of the
 * receiver's TLS certificate (hex), so the phone can pin it.
 */
data class PairingInfo(
    val host: String,
    val port: Int,
    val pairKey: String,
    val certSha256: String,
) {
    fun toUri(): String = "castair://pair?host=$host&port=$port&key=$pairKey&fp=$certSha256"

    companion object {
        fun parse(uri: String): PairingInfo? {
            val prefix = "castair://pair?"
            if (!uri.startsWith(prefix)) return null
            val q = uri.removePrefix(prefix).split("&")
                .mapNotNull { part -> part.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            return PairingInfo(
                host = q["host"] ?: return null,
                port = q["port"]?.toIntOrNull() ?: return null,
                pairKey = q["key"] ?: return null,
                certSha256 = q["fp"] ?: return null,
            )
        }
    }
}
