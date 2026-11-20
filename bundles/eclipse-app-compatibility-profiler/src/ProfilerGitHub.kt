package com.example.eclipse

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class ProfilerGitHub(
    private val clientId: String,
    private val transport: (String, String, String?, String?) -> JSONObject = ::https,
) : Closeable {
    class HttpFailure(val status: Int) : IOException("GitHub returned HTTP $status.")
    class SignIn internal constructor(
        internal val deviceCode: String, val userCode: String, val verificationUrl: String,
        val expiresIn: Int, val interval: Int,
    )
    sealed class Poll {
        data class Pending(val interval: Int) : Poll()
        data class Authorized(val login: String) : Poll()
    }
    @Volatile private var token: String? = null
    @Volatile private var closed = false
    private var login: String? = null

    fun start(): SignIn {
        checkActive()
        val response = transport("POST", "https://github.com/login/device/code",
            form("client_id" to clientId, "scope" to "public_repo"), null)
        if (response.has("error")) throw IOException("GitHub Device Flow is unavailable. Check that Device Flow is enabled for this OAuth App.")
        val url = response.getString("verification_uri")
        require(url == "https://github.com/login/device") { "GitHub returned an unexpected verification URL." }
        return SignIn(response.getString("device_code"), response.getString("user_code"), url,
            response.getInt("expires_in").also { require(it in 1..3600) },
            response.getInt("interval").also { require(it in 1..120) })
    }

    fun poll(signIn: SignIn, interval: Int): Poll {
        checkActive()
        require(interval in 1..120) { "Invalid GitHub polling interval." }
        val response = transport("POST", "https://github.com/login/oauth/access_token",
            form("client_id" to clientId, "device_code" to signIn.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), null)
        return when (response.optString("error")) {
            "authorization_pending" -> Poll.Pending(interval)
            "slow_down" -> Poll.Pending(maxOf(interval + 5, response.optInt("interval", 0)).also {
                require(it in 1..120) { "GitHub requested an excessive polling delay. Start sign-in again." }
            })
            "" -> {
                val access = response.getString("access_token")
                require(response.getString("scope").split(',', ' ').contains("public_repo")) { "GitHub did not grant public repository access." }
                checkActive()
                val user = transport("GET", "$API/user", null, access).getString("login")
                require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}").matches(user)) { "Invalid GitHub account response." }
                authorize(access, user)
                Poll.Authorized(user)
            }
            "access_denied" -> throw IOException("GitHub sign-in was cancelled.")
            "expired_token", "token_expired" -> throw IOException("The GitHub sign-in code expired. Start sign-in again.")
            else -> throw IOException("GitHub could not authorize this device. Start sign-in again.")
        }
    }

    fun contribute(report: JSONObject, device: TvDevice, icon: ByteArray): String {
        TvCapabilities.parse(report)
        require(report.getJSONObject("os").getString("firmwareBuild") == android.os.Build.DISPLAY &&
            report.getJSONObject("os").getInt("sdk") == device.sdk) { "Run a fresh scan on this firmware before contributing." }
        val user = checkNotNull(login) { "Sign in before creating a pull request." }
        checkNotNull(token) { "Sign in before creating a pull request." }
        val digest = MessageDigest.getInstance("SHA-256").digest(report.toString().toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        val branch = "compatibility/${modelBundleId(device).removePrefix(BundleIds.TV_COMPATIBILITY_PREFIX)}-$digest"
        fun existingPull(): String? {
            val head = URLEncoder.encode("$user:$branch", "UTF-8")
            val items = api("GET", "/repos/$REPOSITORY/pulls?state=all&base=main&head=$head&per_page=1").getJSONArray("items")
            return if (items.length() == 0) null else pullUrl(items.getJSONObject(0))
        }
        existingPull()?.let { return it }
        val base = api("GET", "/repos/$REPOSITORY/git/ref/heads/main").getJSONObject("object").getString("sha")
        require(SHA.matches(base)) { "Invalid Marketplace revision." }
        val baseTree = api("GET", "/repos/$REPOSITORY/git/commits/$base").getJSONObject("tree").getString("sha")
        val indexPath = "repository-v2.json"
        val index = JSONObject(readContent("/repos/$REPOSITORY/contents/$indexPath?ref=$base"))
        val catalog = MarketplaceRepository.parse(base, index.toString())
        val existing = catalog.entries.firstOrNull { it.device == device }
        val id = existing?.id ?: modelBundleId(device)
        val folder = "bundles/$id"
        val manifest = if (existing == null) JSONObject().put("schemaVersion", 1).put("id", id)
            .put("title", "${device.model.take(32)} Compatibility").put("developer", "Eclipse").put("type", "plugin")
            .put("version", "0.1").put("pluginType", "compatibility").put("compatibilityType", "tv")
            .put("entryPoint", JSONObject().put("handler", "compatibility"))
            .put("icons", JSONArray().put(JSONObject().put("iconName", "TV Compatibility").put("path", "icon0.png")))
            .put("colours", JSONArray().put(JSONObject().put("colourName", "Graphite").put("hex", "#4A505C")))
            .put("customisation", JSONObject().put("title", false).put("colour", false))
            .put("capabilities", JSONObject().put("liveThumbnail", false).put("nowPlaying", false))
            .put("compatibility", JSONObject().put("device", deviceJson(device)).put("sharedDigitalTuner", false))
        else JSONObject(readContent("/repos/$REPOSITORY/contents/$folder/manifest.json?ref=$base"))
        val compatibility = manifest.getJSONObject("compatibility")
        val previous = compatibility.optJSONObject("capabilities")
        val sameFirmware = previous?.optJSONObject("os")?.optString("firmwareBuild") ==
            report.getJSONObject("os").getString("firmwareBuild")
        val variants = compatibility.optJSONArray("firmwareProfiles") ?: JSONArray()
        val matchedVariant = (0 until variants.length()).map(variants::getJSONObject).firstOrNull {
            it.optJSONObject("os")?.optString("firmwareBuild") == report.getJSONObject("os").getString("firmwareBuild")
        }
        val facts = when {
            sameFirmware -> mergeFacts(requireNotNull(previous), report)
            matchedVariant != null -> mergeFacts(matchedVariant, report)
            else -> JSONObject(report.toString())
        }
        val retained = JSONArray()
        for (index in 0 until variants.length()) {
            val variant = variants.getJSONObject(index)
            if (variant !== matchedVariant) retained.put(variant)
        }
        if (!sameFirmware && previous?.optJSONObject("os")?.has("firmwareBuild") == true) retained.put(previous)
        if (retained.length() > 0 || compatibility.has("firmwareProfiles")) compatibility.put("firmwareProfiles", retained)
        compatibility.put("capabilities", facts)
        if (existing != null) {
            // A contribution is a proposed revision, never an in-place change to the catalog's main branch.
            val version = manifest.getString("version").split('.').map {
                requireNotNull(it.toIntOrNull()) { "The existing plugin version cannot be incremented." }
            }.toMutableList()
            require(version.isNotEmpty() && version.last() in 0 until Int.MAX_VALUE) { "Invalid existing plugin version." }
            version[version.lastIndex]++
            manifest.put("version", version.joinToString("."))
        }
        val parsed = EclipseBundle.parse(manifest.toString())
        require(manifest.toString().toByteArray().size <= EclipseBundles.MAX_MANIFEST) { "The generated plugin is too large." }
        val entries = index.getJSONArray("bundles")
        val entry = (0 until entries.length()).map(entries::getJSONObject).firstOrNull { it.getString("id") == id }
            ?: JSONObject().put("id", id).put("title", parsed.title).put("type", "plugin")
                .put("device", deviceJson(device)).also(entries::put)
        entry.put("version", parsed.version)
        val targetRepo = if (user.equals(REPOSITORY.substringBefore('/'), ignoreCase = true)) REPOSITORY else {
            val fork = api("POST", "/repos/$REPOSITORY/forks", JSONObject())
            fork.getString("full_name").also {
                require(it.equals("$user/${REPOSITORY.substringAfter('/')}", ignoreCase = true)) { "GitHub returned an unexpected fork." }
            }
        }
        if (targetRepo != REPOSITORY) {
            var ready = false
            for (attempt in 0 until 20) {
                try {
                    api("GET", "/repos/$targetRepo/git/refs/heads")
                    ready = true
                    break
                } catch (e: HttpFailure) {
                    if (e.status !in setOf(404, 409)) throw e
                    Thread.sleep(1500)
                }
            }
            check(ready) { "GitHub is still creating the fork. Retry in a moment." }
        }
        val branchExists = try {
            api("GET", "/repos/$targetRepo/git/ref/heads/$branch")
            true
        } catch (e: HttpFailure) {
            if (e.status != 404) throw e
            false
        }
        if (!branchExists) {
            val tree = JSONArray()
            fun text(path: String, value: JSONObject) {
                tree.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("content", value.toString(2) + "\n"))
            }
            text("$folder/manifest.json", manifest)
            text(indexPath, index)
            if (existing == null) {
                val blob = api("POST", "/repos/$targetRepo/git/blobs", JSONObject()
                    .put("encoding", "base64").put("content", Base64.encodeToString(icon, Base64.NO_WRAP)))
                tree.put(JSONObject().put("path", "$folder/icon0.png").put("mode", "100644").put("type", "blob").put("sha", blob.getString("sha")))
                text("$folder/marketplace/marketplace-manifest.json", JSONObject().put("schemaVersion", 1)
                    .put("tagline", "Community-reported TV hardware and firmware capabilities")
                    .put("description", "Reviewed model-specific compatibility data. Reported API limits are not playback verification; unknown capabilities stay unknown.")
                    .put("features", JSONArray(listOf("Model and firmware scoped", "No raw diagnostics or identifying device data"))))
            }
            val treeSha = api("POST", "/repos/$targetRepo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", tree)).getString("sha")
            val commit = api("POST", "/repos/$targetRepo/git/commits", JSONObject()
                .put("message", "Report compatibility for ${device.model}")
                .put("tree", treeSha).put("parents", JSONArray(listOf(base)))).getString("sha")
            api("POST", "/repos/$targetRepo/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", commit))
        }
        val result = try { api("POST", "/repos/$REPOSITORY/pulls", JSONObject()
            .put("title", "Compatibility report: ${device.model} (API ${device.sdk})")
            .put("head", "$user:$branch").put("base", "main")
            .put("body", "Submitted from Eclipse Compatibility Profiler after user review.\n\n" +
                "Includes allowlisted hardware/firmware facts only. No raw dumps, network addresses, serial numbers, credentials or account details.\n\n" +
                "API-reported codec limits, connected audio policy and physical panel capabilities must not be interpreted as verified simultaneous playback or HDR interface support. Please review the firmware scope and all unknowns."))
        } catch (e: HttpFailure) {
            if (e.status != 422) throw e
            return existingPull() ?: throw e
        }
        return pullUrl(result)
    }

    private fun readContent(path: String) = String(Base64.decode(api("GET", path).getString("content"), Base64.DEFAULT), Charsets.UTF_8)
    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        checkActive()
        return transport(method, "$API$path", body?.toString(), checkNotNull(token))
    }
    private fun checkActive() {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedException("GitHub contribution cancelled.")
    }
    @Synchronized private fun authorize(access: String, user: String) {
        checkActive()
        token = access
        login = user
    }
    @Synchronized override fun close() { closed = true; token = null; login = null }

    companion object {
        const val REPOSITORY = "OnishaX/EclispeOS-tv-marketplace"
        private const val API = "https://api.github.com"
        private val SHA = Regex("[0-9a-f]{40}")
        private fun pullUrl(result: JSONObject) = result.getString("html_url").also {
            require(Regex("https://github\\.com/$REPOSITORY/pull/[0-9]+").matches(it)) { "Unexpected pull request URL." }
        }
        internal fun modelBundleId(device: TvDevice): String {
            val model = device.model.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .take(18).trimEnd('-').ifEmpty { "tv" }
            val digest = MessageDigest.getInstance("SHA-256").digest("${device.model}|${device.product}|${device.sdk}".toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            return "${BundleIds.TV_COMPATIBILITY_PREFIX}$model-$digest"
        }
        internal fun deviceJson(device: TvDevice) = JSONObject().put("model", device.model).put("product", device.product).put("sdk", device.sdk)
        internal fun mergeFacts(previous: JSONObject, observed: JSONObject): JSONObject {
            val merged = JSONObject(previous.toString())
            observed.keys().forEach { section ->
                val old = merged.optJSONObject(section) ?: JSONObject()
                val next = observed.getJSONObject(section)
                next.keys().forEach { key -> if (!next.isNull(key)) old.put(key, next.get(key)) }
                merged.put(section, old)
            }
            return merged
        }
        private fun form(vararg fields: Pair<String, String>) = fields.joinToString("&") {
            "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}"
        }
        private fun https(method: String, address: String, body: String?, token: String?): JSONObject {
            val url = URL(address)
            require(url.protocol == "https" && url.host in setOf("api.github.com", "github.com")) { "Only GitHub HTTPS endpoints are allowed." }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Eclipse-Compatibility-Profiler")
                if (token != null) {
                    require(url.host == "api.github.com") { "Unexpected token recipient." }
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type",
                        if (url.host == "github.com") "application/x-www-form-urlencoded" else "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode !in 200..299) throw HttpFailure(connection.responseCode)
                val raw = connection.inputStream.use { EclipseBundles.readLimited(it, 512 * 1024) }
                val text = raw.toString(Charsets.UTF_8)
                return if (text.startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
            } finally { connection.disconnect() }
        }
    }
}
