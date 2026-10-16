package io.github.sushiericworkspace.sushiericservermanager.config

enum class AuthenticationType(
    val storedValue: String,
    val displayName: String
) {
    GENERATED_KEY(
        storedValue = "GENERATED_KEY",
        displayName = "アプリ内で新しい鍵を生成"
    ),
    EXISTING_PRIVATE_KEY(
        storedValue = "EXISTING_PRIVATE_KEY",
        displayName = "既存の秘密鍵を選択"
    );

    companion object {
        fun fromStoredValue(value: String?): AuthenticationType {
            return entries.firstOrNull { it.storedValue == value }
                ?: EXISTING_PRIVATE_KEY
        }

        fun fromDisplayName(value: String?): AuthenticationType {
            return entries.firstOrNull { it.displayName == value }
                ?: EXISTING_PRIVATE_KEY
        }
    }
}
