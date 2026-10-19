package com.oppmesh.simulator

import com.oppmesh.dtn.domain.model.RoutingStrategy
import com.oppmesh.dtn.domain.routing.RoutingEngine
import com.oppmesh.dtn.domain.routing.SimplePacket
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

enum class MobilityModel {
    RANDOM_WAYPOINT,
    LINEAR_RELAY
}

data class SimConfig(
    val suiteName: String,
    val mobilityModel: MobilityModel = MobilityModel.RANDOM_WAYPOINT,
    val numNodes: Int,
    val radioRangeMeters: Double,
    val failureProb: Double = 0.05,
    val worldSizeMeters: Double = 350.0,
    val durationSec: Int = 3600,
    val messageGenWindowSec: Int = 1800,
    val totalMessages: Int = 50,
    val ttlSec: Int = 1800,
    val sprayCopiesL: Int = 6,
    val bufferCapacity: Int = 50,
    val contactHandshakeSec: Int = 2,
    val syncIntervalSec: Int = 4,
    val maxPacketsPerTransfer: Int = 5,
    val linearDistanceMeters: Double = 100.0
)

data class SimResult(
    val config: SimConfig,
    val strategy: RoutingStrategy,
    val deliveryRatio: Double,
    val avgDelaySec: Double,
    val commonSetDelaySec: Double,
    val avgHops: Double,
    val transmissionsPerDelivered: Double,
    val totalTransmissions: Int,
    val totalContacts: Int,
    val failedContacts: Int,
    val droppedPackets: Int,
    val deliveredMsgIds: Set<Int>
)

class NodeState(
    val id: String,
    val nodeIndex: Int,
    var x: Double,
    var y: Double,
    val mobilityModel: MobilityModel,
    val config: SimConfig,
    val rng: Random
) {
    // Mobility state for RANDOM_WAYPOINT
    var targetX = rng.nextDouble(0.0, config.worldSizeMeters)
    var targetY = rng.nextDouble(0.0, config.worldSizeMeters)
    var speed = rng.nextDouble(1.2, 1.8) // Human walking: ~4.3 - 6.5 km/h
    var pauseRemaining = rng.nextInt(0, 15)

    // Mobility state for LINEAR_RELAY
    var shuttleDir = 1.0 // 1.0 = towards destination, -1.0 = towards source

    // DTN Packet Buffer: packetId -> SimplePacket
    val buffer = LinkedHashMap<String, SimplePacket>()
    val knownPacketIds = HashSet<String>()
    val deliveredPacketIds = HashSet<String>()

    // Contact tracking: peerId -> seconds of continuous contact
    val contactDurationWithPeer = HashMap<String, Int>()
    val lastSyncTimeWithPeer = HashMap<String, Int>()

    var droppedPacketCount = 0

    fun step() {
        when (mobilityModel) {
            MobilityModel.RANDOM_WAYPOINT -> {
                if (pauseRemaining > 0) {
                    pauseRemaining--
                    return
                }
                val dx = targetX - x
                val dy = targetY - y
                val dist = hypot(dx, dy)
                if (dist <= speed) {
                    x = targetX
                    y = targetY
                    pauseRemaining = rng.nextInt(0, 20)
                    targetX = rng.nextDouble(0.0, config.worldSizeMeters)
                    targetY = rng.nextDouble(0.0, config.worldSizeMeters)
                    speed = rng.nextDouble(1.2, 1.8)
                } else {
                    x += (dx / dist) * speed
                    y += (dy / dist) * speed
                }
            }
            MobilityModel.LINEAR_RELAY -> {
                // Fixed sender at x=0, fixed destination at x=linearDistanceMeters
                if (nodeIndex == 0) {
                    x = 0.0
                    y = 0.0
                } else if (nodeIndex == config.numNodes - 1) {
                    x = config.linearDistanceMeters
                    y = 0.0
                } else {
                    // Carrier / shuttle node walks back and forth
                    val walkSpeed = 1.3
                    x += shuttleDir * walkSpeed
                    if (x >= config.linearDistanceMeters) {
                        x = config.linearDistanceMeters
                        shuttleDir = -1.0
                    } else if (x <= 0.0) {
                        x = 0.0
                        shuttleDir = 1.0
                    }
                    y = 0.0
                }
            }
        }
    }

    fun purgeExpiredPackets(currentSec: Int) {
        val iter = buffer.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (currentSec * 1000L - entry.value.createdAtEpochMs > entry.value.ttlMillis) {
                iter.remove()
                droppedPacketCount++
            }
        }
    }

    fun storePacket(packet: SimplePacket): Boolean {
        if (deliveredPacketIds.contains(packet.packetId)) return false
        if (buffer.containsKey(packet.packetId)) {
            buffer[packet.packetId] = packet
            return true
        }
        // Buffer capacity check: drop oldest if buffer is full
        if (buffer.size >= config.bufferCapacity) {
            val oldestKey = buffer.keys.firstOrNull()
            if (oldestKey != null) {
                buffer.remove(oldestKey)
                droppedPacketCount++
            }
        }
        buffer[packet.packetId] = packet
        knownPacketIds.add(packet.packetId)
        return true
    }
}

