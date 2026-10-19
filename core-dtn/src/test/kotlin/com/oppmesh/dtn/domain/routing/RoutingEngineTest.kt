package com.oppmesh.dtn.domain.routing

import com.oppmesh.dtn.domain.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingEngineTest {

    private val now = 1_000_000L

    @Test
    fun testDirectDelivery_onlyForwardsToDestination() {
        val packet = SimplePacket(
            packetId = "p1",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Direct message",
            strategy = RoutingStrategy.DIRECT_DELIVERY,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now
        )

        // Relay node NODE-C in range -> should not forward
        val relayResult = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 100
        )
        assertTrue(relayResult.isEmpty())

        // Destination node NODE-B in range -> should forward
        val destResult = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-B",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 100
        )
        assertEquals(1, destResult.size)
        assertEquals("p1", destResult[0].packetDto.packetId)
        assertEquals(1, destResult[0].packetDto.sprayCopiesToTransfer)
    }

    @Test
    fun testEpidemic_forwardsToAnyUnawarePeer() {
        val packet = SimplePacket(
            packetId = "p2",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Epidemic message",
            strategy = RoutingStrategy.EPIDEMIC,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now
        )

        val result = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 50
        )
        assertEquals(1, result.size)
        assertEquals("p2", result[0].packetDto.packetId)
        assertEquals(1, result[0].packetDto.sprayCopiesToTransfer)
        assertEquals(1, result[0].newLocalCopiesRemaining)
    }

    @Test
    fun testSprayAndWait_sprayPhaseHalving() {
        val packet = SimplePacket(
            packetId = "p3",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Spray message L=4",
            strategy = RoutingStrategy.SPRAY_AND_WAIT,
            sprayCopiesRemaining = 4,
            createdAtEpochMs = now
        )

        // Transfer to intermediate relay NODE-C
        val result = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 10
        )
        assertEquals(1, result.size)
        assertEquals(2, result[0].packetDto.sprayCopiesToTransfer) // 4 / 2 = 2
        assertEquals(2, result[0].newLocalCopiesRemaining)        // 4 - 2 = 2

        // Odd number of copies: L=3 -> gives 1, keeps 2
        val oddPacket = packet.copy(sprayCopiesRemaining = 3)
        val oddResult = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(oddPacket),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 10
        )
        assertEquals(1, oddResult.size)
        assertEquals(1, oddResult[0].packetDto.sprayCopiesToTransfer) // 3 / 2 = 1
        assertEquals(2, oddResult[0].newLocalCopiesRemaining)        // 3 - 1 = 2
    }

    @Test
    fun testSprayAndWait_waitPhaseBlocksRelays() {
        val packet = SimplePacket(
            packetId = "p4",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Wait phase message L=1",
            strategy = RoutingStrategy.SPRAY_AND_WAIT,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now
        )

        // Relay encounter in wait phase -> blocked
        val relayResult = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 20
        )
        assertTrue(relayResult.isEmpty())

        // Direct encounter in wait phase -> delivered
        val destResult = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(packet),
            peerNodeId = "NODE-B",
            peerKnownPacketIds = emptySet(),
            peerDeliveredPacketIds = emptySet(),
            currentEpochMs = now + 20
        )
        assertEquals(1, destResult.size)
        assertEquals("p4", destResult[0].packetDto.packetId)
        assertEquals(1, destResult[0].packetDto.sprayCopiesToTransfer)
    }

    @Test
    fun testAntiEntropyAndTTL() {
        val expiredPacket = SimplePacket(
            packetId = "p5",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Expired message",
            strategy = RoutingStrategy.EPIDEMIC,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now,
            ttlMillis = 1000L
        )

        val alreadyKnownPacket = SimplePacket(
            packetId = "p6",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Already known message",
            strategy = RoutingStrategy.EPIDEMIC,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now
        )

        val alreadyDeliveredPacket = SimplePacket(
            packetId = "p7",
            sourceNodeId = "NODE-A",
            destinationNodeId = "NODE-B",
            payloadText = "Already delivered message",
            strategy = RoutingStrategy.EPIDEMIC,
            sprayCopiesRemaining = 1,
            createdAtEpochMs = now
        )

        val result = RoutingEngine.selectPacketsForPeer(
            localPackets = listOf(expiredPacket, alreadyKnownPacket, alreadyDeliveredPacket),
            peerNodeId = "NODE-C",
            peerKnownPacketIds = setOf("p6"),
            peerDeliveredPacketIds = setOf("p7"),
            currentEpochMs = now + 5000L // Exceeds p5's 1000ms TTL
        )
        assertTrue("All packets should be filtered out", result.isEmpty())
    }
}
