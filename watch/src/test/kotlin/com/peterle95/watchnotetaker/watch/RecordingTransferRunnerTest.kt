package com.peterle95.watchnotetaker.watch

import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingTransferRunnerTest {
    private val directory = Files.createTempDirectory("watch-transfer").toFile()
    private val recording = QueuedWatchAudio(
        "123e4567-e89b-12d3-a456-426614174000",
        directory.resolve("123e4567-e89b-12d3-a456-426614174000.m4a").apply { writeText("audio") },
        12,
    )

    @Test
    fun `no connected node leaves recording queued`() {
        val states = mutableListOf<TransferState>()
        val queue = mutableListOf(recording)

        RecordingTransferRunner({ queue.toList() }, FakeTransport(), states::add).run()

        assertEquals(listOf(TransferState.Connecting, TransferState.Disconnected), states)
        assertEquals(listOf(recording), queue)
    }

    @Test
    fun `nearby node wins then stable node ID breaks ties`() {
        val nodes = listOf(
            TransferNode("z-phone", isNearby = true),
            TransferNode("a-tablet", isNearby = false),
            TransferNode("a-phone", isNearby = true),
        )

        assertEquals("a-phone", selectPhoneNode(nodes)?.id)
    }

    @Test
    fun `open and write failures leave recording queued`() {
        listOf("open", "write").forEach { failure ->
            val queue = mutableListOf(recording)
            val states = mutableListOf<TransferState>()

            RecordingTransferRunner(
                { queue.toList() },
                FakeTransport(listOf(TransferNode("phone", true)), failure),
                states::add,
            ).run()

            assertEquals(TransferState.Failed, states.last())
            assertEquals(listOf(recording), queue)
        }
    }

    @Test
    fun `repeated sends are idempotent and wait for acknowledgement`() {
        val transport = FakeTransport(listOf(TransferNode("phone", true)))
        val queue = mutableListOf(recording)
        val states = mutableListOf<TransferState>()
        val runner = RecordingTransferRunner({ queue.toList() }, transport, states::add)

        runner.run()
        runner.run()

        assertEquals(listOf(recording, recording), transport.sent)
        assertEquals(TransferState.WaitingForAcknowledgement, states.last())
        assertEquals(listOf(recording), queue)
    }

    @Test
    fun `restart retains unacknowledged queue`() {
        val queue = mutableListOf(recording)
        RecordingTransferRunner(
            { queue.toList() },
            FakeTransport(listOf(TransferNode("phone", true)), "write"),
        ).run()

        val restartedTransport = FakeTransport(listOf(TransferNode("phone", true)))
        RecordingTransferRunner({ queue.toList() }, restartedTransport).run()

        assertEquals(listOf(recording), restartedTransport.sent)
        assertEquals(listOf(recording), queue)
    }

    @Test
    fun `only one process transfer runs at a time`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = object : RecordingTransport {
            override fun connectedNodes() = listOf(TransferNode("phone", true))
            override fun send(node: TransferNode, recording: QueuedWatchAudio) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
        }
        val first = RecordingTransferRunner({ listOf(recording) }, transport)
        val second = RecordingTransferRunner({ listOf(recording) }, transport)

        val worker = thread { first.run() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(second.run())
        release.countDown()
        worker.join()
    }

    @Test
    fun `a scheduled transfer prevents duplicate scheduling`() {
        var pending: Runnable? = null
        val executor = Executor { pending = it }
        val transport = FakeTransport(listOf(TransferNode("phone", true)))

        assertTrue(RecordingTransferRunner({ listOf(recording) }, transport).schedule(executor))
        assertFalse(RecordingTransferRunner({ listOf(recording) }, transport).schedule(executor))

        pending!!.run()
        assertEquals(listOf(recording), transport.sent)
    }
}

private class FakeTransport(
    private val nodes: List<TransferNode> = emptyList(),
    private val failure: String? = null,
) : RecordingTransport {
    val sent = mutableListOf<QueuedWatchAudio>()

    override fun connectedNodes(): List<TransferNode> =
        nodes

    override fun send(node: TransferNode, recording: QueuedWatchAudio) {
        if (failure != null) error("$failure failed")
        sent += recording
    }
}
