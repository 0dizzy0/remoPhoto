package com.remophoto.ui.components

import com.remophoto.data.remote.RemoteErrorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbFormPolicyTest {
    @Test
    fun validFormPassesValidation() {
        val errors = SmbFormPolicy.validate(
            SmbFormValues("照片库", "nas.local", "445", "Photos", "家庭", "reader", "", "secret")
        )
        assertTrue(errors.isEmpty)
    }

    @Test
    fun invalidFieldsHaveActionableMessages() {
        val errors = SmbFormPolicy.validate(
            SmbFormValues("", "", "70000", "", "../private", "", "", "")
        )
        assertEquals("端口必须在 1 到 65535 之间", errors.port)
        assertEquals("相册根目录不能包含上级路径或无效字符", errors.rootPath)
        assertEquals("请输入密码", errors.password)
    }

    @Test
    fun remoteErrorsNeverExposeServerDetails() {
        val message = SmbFormPolicy.actionableMessage(RemoteErrorCategory.AUTH_FAILED)
        assertEquals("认证失败，请检查用户名、域和密码", message)
    }
}