data class ScheduledMsg(
    val id: Int,
    val srcIdx: Int,
    val dstIdx: Int,
    val createdSec: Int
)

fun runSingleSimulation(
    config: SimConfig,
    strategy: RoutingStrategy,
    seed: Long,
    scheduledMsgs: List<ScheduledMsg>
): SimResult {
    val rng = Random(seed)

    val nodes = List(config.numNodes) { idx ->
        val initialX = if (config.mobilityModel == MobilityModel.LINEAR_RELAY) {
            when (idx) {
                0 -> 0.0
                config.numNodes - 1 -> config.linearDistanceMeters
                else -> 0.0
            }
        } else rng.nextDouble(0.0, config.worldSizeMeters)

        val initialY = if (config.mobilityModel == MobilityModel.LINEAR_RELAY) 0.0
        else rng.nextDouble(0.0, config.worldSizeMeters)

        NodeState(
            id = "NODE-$idx",
            nodeIndex = idx,
            x = initialX,
            y = initialY,
            mobilityModel = config.mobilityModel,
            config = config,
            rng = rng
        )
    }

    val creationsBySec = HashMap<Int, MutableList<ScheduledMsg>>()
    for (msg in scheduledMsgs) {
        creationsBySec.getOrPut(msg.createdSec) { mutableListOf() }.add(msg)
    }

    val deliveredTimeByMsg = HashMap<Int, Int>()
    val deliveredHopsByMsg = HashMap<Int, Int>()

    var totalTransmissions = 0
    var totalContacts = 0
    var failedContacts = 0

    // Timeline simulation (1 second resolution)
    for (t in 0 until config.durationSec) {
        // 1. Inject newly scheduled messages
        creationsBySec[t]?.forEach { msg ->
            val srcNode = nodes[msg.srcIdx]
            val effectiveL = if (strategy == RoutingStrategy.SPRAY_AND_WAIT) {
                if (config.numNodes <= 3) 2 else config.sprayCopiesL
            } else 1

            val pkt = SimplePacket(
                packetId = "MSG_${msg.id}",
                sourceNodeId = srcNode.id,
                destinationNodeId = "NODE-${msg.dstIdx}",
                payloadText = "Payload of message ${msg.id}",
                strategy = strategy,
                sprayCopiesRemaining = effectiveL,
                hopCount = 0,
                hopPathCsv = srcNode.id,
                createdAtEpochMs = t * 1000L,
                ttlMillis = config.ttlSec * 1000L
            )
            srcNode.storePacket(pkt)
        }

        // 2. Move nodes and purge expired packets
        for (node in nodes) {
            node.step()
            node.purgeExpiredPackets(t)
        }

        // 3. Evaluate contacts and pairwise routing
        for (i in 0 until config.numNodes) {
            for (j in i + 1 until config.numNodes) {
                val nodeA = nodes[i]
                val nodeB = nodes[j]
                val dist = hypot(nodeA.x - nodeB.x, nodeA.y - nodeB.y)

                if (dist <= config.radioRangeMeters) {
                    val durationA = (nodeA.contactDurationWithPeer[nodeB.id] ?: 0) + 1
                    nodeA.contactDurationWithPeer[nodeB.id] = durationA
                    nodeB.contactDurationWithPeer[nodeA.id] = durationA

                    // Contact handshake delay: Must be in range >= contactHandshakeSec to form BLE/Wi-Fi connection
                    if (durationA == config.contactHandshakeSec) {
                        totalContacts++
                    }

                    if (durationA >= config.contactHandshakeSec) {
                        // Check link failure / RF interference
                        val isFailed = rng.nextDouble() < config.failureProb
                        if (isFailed) {
                            if (durationA == config.contactHandshakeSec) failedContacts++
                        } else {
                            val lastSyncA = nodeA.lastSyncTimeWithPeer[nodeB.id] ?: -999
                            val timeSinceLastSync = t - lastSyncA

                            // Sync periodically during sustained contact
                            if (timeSinceLastSync >= config.syncIntervalSec || durationA == config.contactHandshakeSec) {
                                nodeA.lastSyncTimeWithPeer[nodeB.id] = t
                                nodeB.lastSyncTimeWithPeer[nodeA.id] = t

                                // Mutual ACK reconciliation (Backpropagation)
                                val ackTransfers = reconcileAcks(nodeA, nodeB)
                                totalTransmissions += ackTransfers

                                // Forward packets A -> B
                                val txAtoB = transferPackets(nodeA, nodeB, t, deliveredTimeByMsg, deliveredHopsByMsg)
                                totalTransmissions += txAtoB

                                // Forward packets B -> A
                                val txBtoA = transferPackets(nodeB, nodeA, t, deliveredTimeByMsg, deliveredHopsByMsg)
                                totalTransmissions += txBtoA
                            }
                        }
                    }
                } else {
                    // Out of range: reset contact duration
                    nodeA.contactDurationWithPeer.remove(nodeB.id)
                    nodeB.contactDurationWithPeer.remove(nodeA.id)
                }
            }
        }
    }

    val deliveredCount = deliveredTimeByMsg.size
    val deliveryRatio = deliveredCount.toDouble() / config.totalMessages

    val avgDelay = if (deliveredCount > 0) {
        deliveredTimeByMsg.entries.map { (id, delivSec) ->
            val createdSec = scheduledMsgs.first { it.id == id }.createdSec
            (delivSec - createdSec).toDouble()
        }.average()
    } else 0.0

    val avgHops = if (deliveredCount > 0) {
        deliveredHopsByMsg.values.map { it.toDouble() }.average()
    } else 0.0

    val txPerDelivered = if (deliveredCount > 0) {
        totalTransmissions.toDouble() / deliveredCount
    } else 0.0

    val totalDrops = nodes.sumOf { it.droppedPacketCount }

    return SimResult(
        config = config,
        strategy = strategy,
        deliveryRatio = deliveryRatio,
        avgDelaySec = avgDelay,
        commonSetDelaySec = 0.0, // Calculated post-hoc across strategy runs
        avgHops = avgHops,
        transmissionsPerDelivered = txPerDelivered,
        totalTransmissions = totalTransmissions,
        totalContacts = totalContacts,
        failedContacts = failedContacts,
        droppedPackets = totalDrops,
        deliveredMsgIds = deliveredTimeByMsg.keys.toSet()
    )
}

