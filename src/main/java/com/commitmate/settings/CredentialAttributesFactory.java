package com.commitmate.settings;

import com.intellij.credentialStore.CredentialAttributes;

/**
 * 创建 {@link CredentialAttributes} 的桥接工厂。
 *
 * <p>IntelliJ Platform 2025.3 起，CredentialAttributes 的主构造函数把 requestor 参数标记为废弃，
 * 而 Kotlin 调用带默认参数的构造函数时会经过一个被标记为废弃的合成构造函数，
 * Plugin Verifier 会因此报告 "usage of deprecated API"。</p>
 *
 * <p>这里用 Java 直接绑定到未废弃的 (serviceName, userName) 重载，规避该误报，
 * 同时保证 2024.1 - 2025.3+ 的字节码兼容。</p>
 */
public final class CredentialAttributesFactory {

    private CredentialAttributesFactory() {
    }

    public static CredentialAttributes create(String serviceName, String userName) {
        return new CredentialAttributes(serviceName, userName);
    }
}
