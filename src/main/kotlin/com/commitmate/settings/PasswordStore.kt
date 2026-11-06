package com.commitmate.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.openapi.application.ApplicationManager
import java.lang.reflect.Method

/**
 * 跨 IDE 版本的凭据存储适配器。
 *
 * IntelliJ Platform 在 2025.3 把 PasswordSafe 从 `com.intellij.credentialStore`
 * 迁移到 `com.intellij.ide.passwordSafe`，并改为 application service；而 2025.2 及更早
 * 版本仍然是旧的静态 `getInstance()`。为了让同一份插件包既能装在 2024.1，又能装在 2025.3+，
 * 这里通过反射按版本查找实现，避免在编译期绑定某一代类名。
 *
 * 优先级：
 * 1. 2025.3+：`com.intellij.ide.passwordSafe.PasswordSafe`（application service）
 * 2. 2025.2 及更早：`com.intellij.credentialStore.PasswordSafe.getInstance()`
 */
internal class PasswordStore private constructor(private val store: Any) {

    fun getPassword(attributes: CredentialAttributes): String? = runCatching {
        findMethod("getPassword", CredentialAttributes::class.java)?.invoke(store, attributes) as String?
    }.getOrNull()

    fun setPassword(attributes: CredentialAttributes, password: String?) {
        val setPassword = findMethod("setPassword", CredentialAttributes::class.java, String::class.java)
        if (setPassword != null) {
            setPassword.invoke(store, attributes, password)
            return
        }
        val setWithString = findMethod("set", CredentialAttributes::class.java, String::class.java)
        if (setWithString != null) {
            setWithString.invoke(store, attributes, password)
            return
        }
        throw IllegalStateException("当前 IDE 版本的凭据存储不支持写入")
    }

    fun isAvailable(): Boolean = findMethod("getPassword", CredentialAttributes::class.java) != null

    private fun findMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        val fromInstance = store.javaClass.methods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == parameterTypes.size &&
                candidate.parameterTypes.withIndex().all { (index, type) ->
                    type.isAssignableFrom(parameterTypes[index]) || parameterTypes[index].isAssignableFrom(type)
                }
        }
        if (fromInstance != null) return fromInstance

        // 兜底：从 CredentialStore 接口上查找（Kotlin 默认方法可能只声明在接口上）
        return runCatching {
            Class.forName("com.intellij.credentialStore.CredentialStore")
                .getMethod(name, *parameterTypes)
                .takeIf { it.declaringClass.isInstance(store) }
        }.getOrNull()
    }

    companion object {

        fun getInstance(): PasswordStore? = modernStore() ?: legacyStore()

        /** 2025.3+：PasswordSafe 是 application service。 */
        private fun modernStore(): PasswordStore? {
            val instance = runCatching {
                val type = Class.forName("com.intellij.ide.passwordSafe.PasswordSafe")
                ApplicationManager.getApplication()?.getService(type)
            }.getOrNull() ?: return null
            return PasswordStore(instance).takeIf { it.isAvailable() }
        }

        /** 2025.2 及更早：静态 `getInstance()`。 */
        private fun legacyStore(): PasswordStore? {
            val instance = runCatching {
                val type = Class.forName("com.intellij.credentialStore.PasswordSafe")
                type.getMethod("getInstance").invoke(null)
            }.getOrNull() ?: return null
            return PasswordStore(instance).takeIf { it.isAvailable() }
        }
    }
}
