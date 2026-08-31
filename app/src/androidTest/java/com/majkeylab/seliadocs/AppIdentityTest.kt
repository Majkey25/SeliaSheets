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

        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("com.majkeylab.seliadocs", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
        assertEquals("seliadocs.db", SeliaDocsDatabase.FILE_NAME)
    }

    @Test
    fun debugLauncherLabelIsDistinctFromRelease() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("SeliaSheets Debug", context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
