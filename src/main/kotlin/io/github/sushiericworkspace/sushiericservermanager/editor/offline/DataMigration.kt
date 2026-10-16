package io.github.sushiericworkspace.sushiericservermanager.editor.offline

import java.io.File

interface DataMigration {
    val fromVersion: Int
    val toVersion: Int

    /**
     * 変換元を変更せず、変換結果を[target]へ出力します。
     */
    fun migrate(source: File, target: File)
}

class DataMigrationRegistry(
    migrations: List<DataMigration> = emptyList()
) {
    private val bySourceVersion = migrations.associateBy { it.fromVersion }

    init {
        require(migrations.all { it.toVersion == it.fromVersion + 1 }) {
            "データ形式の移行は連続するバージョン間で定義してください。"
        }
        require(bySourceVersion.size == migrations.size) {
            "同じ移行元バージョンを重複定義できません。"
        }
    }

    fun path(fromVersion: Int, toVersion: Int): List<DataMigration>? {
        if (fromVersion > toVersion) return null
        if (fromVersion == toVersion) return emptyList()

        val result = mutableListOf<DataMigration>()
        var current = fromVersion
        while (current < toVersion) {
            val migration = bySourceVersion[current] ?: return null
            result += migration
            current = migration.toVersion
        }
        return result
    }
}
