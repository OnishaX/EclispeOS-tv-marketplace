package com.example.eclipse

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.view.KeyEvent
import android.view.KeyCharacterMap
import androidx.core.view.DisplayCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class TvCapabilityReport(
    private val context: Context,
    private val readProbe: (CapabilityAdbProbe) -> String = { LocalNetworkAdb.get(context).probe(it) },
) {
    fun scan(): JSONObject {
        val screen = requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(0))
        val native = DisplayCompat.getMode(context, screen)
        val display = JSONObject().put("nativeSize", JSONArray(listOf(native.physicalWidth, native.physicalHeight)))
            .put("appModes", JSONArray(screen.supportedModes.map {
                JSONObject().put("width", it.physicalWidth).put("height", it.physicalHeight).put("refreshHz", it.refreshRate)
            })).put("panelHdrTypes", JSONArray(screen.hdrCapabilities.supportedHdrTypes.toList()))
        context.getSystemService(android.media.tv.TvInputManager::class.java)?.let { manager ->
            display.put("observedPassthroughInputTypes", JSONArray(manager.tvInputList
                .filter { it.isPassthroughInput }.map { it.type }))
        }
        val sound = JSONObject()
        val speakers = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        sound.put("builtInSpeakers", JSONArray(speakers.map {
            JSONObject().put("encodings", JSONArray(it.encodings.toList())).put("sampleRates", JSONArray(it.sampleRates.toList()))
                .put("channelCounts", JSONArray(it.channelCounts.toList()))
        }))
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        sound.put("decoderMimeTypes", JSONArray(codecs.flatMap { it.supportedTypes.toList() }
            .filter { it.startsWith("audio/") }.distinct().sorted()))
        val buffers = JSONObject().put("videoDecoderMimeTypes", JSONArray(codecs.flatMap { it.supportedTypes.toList() }
            .filter { it.startsWith("video/") }.distinct().sorted()))
        val limits = JSONArray()
        codecs.filter { it.isHardwareAccelerated }.forEach { codec ->
            codec.supportedTypes.filter { it.startsWith("video/") }.forEach { mime ->
                val capability = codec.getCapabilitiesForType(mime)
                val video = capability.videoCapabilities
                limits.put(JSONObject().put("mime", mime).put("maxInstances", capability.maxSupportedInstances)
                    .put("maxWidth", video.supportedWidths.upper).put("maxHeight", video.supportedHeights.upper)
                    .put("maxFrameRate", video.supportedFrameRates.upper)
                    .put("colorFormats", JSONArray(capability.colorFormats.toList()))
                    .put("profileLevels", JSONArray(capability.profileLevels.map {
                        JSONObject().put("profile", it.profile).put("level", it.level)
                    })))
            }
        }
        buffers.put("reportedHardwareDecoderLimits", limits)
            .put("simultaneousTvInputScalers", JSONObject.NULL)
            .put("hdmiSnapshotCapture", "unknown; requires a consented test with non-protected content")
            .put("hdrUiOutput", JSONObject.NULL)
        UiSurfaceHost.latestOutput?.let {
            buffers.put("observedInterfaceOutput", JSONObject().put("size", JSONArray(listOf(it.width, it.height)))
                .put("hdr", it.hdr).put("displayRefreshHz", it.refreshRate))
        }
        val os = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("firmwareBuild", Build.DISPLAY).put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("manufacturer", Build.MANUFACTURER).put("board", Build.BOARD)
        val keys = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_TV_INPUT,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        val remote = JSONObject().put("reportedKeyPresence", JSONArray(keys.map {
            JSONObject().put("code", it).put("present", KeyCharacterMap.deviceHasKey(it))
        })).put("keyDelivery", "unknown; key presence does not prove Eclipse receives it")
        val verification = JSONObject().put("method", "Android APIs and read-only local ADB")
            .put("rawDiagnosticsIncluded", false).put("connectedReceiverAudioSupport", "not model capability")
            .put("decoderLimits", "reported limits; simultaneous decode and maximum dimensions/rate require validation")
        val unavailable = JSONArray()
        for (probe in CapabilityAdbProbe.entries) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val raw = readProbe(probe)
                when (probe) {
                    CapabilityAdbProbe.COMPOSITOR -> compositorFacts(raw, display, buffers)
                    CapabilityAdbProbe.DISPLAY -> display.put("adbDisplayReportAvailable", true)
                    CapabilityAdbProbe.AUDIO_POLICY -> {
                        val formats = Regex("""\bAUDIO_FORMAT_[A-Z0-9_]+\b""").findAll(raw).map { it.value }.toSortedSet()
                        sound.put("advertisedPolicyFormats", JSONArray(formats.toList()))
                            .put("policyFormatsArePlaybackVerification", false)
                        sound.put("earcPolicyPresent", raw.contains("EARC", ignoreCase = true))
                    }
                    CapabilityAdbProbe.REMOTE -> remote.put("inputReportAvailable", true)
                    CapabilityAdbProbe.UI_PRESENTATION -> {
                        val times = raw.lineSequence().map { it.trim().split(Regex("\\s+")) }
                            .filter { it.size == 3 }.mapNotNull { it[1].toLongOrNull() }
                            .filter { it in 1 until Long.MAX_VALUE }.distinct().sorted().toList()
                        if (times.size >= 10 && times.last() > times.first()) {
                            buffers.put("observedUiPresentationFps", (times.size - 1) * 1e9 / (times.last() - times.first()))
                                .put("uiPresentationSamples", times.size)
                        } else unavailable.put("UI_PRESENTATION: no usable layer timing samples; measured frame rate is unknown")
                    }
                }
            } catch (e: IOException) {
                unavailable.put("${probe.name}: ADB read failed; retry the scan")
            } catch (e: IllegalStateException) {
                unavailable.put("${probe.name}: local ADB unavailable or not authorized")
            }
        }
        verification.put("unavailableProbes", unavailable)
        val report = JSONObject().put("display", display).put("sound", sound).put("os", os)
            .put("remote", remote).put("buffers", buffers).put("verification", verification)
        TvCapabilities.parse(report)
        require(report.toString().toByteArray().size < EclipseBundles.MAX_MANIFEST - 4096) { "The capability report is too large to publish." }
        return report
    }

    companion object {
        internal fun compositorFacts(raw: String, display: JSONObject, buffers: JSONObject) {
            val extensions = Regex("""\bEGL_[A-Za-z0-9_]+\b""").findAll(raw).map { it.value }.distinct().sorted().toList()
            if (extensions.isNotEmpty()) {
                display.put("eglExtensions", JSONArray(extensions))
                if ("EGL_EXT_gl_colorspace_bt2020_pq" !in extensions) buffers.put("hdrUiOutput", false)
            }
            val target = Regex("""FramebufferSurface[\s\S]{0,800}?\[(\d+)x(\d+):""").find(raw)
            if (target != null) display.put("observedClientTargetSize",
                JSONArray(listOf(target.groupValues[1].toInt(), target.groupValues[2].toInt())))
        }
    }
}
