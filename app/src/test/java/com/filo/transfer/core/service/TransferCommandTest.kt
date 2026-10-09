package com.filo.transfer.core.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferCommandTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testStartSendCommandIntentRoundTrip() {
        val original = TransferCommand.StartSend(
            transferId = "tx-123",
            targetHost = "192.168.1.50",
            targetPort = 8888,
            filePaths = listOf("/path/to/file1.png", "/path/to/file2.mp4"),
            deviceName = "AlicePhone"
        )

        val intent = TransferCommand.toIntent(context, original)
        assertEquals(TransferCommand.ACTION_START_SEND, intent.action)

        val parsed = TransferCommand.fromIntent(intent) as? TransferCommand.StartSend
        assertNotNull(parsed)
        assertEquals(original.transferId, parsed!!.transferId)
        assertEquals(original.targetHost, parsed.targetHost)
        assertEquals(original.targetPort, parsed.targetPort)
        assertEquals(original.filePaths, parsed.filePaths)
        assertEquals(original.deviceName, parsed.deviceName)
    }

    @Test
    fun testStartSendCommandWithRelativePathsIntentRoundTrip() {
        val original = TransferCommand.StartSend(
            transferId = "tx-folder",
            targetHost = "192.168.1.50",
            targetPort = 50222,
            filePaths = listOf(
                "content://com.android.externalstorage.documents/tree/abc/document/1",
                "content://com.android.externalstorage.documents/tree/abc/document/2"
            ),
            deviceName = "AlicePhone",
            relativePaths = listOf("docs/report.pdf", "photos/img.png")
        )

        val intent = TransferCommand.toIntent(context, original)
        assertEquals(TransferCommand.ACTION_START_SEND, intent.action)

        val parsed = TransferCommand.fromIntent(intent) as? TransferCommand.StartSend
        assertNotNull(parsed)
        assertEquals(original.filePaths, parsed!!.filePaths)
        assertEquals(original.relativePaths, parsed.relativePaths)
    }

    @Test
    fun testStartSendCommandDefaultsToEmptyRelativePaths() {
        val original = TransferCommand.StartSend(
            transferId = "tx-plain",
            targetHost = "192.168.1.50",
            filePaths = listOf("a.txt")
        )

        assertTrue(original.relativePaths.isEmpty())

        val intent = TransferCommand.toIntent(context, original)
        val parsed = TransferCommand.fromIntent(intent) as? TransferCommand.StartSend
        assertNotNull(parsed)
        assertTrue(parsed!!.relativePaths.isEmpty())
    }

    @Test
    fun testStartSendRejectsMisalignedRelativePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartSend(
                transferId = "tx-bad",
                targetHost = "127.0.0.1",
                filePaths = listOf("a.txt"),
                relativePaths = listOf("a.txt", "b.txt")
            )
        }
    }

    @Test
    fun testStartSendIntentWithoutRelativePathsDecodesToEmpty() {
        // An intent written by a caller that never set EXTRA_RELATIVE_PATHS
        val original = TransferCommand.StartSend(
            transferId = "tx-legacy",
            targetHost = "192.168.1.50",
            filePaths = listOf("a.txt", "b.txt")
        )

        val intent = TransferCommand.toIntent(context, original)
        intent.removeExtra(TransferCommand.EXTRA_RELATIVE_PATHS)

        val parsed = TransferCommand.fromIntent(intent) as? TransferCommand.StartSend
        assertNotNull(parsed)
        assertTrue(parsed!!.relativePaths.isEmpty())
    }

    @Test
    fun testStartReceiveCommandIntentRoundTrip() {
        val original = TransferCommand.StartReceive(
            transferId = "rx-456",
            listenPort = 9999,
            destinationDir = "/storage/emulated/0/Download",
            deviceName = "BobTablet"
        )

        val intent = TransferCommand.toIntent(context, original)
        assertEquals(TransferCommand.ACTION_START_RECEIVE, intent.action)

        val parsed = TransferCommand.fromIntent(intent) as? TransferCommand.StartReceive
        assertNotNull(parsed)
        assertEquals(original.transferId, parsed!!.transferId)
        assertEquals(original.listenPort, parsed.listenPort)
        assertEquals(original.destinationDir, parsed.destinationDir)
        assertEquals(original.deviceName, parsed.deviceName)
    }

    @Test
    fun testControlCommandsIntentRoundTrip() {
        val pauseIntent = TransferCommand.toIntent(context, TransferCommand.Pause)
        assertEquals(TransferCommand.Pause, TransferCommand.fromIntent(pauseIntent))

        val resumeIntent = TransferCommand.toIntent(context, TransferCommand.Resume)
        assertEquals(TransferCommand.Resume, TransferCommand.fromIntent(resumeIntent))

        val cancelIntent = TransferCommand.toIntent(context, TransferCommand.Cancel)
        assertEquals(TransferCommand.Cancel, TransferCommand.fromIntent(cancelIntent))

        val stopIntent = TransferCommand.toIntent(context, TransferCommand.Stop)
        assertEquals(TransferCommand.Stop, TransferCommand.fromIntent(stopIntent))
    }

    @Test
    fun testNullOrUnknownIntentReturnsNull() {
        assertNull(TransferCommand.fromIntent(null))

        val emptyIntent = Intent()
        assertNull(TransferCommand.fromIntent(emptyIntent))

        val unknownIntent = Intent("com.unknown.ACTION")
        assertNull(TransferCommand.fromIntent(unknownIntent))
    }

    @Test
    fun testMalformedStartSendIntentReturnsNull() {
        val intent = Intent(TransferCommand.ACTION_START_SEND).apply {
            // Missing transferId and targetHost
            putExtra(TransferCommand.EXTRA_PORT, 8888)
        }
        assertNull(TransferCommand.fromIntent(intent))
    }

    @Test
    fun testParameterValidation() {
        // Blank transfer ID
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartSend(
                transferId = "",
                targetHost = "127.0.0.1",
                filePaths = listOf("a")
            )
        }

        // Blank target host
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartSend(
                transferId = "id",
                targetHost = "  ",
                filePaths = listOf("a")
            )
        }

        // Invalid port
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartSend(
                transferId = "id",
                targetHost = "127.0.0.1",
                targetPort = 70000,
                filePaths = listOf("a")
            )
        }

        // Empty files
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartSend(
                transferId = "id",
                targetHost = "127.0.0.1",
                filePaths = emptyList()
            )
        }

        // Blank destination dir
        assertThrows(IllegalArgumentException::class.java) {
            TransferCommand.StartReceive(
                transferId = "id",
                destinationDir = ""
            )
        }
    }
}
