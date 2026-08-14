package com.sa.aidesktop.core.github

import android.content.Context
import com.sa.aidesktop.core.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class GitHubAccount(
    val id: String,
    val login: String,
    val active: Boolean
)

sealed interface GitHubResult<out T> {
    data class Success<T>(val value: T): GitHubResult<T>
    data class Failure(val message: String, val authenticationRequired: Boolean = false): GitHubResult<Nothing>
}

data class GitHubRepository(
    val name: String,
    val fullName: String,
    val private: Boolean,
    val cloneUrl: String,
    val sshUrl: String,
    val htmlUrl: String
)

class GitHubAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("sa_github_accounts", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)
    private val idsKey = "account_ids"
    private val activeKey = "active_id"

    fun accounts(): List<GitHubAccount> =
        ids().mapNotNull { id ->
            val login = prefs.getString("login_$id", null) ?: return@mapNotNull null
            GitHubAccount(id, login, id == prefs.getString(activeKey, null))
        }

    fun active(): GitHubAccount? = accounts().firstOrNull { it.active }

    fun token(id: String = prefs.getString(activeKey, null).orEmpty()): String? =
        id.takeIf { it.isNotBlank() }?.let { secure.get(tokenKey(it)) }

    fun saveVerified(login: String, token: String, makeActive: Boolean = true): GitHubAccount {
        require(login.isNotBlank() && token.isNotBlank())
        val id = UUID.randomUUID().toString()
        prefs.edit()
            .putStringSet(idsKey, ids().toMutableSet().apply { add(id) })
            .putString("login_$id", login)
            .apply()
        secure.put(tokenKey(id), token)
        if (makeActive) prefs.edit().putString(activeKey, id).apply()
        return GitHubAccount(id, login, makeActive)
    }

    fun setActive(id: String): Boolean =
        if (ids().contains(id)) { prefs.edit().putString(activeKey, id).apply(); true } else false

    fun remove(id: String) {
        secure.remove(tokenKey(id))
        prefs.edit()
            .remove("login_$id")
            .putStringSet(idsKey, ids().filterNot { it == id }.toSet())
            .apply()
        if (prefs.getString(activeKey, null) == id) {
            prefs.edit().putString(activeKey, ids().firstOrNull()).apply()
        }
    }

    fun clearAll() {
        ids().forEach { secure.remove(tokenKey(it)) }
        prefs.edit().clear().apply()
    }

    private fun ids(): Set<String> = prefs.getStringSet(idsKey, emptySet()).orEmpty()
    private fun tokenKey(id: String) = "github_token_$id"
}

class GitHubApiClient(private val accounts: GitHubAccountStore) {
    suspend fun verifyToken(token: String): GitHubResult<GitHubAccount> =
        withContext(Dispatchers.IO) {
            request("GET", "/user", token).let { response ->
                if (response.code in 200..299) {
                    val json = JSONObject(response.body)
                    val login = json.optString("login")
                    if (login.isBlank()) GitHubResult.Failure("GitHub returned no account login.")
                    else GitHubResult.Success(accounts.saveVerified(login, token))
                } else failure(response)
            }
        }

    suspend fun listRepositories(): GitHubResult<List<GitHubRepository>> =
        authenticated { token ->
            val response = request("GET", "/user/repos?per_page=100&sort=updated", token)
            if (response.code !in 200..299) return@authenticated failure(response)
            runCatching {
                val array = JSONArray(response.body)
                buildList {
                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        add(GitHubRepository(
                            o.optString("name"),
                            o.optString("full_name"),
                            o.optBoolean("private"),
                            o.optString("clone_url"),
                            o.optString("ssh_url"),
                            o.optString("html_url")
                        ))
                    }
                }
            }.fold(
                onSuccess = { GitHubResult.Success(it) },
                onFailure = { GitHubResult.Failure("Could not parse GitHub repository response.") }
            )
        }

    suspend fun createRepository(name: String, description: String = "", private: Boolean = true): GitHubResult<GitHubRepository> =
        authenticated { token ->
            if (!name.matches(Regex("[A-Za-z0-9._-]{1,100}")))
                return@authenticated GitHubResult.Failure("Invalid GitHub repository name.")
            val body = JSONObject()
                .put("name", name)
                .put("description", description.take(350))
                .put("private", private)
                .toString()
            val response = request("POST", "/user/repos", token, body)
            if (response.code !in 200..299) return@authenticated failure(response)
            runCatching {
                val o = JSONObject(response.body)
                GitHubRepository(
                    o.optString("name"), o.optString("full_name"), o.optBoolean("private"),
                    o.optString("clone_url"), o.optString("ssh_url"), o.optString("html_url")
                )
            }.fold(
                onSuccess = { GitHubResult.Success(it) },
                onFailure = { GitHubResult.Failure("Could not parse GitHub repository response.") }
            )
        }

    private suspend fun <T> authenticated(block: suspend (String) -> GitHubResult<T>): GitHubResult<T> {
        val token = accounts.token()
            ?: return GitHubResult.Failure("No verified GitHub account is configured.", authenticationRequired = true)
        return block(token)
    }

    private data class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, token: String, body: String? = null): Response {
        return try {
            val connection = (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", "SA-AI-Desktop")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Response(599, e.message ?: "Network request failed.")
        }
    }

    private fun <T> failure(response: Response): GitHubResult<T> {
        val message = runCatching { JSONObject(response.body).optString("message") }.getOrNull()
            .takeUnless { it.isNullOrBlank() } ?: "GitHub request failed (HTTP ${response.code})."
        return GitHubResult.Failure(
            message,
            authenticationRequired = response.code == 401 || response.code == 403 && message.contains("credential", true)
        )
    }
}
