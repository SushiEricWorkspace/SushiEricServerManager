package io.github.sushiericworkspace.sushiericservermanager.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 設定ディレクトリ移行で取り残された生成鍵のパス修復を検証する。
 */
class GeneratedKeyPathRepairTest {

    private val legacy = File("C:${File.separator}base${File.separator}Legacy")
    private val current = File("C:${File.separator}base${File.separator}Current")

    @Test
    fun `旧ディレクトリ配下の生成鍵は新ディレクトリへ向け直す`() {
        val config =
            ServerConfig(
                listOf(
                    profile(
                        name = "generated",
                        key = path(legacy, "ssh", "profile-abc.key"),
                        generatedKey = true
                    )
                )
            )

        val repaired = repair(config)

        assertEquals(
            path(current, "ssh", "profile-abc.key"),
            repaired.list.single().key
        )
    }

    @Test
    fun `利用者が用意した鍵は書き換えない`() {
        val userKey =
            path(File("C:${File.separator}Users${File.separator}me"), ".ssh", "id_ed25519")

        val config =
            ServerConfig(
                listOf(
                    profile(
                        name = "user-key",
                        key = userKey,
                        generatedKey = false
                    )
                )
            )

        val repaired = repair(config)

        assertSame(config, repaired)
        assertEquals(userKey, repaired.list.single().key)
    }

    @Test
    fun `設定ディレクトリ外を指す生成鍵は書き換えない`() {
        val outside =
            path(File("D:${File.separator}keys"), "profile-abc.key")

        val config =
            ServerConfig(
                listOf(
                    profile(
                        name = "outside",
                        key = outside,
                        generatedKey = true
                    )
                )
            )

        assertEquals(outside, repair(config).list.single().key)
    }

    @Test
    fun `新ディレクトリを既に指している場合は書き換えない`() {
        val alreadyCurrent =
            path(current, "ssh", "profile-abc.key")

        val config =
            ServerConfig(
                listOf(
                    profile(
                        name = "current",
                        key = alreadyCurrent,
                        generatedKey = true
                    )
                )
            )

        assertSame(config, repair(config))
    }

    @Test
    fun `対象が無い場合は同じインスタンスを返す`() {
        val config = ServerConfig(emptyList())

        assertSame(config, repair(config))
    }

    @Test
    fun `対象と対象外が混在しても対象だけを書き換える`() {
        val userKey =
            path(File("C:${File.separator}Users${File.separator}me"), ".ssh", "id_ed25519")

        val config =
            ServerConfig(
                listOf(
                    profile("a", path(legacy, "ssh", "a.key"), generatedKey = true),
                    profile("b", userKey, generatedKey = false),
                    profile("c", path(legacy, "ssh", "c.key"), generatedKey = true)
                )
            )

        val repaired = repair(config)

        assertEquals(
            path(current, "ssh", "a.key"),
            repaired.list[0].key
        )

        assertEquals(userKey, repaired.list[1].key)

        assertEquals(
            path(current, "ssh", "c.key"),
            repaired.list[2].key
        )
    }

    @Test
    fun `鍵以外の項目は変更しない`() {
        val original =
            profile(
                name = "generated",
                key = path(legacy, "ssh", "profile-abc.key"),
                generatedKey = true
            ).copy(managementPort = 25581)

        val repaired =
            repair(ServerConfig(listOf(original))).list.single()

        assertEquals(original.name, repaired.name)
        assertEquals(original.host, repaired.host)
        assertEquals(original.port, repaired.port)
        assertEquals(original.user, repaired.user)
        assertEquals(original.path, repaired.path)
        assertEquals(original.generatedKey, repaired.generatedKey)
        assertEquals(original.managementPort, repaired.managementPort)
    }

    private fun repair(config: ServerConfig): ServerConfig =
        GeneratedKeyPathRepair.repair(
            config = config,
            legacyDirectory = legacy,
            currentDirectory = current
        )

    private fun path(base: File, vararg parts: String): String =
        parts.fold(base) { file, part -> File(file, part) }.path

    private fun profile(
        name: String,
        key: String,
        generatedKey: Boolean
    ): ServerProfile =
        ServerProfile(
            name = name,
            host = "127.0.0.1",
            port = 22,
            user = "user",
            path = "/tmp",
            key = key,
            generatedKey = generatedKey
        )
}
