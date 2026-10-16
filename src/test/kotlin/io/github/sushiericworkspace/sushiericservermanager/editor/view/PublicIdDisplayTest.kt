package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

class PublicIdDisplayTest {
    @Test fun `直下IDを葉名だけへ分割する`() {
        assertEquals(PublicIdDisplayParts("", "test"), splitPublicIdForDisplay("test"))
    }

    @Test fun `多階層IDをディレクトリと葉名へ分割する`() {
        assertEquals(
            PublicIdDisplayParts("combat.sword.", "test_sword"),
            splitPublicIdForDisplay("combat.sword.test_sword")
        )
    }
}
