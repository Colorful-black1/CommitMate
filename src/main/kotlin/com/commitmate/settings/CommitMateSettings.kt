package com.commitmate.settings

import com.commitmate.llm.ExtraHeaders
import com.commitmate.llm.LlmConfig
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件配置。
 *
 * - 普通配置（Base URL、Model、Temperature 等）保存在 `commit-mate.xml`；
 * - API Key 只通过 IntelliJ PasswordSafe 保存，不会写入普通配置文件，也不会出现在日志中。
 */
@Service(Service.Level.APP)
@State(name = "CommitMateSettings", storages = [Storage("commit-mate.xml")])
class CommitMateSettings : PersistentStateComponent<CommitMateSettings.State> {

    class State {
        var baseUrl: String = LlmConfig.DEFAULT_BASE_URL
        var model: String = LlmConfig.DEFAULT_MODEL
        var temperature: Double = LlmConfig.DEFAULT_TEMPERATURE
        var maxDiffCharacters: Int = DEFAULT_MAX_DIFF_CHARACTERS
        var timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS

        /** 暂存区为空时，是否改用工作区未提交的修改。 */
        var useWorkingTreeChanges: Boolean = true

        /** 自定义请求头，每行 `Name: Value`；用于需要额外头的网关（如 opencode-go 的 X-Head-Session）。 */
        var extraHeaders: String = ""

        /** 稳定会话 ID，首次使用时生成并持久化，作为 `x-opencode-session` 发送。 */
        var sessionId: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var baseUrl: String
        get() = myState.baseUrl
        set(value) {
            myState.baseUrl = value.trim().ifBlank { LlmConfig.DEFAULT_BASE_URL }
        }

    var model: String
        get() = myState.model
        set(value) {
            myState.model = value.trim().ifBlank { LlmConfig.DEFAULT_MODEL }
        }

    var temperature: Double
        get() = myState.temperature
        set(value) {
            myState.temperature = value.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        }

    var maxDiffCharacters: Int
        get() = myState.maxDiffCharacters
        set(value) {
            myState.maxDiffCharacters = value.coerceIn(MIN_DIFF_CHARACTERS, MAX_DIFF_CHARACTERS)
        }

    var timeoutSeconds: Int
        get() = myState.timeoutSeconds
        set(value) {
            myState.timeoutSeconds = value.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        }

    var useWorkingTreeChanges: Boolean
        get() = myState.useWorkingTreeChanges
        set(value) {
            myState.useWorkingTreeChanges = value
        }

    var extraHeaders: String
        get() = myState.extraHeaders
        set(value) {
            myState.extraHeaders = value
        }

    /** 取得稳定的会话 ID；首次调用时生成并写入配置，重启后保持不变。 */
    @Synchronized
    fun stableSessionId(): String {
        if (myState.sessionId.isBlank()) {
            myState.sessionId = java.util.UUID.randomUUID().toString()
        }
        return myState.sessionId
    }

    /** 读取保存的 API Key；未配置或读取失败时返回 null。 */
    fun getApiKey(): String? = runCatching {
        PasswordStore.getInstance()?.getPassword(credentialAttributes())
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 保存 API Key；传入空白表示清除。 */
    fun setApiKey(apiKey: String?) {
        val store = PasswordStore.getInstance()
            ?: throw IllegalStateException("当前 IDE 未提供 PasswordSafe 凭据存储")
        store.setPassword(credentialAttributes(), apiKey?.takeIf { it.isNotBlank() })
    }

    fun hasApiKey(): Boolean = getApiKey() != null

    fun toLlmConfig(apiKey: String): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        readTimeoutMillis = timeoutSeconds * 1_000,
        extraHeaders = ExtraHeaders.parse(extraHeaders),
        sessionId = stableSessionId(),
    )

    private fun credentialAttributes(): CredentialAttributes =
        CredentialAttributesFactory.create(CREDENTIAL_SERVICE_NAME, CREDENTIAL_USER_NAME)

    companion object {
        const val DEFAULT_MAX_DIFF_CHARACTERS: Int = 60_000
        const val DEFAULT_TIMEOUT_SECONDS: Int = 120

        private const val MIN_TEMPERATURE = 0.0
        private const val MAX_TEMPERATURE = 2.0
        private const val MIN_DIFF_CHARACTERS = 2_000
        private const val MAX_DIFF_CHARACTERS = 400_000
        private const val MIN_TIMEOUT_SECONDS = 5
        private const val MAX_TIMEOUT_SECONDS = 600

        private const val CREDENTIAL_SERVICE_NAME = "Commit Mate"
        private const val CREDENTIAL_USER_NAME = "api-key"
    }
}