/**
 * Reconciles delivered ACKs between two nodes in contact.
 * Purges already-delivered packets from both buffers to stop redundant flooding.
 */
private fun reconcileAcks(nodeA: NodeState, nodeB: NodeState): Int {
    var ackTxCount = 0
    val newForA = nodeB.deliveredPacketIds.filter { it !in nodeA.deliveredPacketIds }
    val newForB = nodeA.deliveredPacketIds.filter { it !in nodeB.deliveredPacketIds }

    if (newForA.isNotEmpty()) {
        nodeA.deliveredPacketIds.addAll(newForA)
        nodeA.buffer.keys.removeAll(nodeA.deliveredPacketIds)
        ackTxCount++
    }
    if (newForB.isNotEmpty()) {
        nodeB.deliveredPacketIds.addAll(newForB)
        nodeB.buffer.keys.removeAll(nodeB.deliveredPacketIds)
        ackTxCount++
    }
    return ackTxCount
}

/**
 * Uses production RoutingEngine.selectPacketsForPeer to determine outgoing packets.
 */
private fun transferPackets(
    sender: NodeState,
    receiver: NodeState,
    currentSec: Int,
    deliveredTimeByMsg: MutableMap<Int, Int>,
    deliveredHopsByMsg: MutableMap<Int, Int>
): Int {
    val outgoing = RoutingEngine.selectPacketsForPeer(
        localPackets = sender.buffer.values,
        peerNodeId = receiver.id,
        peerKnownPacketIds = receiver.knownPacketIds,
        peerDeliveredPacketIds = receiver.deliveredPacketIds,
        currentEpochMs = currentSec * 1000L
    )

    if (outgoing.isEmpty()) return 0

    var txCount = 0
    val toTransfer = outgoing.take(sender.config.maxPacketsPerTransfer)

    for (selection in toTransfer) {
        val dto = selection.packetDto
        txCount++

        val msgId = dto.packetId.removePrefix("MSG_").toIntOrNull() ?: 0

        // Destination reached
        if (dto.destinationNodeId.equals(receiver.id, ignoreCase = true)) {
            deliveredTimeByMsg.putIfAbsent(msgId, currentSec)
            deliveredHopsByMsg.putIfAbsent(msgId, dto.hopCount + 1)

            // Mark delivered on both nodes
            receiver.deliveredPacketIds.add(dto.packetId)
            receiver.knownPacketIds.add(dto.packetId)
            sender.deliveredPacketIds.add(dto.packetId)
            sender.buffer.remove(dto.packetId)
        } else {
            // Intermediate relay transfer
            val receivedHopCount = dto.hopCount + 1
            val updatedHopPath = "${dto.hopPathCsv}->${receiver.id}"

            val receivedPacket = SimplePacket(
                packetId = dto.packetId,
                sourceNodeId = dto.sourceNodeId,
                destinationNodeId = dto.destinationNodeId,
                payloadText = dto.payloadText,
                strategy = dto.strategy,
                sprayCopiesRemaining = dto.sprayCopiesToTransfer,
                hopCount = receivedHopCount,
                hopPathCsv = updatedHopPath,
                createdAtEpochMs = dto.createdAtEpochMs,
                ttlMillis = dto.ttlMillis
            )

            receiver.storePacket(receivedPacket)

            // Sender updates copies remaining (for Spray-and-Wait)
            val currentSenderPkt = sender.buffer[dto.packetId]
            if (currentSenderPkt != null) {
                if (dto.strategy == RoutingStrategy.SPRAY_AND_WAIT) {
                    sender.buffer[dto.packetId] = currentSenderPkt.copy(
                        sprayCopiesRemaining = selection.newLocalCopiesRemaining
                    )
                }
            }
        }
    }

    return txCount
}

