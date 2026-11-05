package org.magora.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IngestQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(n: Int) = """{"n":$n}"""

    @Test
    fun queueOrderSurvivesARestart() {
        val dir = tmp.newFolder("ingest")
        val first = IngestQueue(dir)
        for (i in 1..6) first.enqueue(if (i % 3 == 0) "detections" else "aci_logs", row(i))
        first.ack(first.peek()!!.seq)
        first.ack(first.peek()!!.seq)

        val restarted = IngestQueue(dir)
        assertEquals(listOf(row(3), row(4), row(5), row(6)), restarted.snapshot().map { it.json })
        assertEquals(listOf("detections", "aci_logs", "aci_logs", "detections"), restarted.snapshot().map { it.table })

        // New rows keep increasing sequence numbers after the restart.
        val added = restarted.enqueue("aci_logs", row(7))
        assertEquals(restarted.snapshot().dropLast(1).maxOf { it.seq } + 1, added.seq)

        val again = IngestQueue(dir)
        assertEquals((3..7).map { row(it) }, again.snapshot().map { it.json })
    }

    @Test
    fun drainedQueueStaysEmptyAfterRestart() {
        val dir = tmp.newFolder("ingest")
        val q = IngestQueue(dir)
        q.enqueue("aci_logs", row(1))
        q.ack(q.peek()!!.seq)
        assertNull(IngestQueue(dir).peek())
    }

    @Test
    fun parkedRowLeavesTheQueueAndIsKept() {
        val dir = tmp.newFolder("ingest")
        val q = IngestQueue(dir)
        q.enqueue("detections", row(1))
        q.enqueue("aci_logs", row(2))
        q.park(q.peek()!!.seq, 400)
        assertEquals(row(2), q.peek()!!.json)
        val restarted = IngestQueue(dir)
        assertEquals(1, restarted.parkedCount())
        assertEquals(listOf(row(2)), restarted.snapshot().map { it.json })
        assertEquals(1, File(dir, "parked.log").readLines().count { it.contains("\t400\t") })
    }

    @Test
    fun rowsOlderThanSevenDaysAreDropped() {
        val dir = tmp.newFolder("ingest")
        var now = 1_000_000_000_000L
        val q = IngestQueue(dir, clock = { now })
        q.enqueue("aci_logs", row(1))
        now += IngestQueue.SEVEN_DAYS_MILLIS / 2
        q.enqueue("aci_logs", row(2))
        now += IngestQueue.SEVEN_DAYS_MILLIS / 2 + 1
        val expired = mutableListOf<String>()
        assertEquals(row(2), q.peek { expired += it.json }!!.json)
        assertEquals(listOf(row(1)), expired)
        assertEquals(listOf(row(2)), IngestQueue(dir, clock = { now }).snapshot().map { it.json })
    }

    @Test
    fun expiryCompactsTheLogAndParkedRowsAgeOut() {
        val dir = tmp.newFolder("ingest")
        var now = 1_000_000_000_000L
        val q = IngestQueue(dir, clock = { now })
        q.enqueue("detections", row(0))
        q.park(q.peek()!!.seq, 400)
        // Compaction runs once COMPACT_EVERY rows are done, so stale lines stay bounded.
        for (i in 1..600) q.enqueue("aci_logs", row(i))
        now += IngestQueue.SEVEN_DAYS_MILLIS + 1
        q.enqueue("aci_logs", row(99))
        assertEquals(row(99), q.peek()!!.json)
        assertEquals(1, File(dir, "queue.log").readLines().size)
        val restarted = IngestQueue(dir, clock = { now })
        assertEquals(0, restarted.parkedCount())
        assertEquals(0, File(dir, "parked.log").readLines().count { it.isNotBlank() })
    }

    @Test
    fun parkedRowsAgeOutOnTheSameInstance() {
        val dir = tmp.newFolder("ingest")
        var now = 1_000_000_000_000L
        val q = IngestQueue(dir, clock = { now })
        q.enqueue("detections", row(1))
        q.park(q.peek()!!.seq, 422)
        assertEquals(1, q.parkedCount())
        now += IngestQueue.SEVEN_DAYS_MILLIS + IngestQueue.PARKED_TRIM_INTERVAL_MILLIS
        assertNull(q.peek())
        assertEquals(0, q.parkedCount())
    }

    @Test
    fun drainLockAdmitsOneHolderAndACancelledWaiterNeverTakesIt() {
        val q = IngestQueue(tmp.newFolder("ingest"))
        q.drainLock.lock() // worker A, still finishing a request
        val waiter = Thread { try { q.drainLock.lockInterruptibly(); q.drainLock.unlock() } catch (_: InterruptedException) {} }
        waiter.start()
        waiter.interrupt() // worker B stopped while waiting
        waiter.join(2_000)
        assertEquals(false, waiter.isAlive)
        assertEquals(true, q.drainLock.isHeldByCurrentThread)
        q.drainLock.unlock()
        assertEquals(false, q.drainLock.isLocked)
    }

    @Test
    fun partialAppendNeverMergesWithTheNextRow() {
        val dir = tmp.newFolder("ingest")
        val q = IngestQueue(dir)
        q.enqueue("aci_logs", row(1))
        q.failAppendAfterBytes = 12
        try {
            q.enqueue("detections", row(2))
            throw AssertionError("expected the injected failure")
        } catch (_: java.io.IOException) {
        }
        q.enqueue("aci_logs", row(3))
        // Process dies here; reload must see exactly the two whole rows, in order.
        assertEquals(listOf(row(1), row(3)), IngestQueue(dir).snapshot().map { it.json })
    }

    @Test
    fun oneQueueInstancePerDirectory() {
        val dir = tmp.newFolder("ingest")
        assertSame(IngestQueue.forDirectory(dir), IngestQueue.forDirectory(File(dir.path)))
    }

    @Test
    fun tornFinalLineIsIgnored() {
        val dir = tmp.newFolder("ingest")
        val q = IngestQueue(dir)
        q.enqueue("aci_logs", row(1))
        File(dir, "queue.log").appendText("2\taci_logs\t123\t{\"n\":")
        assertEquals(listOf(row(1)), IngestQueue(dir).snapshot().map { it.json })
    }

    @Test(expected = IllegalArgumentException::class)
    fun ackOutOfOrderIsRejected() {
        val q = IngestQueue(tmp.newFolder("ingest"))
        q.enqueue("aci_logs", row(1))
        val second = q.enqueue("aci_logs", row(2))
        q.ack(second.seq)
    }
}
