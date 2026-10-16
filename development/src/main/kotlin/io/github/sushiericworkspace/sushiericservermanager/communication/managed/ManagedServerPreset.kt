package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.util.UUID

/** 初回に承認・EULA同意した起動条件。既存世代の借用には設定済みtemplateを使い、旧世代は保持します。 */
data class ManagedServerPreset(val owner: String, val artifactId: String, val seed: Long, val templateId: String) {
    init {
        require(Regex("[a-z0-9][a-z0-9-]{0,127}").matches(owner)) { "貸出ownerを設定してください。" }
        require(Regex("[0-9a-f]{64}").matches(artifactId)) { "配置済みbundle IDを設定してください。" }
        require(templateId.isEmpty() || Regex("[a-z0-9][a-z0-9-]{0,127}").matches(templateId)) { "リセット元templateを設定してください。" }
    }

    /** 空枠はseedから準備し、既存枠は承認済みtemplateから別世代を作ります。 */
    fun preparation(hasGeneration: Boolean): Pair<String, JsonObject> {
        if (hasGeneration) require(templateId.isNotEmpty()) { "既存枠を初期化するtemplateを初回設定で選んでください。" }
        return (if (hasGeneration) "reset" else "prepare") to buildJsonObject {
            put("artifactId", artifactId); put("generationId", "manager-" + UUID.randomUUID().toString().replace("-", ""))
            put("acceptEula", true)
            if (hasGeneration) put("templateId", templateId) else put("seed", seed)
        }
    }
}
