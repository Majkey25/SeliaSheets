package com.majkeylab.seliadocs.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

internal fun withBoundPdfSandbox(context: Context, healthyPdf: File, test: (IPdfRenderService) -> Unit) {
    val connected = CountDownLatch(1)
    var binder: IBinder? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) = Unit
        override fun onNullBinding(name: ComponentName?) { connected.countDown() }
        override fun onBindingDied(name: ComponentName?) { connected.countDown() }
    }
    try {
        check(context.bindService(Intent(context, PdfRenderService::class.java), connection, Context.BIND_AUTO_CREATE))
        check(connected.await(10, TimeUnit.SECONDS)) { "PDF sandbox binding timed out" }
        val service = requireNotNull(IPdfRenderService.Stub.asInterface(binder)) { "PDF sandbox returned no binder" }
        test(service)
        // A dead sandbox must not make a leaked descriptor look closed.
        ParcelFileDescriptor.open(healthyPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val result = service.inspect(descriptor)
            assertTrue(result.getBoolean(PdfProtocol.SUCCESS))
            assertNotEquals(Process.myUid(), result.getInt(PdfProtocol.SANDBOX_UID))
        }
    } finally {
        context.unbindService(connection)
    }
}

internal fun assertPipeReaderClosed(reader: ParcelFileDescriptor, writer: ParcelFileDescriptor) {
    assertTrue(reader.fileDescriptor.valid())
    assertTrue(writer.fileDescriptor.valid())
    reader.close()
    awaitPipeClosure(writer, OsConstants.POLLERR)
    ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
        val failure = assertThrows(IOException::class.java) { output.write(1) }
        assertEquals(OsConstants.EPIPE, (failure.cause as ErrnoException).errno)
    }
}

internal fun awaitPipeClosure(descriptor: ParcelFileDescriptor, event: Int) {
    // Older Binder versions release transport descriptors after replying.
    val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = 0 }
    assertEquals(1, Os.poll(arrayOf(poll), 2_000))
    assertTrue(poll.revents.toInt() and event != 0)
}