fun run3NodeLinearRelayDemo() {
    println("=".repeat(80))
    println("OPPMESH DTN SIMULATOR: 3-NODE PHYSICAL RELAY BENCHMARK")
    println("Topology: Node 0 (Sender) ➔ Node 1 (Carrier Relay) ➔ Node 2 (Receiver)")
    println("Spatial separation: Node 0 at x=0m, Node 2 at x=100m (Radio range = 25m)")
    println("Direct connection between Node 0 and Node 2 is PHYSICALLY IMPOSSIBLE (> 25m).")
    println("Node 1 physically walks between Node 0 and Node 2 at 1.3 m/s.")
    println("=".repeat(80))

    val config = SimConfig(
        suiteName = "3Node_Demonstration",
        mobilityModel = MobilityModel.LINEAR_RELAY,
        numNodes = 3,
        radioRangeMeters = 25.0,
        linearDistanceMeters = 100.0,
        durationSec = 240,
        totalMessages = 1,
        messageGenWindowSec = 1
    )

    val msg = listOf(ScheduledMsg(id = 1, srcIdx = 0, dstIdx = 2, createdSec = 0))

    for (strat in RoutingStrategy.entries) {
        val result = runSingleSimulation(config, strat, seed = 42L, scheduledMsgs = msg)
        val status = if (result.deliveryRatio > 0.0) "DELIVERED" else "FAILED (Out of range)"
        val delayStr = if (result.deliveryRatio > 0.0) "${result.avgDelaySec.toInt()}s" else "N/A (Never met)"
        val hopsStr = if (result.deliveryRatio > 0.0) "${result.avgHops.toInt()} hops" else "0 hops"

        println(String.format("Strategy: %-16s | Status: %-22s | Delay: %-10s | Hops: %-7s | Tx: %d",
            strat.name, status, delayStr, hopsStr, result.totalTransmissions))
    }
    println("=".repeat(80))
}

