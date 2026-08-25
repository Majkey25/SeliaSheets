package com.majkeylab.seliadocs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIdentityTest {
    @Test
    fun installedPackageAndDatabaseUseSeliaDocsIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("com.majkeylab.seliadocs", context.packageName)
        assertEquals("seliadocs.db", SeliaDocsDatabase.FILE_NAME)
    }
}