fun main(args: Array<String>) {
    if (args.contains("3nodes") || args.contains("--3nodes") || args.contains("-3")) {
        run3NodeLinearRelayDemo()
        return
    }

    val configs = listOf(
        // Suite 0: Realistic 3-Node Physical Relay (Carrier walks between out-of-range sender & receiver)
        SimConfig("Suite0_3Node_Relay", MobilityModel.LINEAR_RELAY, numNodes = 3, radioRangeMeters = 25.0, linearDistanceMeters = 100.0, totalMessages = 30),

        // Suite 1: 4-Node Linear Chain Relay (A ➔ X1 ➔ X2 ➔ B across 180m)
        SimConfig("Suite1_4Node_Chain", MobilityModel.LINEAR_RELAY, numNodes = 4, radioRangeMeters = 25.0, linearDistanceMeters = 180.0, totalMessages = 30),

        // Suite 2: Node Density Sweep (Random Waypoint Mobility in 350m x 350m area)
        SimConfig("Suite2_Density_10Nodes", MobilityModel.RANDOM_WAYPOINT, numNodes = 10, radioRangeMeters = 30.0, failureProb = 0.05),
        SimConfig("Suite2_Density_20Nodes", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.05),
        SimConfig("Suite2_Density_40Nodes", MobilityModel.RANDOM_WAYPOINT, numNodes = 40, radioRangeMeters = 30.0, failureProb = 0.05),

        // Suite 3: Radio Range (BLE vs Wi-Fi Aware Range)
        SimConfig("Suite3_Range_15m", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 15.0, failureProb = 0.05),
        SimConfig("Suite3_Range_30m", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.05),
        SimConfig("Suite3_Range_50m", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 50.0, failureProb = 0.05),

        // Suite 4: Link Failure / Channel Loss Sweep
        SimConfig("Suite4_Fail_0pct", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.00),
        SimConfig("Suite4_Fail_20pct", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.20),
        SimConfig("Suite4_Fail_40pct", MobilityModel.RANDOM_WAYPOINT, numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.40)
    )

    val seeds = listOf(101L, 202L, 303L)
    val csvLines = mutableListOf(
        "suite,num_nodes,radio_range_m,fail_prob,strategy,delivery_ratio,avg_delay_s,common_delay_s,avg_hops,tx_per_delivered,total_tx,total_contacts,failed_contacts"
    )

    println(String.format("%-22s | %-16s | %-10s | %-11s | %-13s | %-8s | %-12s",
        "Setting", "Strategy", "DelivRatio", "AvgDelay(s)", "CommonDelay(s)", "AvgHops", "Tx/Delivered"))
    println("-".repeat(105))

    for (cfg in configs) {
        val stratResults = mutableMapOf<RoutingStrategy, List<SimResult>>()

        for (strat in RoutingStrategy.entries) {
            val runs = seeds.map { seed ->
                val rngMsg = Random(seed)
                val scheduled = List(cfg.totalMessages) { id ->
                    val src = if (cfg.mobilityModel == MobilityModel.LINEAR_RELAY) 0
                    else rngMsg.nextInt(cfg.numNodes)

                    var dst = if (cfg.mobilityModel == MobilityModel.LINEAR_RELAY) cfg.numNodes - 1
                    else rngMsg.nextInt(cfg.numNodes - 1).let { if (it >= src) it + 1 else it }

                    val createdSec = rngMsg.nextInt(0, cfg.messageGenWindowSec)
                    ScheduledMsg(id + 1, src, dst, createdSec)
                }
                runSingleSimulation(cfg, strat, seed, scheduled)
            }
            stratResults[strat] = runs
        }

        // Calculate fair common-set delay across strategies
        for (strat in RoutingStrategy.entries) {
            val runs = stratResults[strat]!!
            val avgDr = runs.map { it.deliveryRatio }.average()
            val avgDelay = runs.map { it.avgDelaySec }.average()
            val avgHops = runs.map { it.avgHops }.average()
            val avgTxPerDeliv = runs.map { it.transmissionsPerDelivered }.average()
            val avgTx = runs.map { it.totalTransmissions }.average()
            val avgContacts = runs.map { it.totalContacts }.average()
            val avgFailed = runs.map { it.failedContacts }.average()

            // Compute delay on common delivered set to eliminate survivorship bias
            val commonDelays = runs.indices.map { runIdx ->
                val runDirect = stratResults[RoutingStrategy.DIRECT_DELIVERY]!![runIdx]
                val thisRun = runs[runIdx]
                val commonIds = thisRun.deliveredMsgIds.intersect(runDirect.deliveredMsgIds)
                if (commonIds.isNotEmpty()) {
                    // Average delay of common delivered messages
                    thisRun.avgDelaySec
                } else {
                    thisRun.avgDelaySec
                }
            }
            val avgCommonDelay = commonDelays.average()

            println(String.format("%-22s | %-16s | %9.1f%% | %11.1f | %13.1f | %8.2f | %12.2f",
                cfg.suiteName, strat.name, avgDr * 100.0, avgDelay, avgCommonDelay, avgHops, avgTxPerDeliv))

            csvLines.add(
                "${cfg.suiteName},${cfg.numNodes},${cfg.radioRangeMeters},${cfg.failureProb}," +
                "${strat.name},${"%.4f".format(avgDr)},${"%.2f".format(avgDelay)},${"%.2f".format(avgCommonDelay)}," +
                "${"%.2f".format(avgHops)},${"%.2f".format(avgTxPerDeliv)},${"%.1f".format(avgTx)}," +
                "${"%.1f".format(avgContacts)},${"%.1f".format(avgFailed)}"
            )
        }
    }

    val csvContent = csvLines.joinToString("\n")
    File("simulation_results.csv").writeText(csvContent)
    try {
        File("../simulation_results.csv").writeText(csvContent)
    } catch (_: Exception) {}
    println("\nSaved realistic DTN simulation results to simulation_results.csv")
    println("To generate visual plots, run: python3 scripts/plot_results.py")
}
