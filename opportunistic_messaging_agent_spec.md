# AGENTS.md — Opportunistic Messaging (DTN) Android App & Simulator Specification

## 1. Project Overview & Research Goal

You are an autonomous Android & Systems Engineering Agent building **OppMesh**, a complete Delay-Tolerant Networking (DTN) store-carry-and-forward messaging prototype and discrete-event simulator in **Kotlin (Android Studio)**.

### Research Question
How do **Direct Delivery**, **Epidemic Forwarding**, and **Binary Spray-and-Wait** compare in **delivery ratio**, **average delivery delay**, and **number of transmissions per delivered message** when mobile nodes meet at random without network infrastructure?

### Core Deliverables to Implement
1. **Android Application (`:app` module, Kotlin + Jetpack Compose + Room + Google Nearby Connections API)**:
   - Stores messages locally when no peers are in range, carries them while the user moves, and automatically discovers, connects, and exchanges eligible messages upon physical proximity with another phone.
   - Implements selectable forwarding rules: **Direct Delivery**, **Epidemic Forwarding**, and **Binary Spray-and-Wait** ($L$ copies).
   - Logs all discovery events, handshake latencies, connection/transmission failures, contact durations, and delivery timestamps to a local SQLite database via [Android Room](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Ftraining%2Fdata-storage%2Froom), with one-tap CSV export for experiment reporting.
   - Runs discovery and advertising inside an [Android Foreground Service](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fdevelop%2Fbackground-work%2Fservices%2Ffgs) to prevent OS background restrictions from halting peer discovery.
2. **Multi-Node Mobility & Routing Simulator (`:simulator` Kotlin/JVM module + Python analysis script)**:
   - Simulates $\ge 10$ mobile nodes moving at random (Random Waypoint / Random Walk mobility model) and exchanging messages upon entering radio contact range.
   - Evaluates Direct Delivery, Epidemic Forwarding, and Spray-and-Wait across at least three experimental configurations (varying node count $N$, contact rate/radio range $R$, and connection failure probability $p_{\text{fail}}$).
   - Computes and exports **Delivery Ratio**, **Average Delivery Delay**, and **Transmissions per Delivered Message**.

## 2. Repository & Module Structure

Configure a multi-module Gradle project in Android Studio using Kotlin DSL (`build.gradle.kts`):

| Module / Path | Purpose | Key Technologies |
| :--- | :--- | :--- |
| `:core-dtn` | Pure Kotlin shared routing engine, packet models, summary vector diffing, and metric calculators shared by both the Android app and JVM simulator | Kotlin Coroutines, `kotlinx.serialization` |
| `:app` | Android client app for real 2-phone and 3-phone store-carry-forward experiments | [Jetpack Compose](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fcompose), [Room](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Ftraining%2Fdata-storage%2Froom), [Nearby Connections API](https://www.google.com/search?q=https%3A%2F%2Fdevelopers.google.com%2Fnearby%2Fconnections%2Foverview), Foreground Service |
| `:simulator` | Standalone Kotlin/JVM discrete-time simulator executing Monte Carlo runs across node densities and contact rates | Kotlin JVM (`application` plugin), CSV report generator |
| `scripts/plot_results.py` | Optional Python script to plot CSV output from `:simulator` and phone logs into publication-ready charts | Python 3, `pandas`, `matplotlib` |

### Package Organization Inside `:app`
- `com.oppmesh.dtn.data.local` — Room database (`OppMeshDatabase`), DAOs (`PacketDao`, `ContactLogDao`), and entities (`PacketEntity`, `ContactLogEntity`).
- `com.oppmesh.dtn.data.nearby` — `NearbyMeshManager` wrapping Google Play Services `ConnectionsClient`, deterministic connection tie-breaking, and payload serialization.
- `com.oppmesh.dtn.service` — `MeshSyncForegroundService` managing persistent notification, wake locks, and continuous advertising/discovery loops.
- `com.oppmesh.dtn.domain.routing` — Strategy implementations (`DirectDeliveryRouter`, `EpidemicRouter`, `SprayAndWaitRouter`).
- `com.oppmesh.dtn.ui` — Jetpack Compose screens (`DashboardScreen`, `MessageBufferScreen`, `TelemetryLogScreen`, `ExperimentViewModel`).

## 3. Gradle Dependencies & Android Manifest Configuration

### Version Alignment (`gradle/libs.versions.toml` or `app/build.gradle.kts`)
- **Min SDK**: `26` (Android 8.0) | **Target / Compile SDK**: `34` (Android 14)
- **Google Play Services Nearby**: `com.google.android.gms:play-services-nearby:19.3.0` (refer to [Nearby Connections Get Started](https://www.google.com/search?q=https%3A%2F%2Fdevelopers.google.com%2Fnearby%2Fconnections%2Fandroid%2Fget-started))
- **Room Persistence**: `androidx.room:room-runtime:2.6.1`, `androidx.room:room-ktx:2.6.1`, `ksp("androidx.room:room-compiler:2.6.1")`
- **Serialization**: `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3`
- **Jetpack Compose**: Material 3 (`androidx.compose.material3:material3`), `lifecycle-viewmodel-compose:2.8.4`

### Required Permissions (`app/src/main/AndroidManifest.xml`)
Nearby Connections uses Bluetooth, BLE, and Wi-Fi Direct under the hood. Declare and request runtime permissions across Android 8–14+:

```xml
<!-- Legacy Bluetooth & Location (Android 8 - 11) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="31" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<!-- Android 12+ (API 31+) Bluetooth permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Android 13+ (API 33+) Wi-Fi Nearby & Notifications -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Foreground Service Permissions (Android 14 / API 34 requires explicit service types) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Register `MeshSyncForegroundService` in `AndroidManifest.xml` with:
```xml
<service
    android:name=".service.MeshSyncForegroundService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|location" />
```

## 4. Data Models & Room Database Schema

### 4.1 `RoutingStrategy` Enum
```kotlin
package com.oppmesh.dtn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingStrategy {
    DIRECT_DELIVERY,
    EPIDEMIC,
    SPRAY_AND_WAIT
}
```

### 4.2 `PacketEntity` (Stored & Carried Messages)
Stores every message originated by or relayed through the device.
```kotlin
package com.oppmesh.dtn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.oppmesh.dtn.domain.model.RoutingStrategy

@Entity(tableName = "packets")
data class PacketEntity(
    @PrimaryKey val packetId: String,          // UUID v4
    val sourceNodeId: String,                  // e.g., "PHONE-A"
    val destinationNodeId: String,             // e.g., "PHONE-B" or "PHONE-C"
    val payloadText: String,                   // User text message
    val strategy: RoutingStrategy,             // Active routing strategy for this packet
    val sprayCopiesRemaining: Int,             // L copies (1 for Direct/Epidemic; e.g. 4 for Spray-and-Wait)
    val hopCount: Int,                         // Incremented on each relay transfer
    val hopPathCsv: String,                    // e.g., "PHONE-A,PHONE-C,PHONE-B"
    val createdAtEpochMs: Long,                // Origin timestamp on source node
    val storedAtLocalEpochMs: Long,            // Timestamp when stored/received on this phone
    val deliveredAtEpochMs: Long? = null,      // Non-null once delivered to destination
    val ttlMillis: Long = 3_600_000L,          // Default 1 hour TTL
    val transmissionCount: Int = 0,            // Number of times this node transmitted this packet
    val status: String                         // "STORED", "CARRIED", "DELIVERED", "EXPIRED"
)
```

### 4.3 `ContactLogEntity` (Contact, Delivery & Failure Telemetry)
Captures every peer encounter, handshake latency, transfer count, and failure event required by the experiment rubric.
```kotlin
package com.oppmesh.dtn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_logs")
data class ContactLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,                     // Unique encounter ID
    val localNodeId: String,
    val peerEndpointId: String,
    val peerNodeId: String?,                   // Resolved from endpointName or HELLO frame
    val eventType: String,                     // DISCOVERED, CONN_REQUESTED, CONNECTED, SYNC_COMPLETE, DISCONNECTED, FAILURE
    val statusCode: Int,                       // Nearby Connections StatusCodes (0 = OK, 8007 = RADIO_ERROR, etc.)
    val statusName: String,                    // Human-readable code name
    val timestampEpochMs: Long,
    val handshakeDurationMs: Long? = null,     // Time from discovery to active connection
    val contactDurationMs: Long? = null,       // Time from connection to disconnection
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val notes: String = ""
)
```

## 5. Wire Protocol & Forwarding Engine Specification

### 5.1 Wire Frame Format (`WireFrame`)
Exchange JSON frames serialized as UTF-8 `ByteArray` payloads (`Payload.fromBytes()`) over Nearby Connections:

```kotlin
package com.oppmesh.dtn.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WireFrame(
    val frameType: FrameType,                  // HELLO_SUMMARY, PACKET_BUNDLE, DELIVERY_ACK
    val senderNodeId: String,
    val senderWallClockMs: Long,
    val knownPacketIds: List<String> = emptyList(),      // Packets currently held by sender
    val deliveredPacketIds: List<String> = emptyList(),  // Packets known to have reached final destination
    val packets: List<NetworkPacketDto> = emptyList()
)

@Serializable
enum class FrameType {
    HELLO_SUMMARY,
    PACKET_BUNDLE,
    DELIVERY_ACK
}

@Serializable
data class NetworkPacketDto(
    val packetId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val payloadText: String,
    val strategy: RoutingStrategy,
    val sprayCopiesToTransfer: Int,
    val hopCount: Int,
    val hopPathCsv: String,
    val createdAtEpochMs: Long,
    val ttlMillis: Long
)
```

### 5.2 Three-Step Contact Exchange Handshake
Whenever Phone A and Phone B establish a connection:
1. **Step 1 — `HELLO_SUMMARY` Exchange**:
   - Both phones immediately send a `HELLO_SUMMARY` frame containing their `senderNodeId`, `senderWallClockMs` (to estimate clock skew $\Delta t$ between phones), `knownPacketIds` (all unexpired packet IDs in their local buffer), and `deliveredPacketIds` (all packet IDs already confirmed delivered).
2. **Step 2 — Routing Decision & `PACKET_BUNDLE` Transmission**:
   - Upon receiving a peer's `HELLO_SUMMARY`, the local node first marks any locally stored packets appearing in `peer.deliveredPacketIds` as `DELIVERED` (anti-entropy ACK healing).
   - Next, the local node invokes `RoutingEngine.selectPacketsForPeer(localPackets, peerNodeId, peerKnownPacketIds)` according to each packet's `RoutingStrategy`:
     - **Direct Delivery (`DIRECT_DELIVERY`)**:
       - Select packet $P$ **if and only if** `P.destinationNodeId == peerNodeId` and `P.packetId !in peerKnownPacketIds`.
       - Local node keeps its record marked `DELIVERED` once ACKed; zero intermediate relays occur.
     - **Epidemic Forwarding (`EPIDEMIC`)**:
       - Select packet $P$ if `P.packetId !in peerKnownPacketIds` and `P.packetId !in peerDeliveredPacketIds` and TTL has not expired.
       - Both sender and receiver keep a full copy (`sprayCopiesToTransfer = 1`), and receiver appends its `nodeId` to `hopPathCsv` and increments `hopCount + 1`.
     - **Binary Spray-and-Wait (`SPRAY_AND_WAIT`)**:
       - If `P.destinationNodeId == peerNodeId` and `P.packetId !in peerKnownPacketIds`: transmit directly (`sprayCopiesToTransfer = 1`).
       - Else if `P.sprayCopiesRemaining > 1` and `P.packetId !in peerKnownPacketIds`:
         - **Spray Phase**: Transfer $\lfloor L / 2 \rfloor$ copies (`sprayCopiesToTransfer = P.sprayCopiesRemaining / 2`) to the peer, and update local `sprayCopiesRemaining = P.sprayCopiesRemaining - (P.sprayCopiesRemaining / 2)` ($\lceil L / 2 \rceil$).
       - Else (`P.sprayCopiesRemaining == 1`):
         - **Wait Phase**: Do not relay to intermediate peers; wait for direct encounter with `P.destinationNodeId`.
3. **Step 3 — `DELIVERY_ACK` Confirmation**:
   - If the receiving phone is the `destinationNodeId` for any incoming packet in `PACKET_BUNDLE`, it records `deliveredAtEpochMs = System.currentTimeMillis()`, calculates delivery delay adjusted for peer clock offset, updates status to `DELIVERED`, and replies with a `DELIVERY_ACK` frame containing `deliveredPacketIds`.

## 6. Mitigating Real-World Android Pitfalls ("Watch Out For" Solutions)

The project specification highlights two critical real-world failure modes on Android: **slow/failing discovery and connection setup** and **background restrictions stopping discovery**. Implement the following safeguards:

| Failure Mode | Root Cause on Android | Mandatory Engineering Mitigation in `:app` |
| :--- | :--- | :--- |
| **Simultaneous Connection Collision (`STATUS_ALREADY_CONNECTED_TO_ENDPOINT` / `8007` Bluetooth error)** | When both Phone A and Phone B run `startAdvertising()` and `startDiscovery()` simultaneously, both discover each other at the same instant and both call `requestConnection()`, colliding on the BLE/Wi-Fi Direct radio. | **Deterministic Lexicographical Tie-Breaking**: Encode `nodeId` as the advertising `endpointName` (e.g., `"NODE-A"`, `"NODE-B"`). Inside `onEndpointFound()`, compare `localNodeId < discoveredEndpointName`. **Only the node with the lexicographically smaller ID calls `requestConnection()`**; the larger ID waits passively to accept in `onConnectionInitiated()`. Add a 4-second fallback timer in case the smaller node missed the advertisement. |
| **Background Discovery Halted by OS** | Android 12–14 freezes background scans and kills idle services within minutes when the screen locks. | **Foreground Service + Partial WakeLock**: Run `NearbyMeshManager` inside `MeshSyncForegroundService` with `foregroundServiceType="connectedDevice\|location"`, a persistent status bar notification showing live peer/buffer counts, and an `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt on onboarding. |
| **Radio Stuck State / Silent Discovery Drop** | Nearby Connections `P2P_CLUSTER` can silently stall after repeated Wi-Fi Direct channel switches. | **Watchdog Duty-Cycle Reset**: Every 90 seconds of zero peer activity, automatically cycle `stopDiscovery()` / `stopAdvertising()` and restart after a 500 ms delay, logging any non-zero status code to `contact_logs`. |
| **Clock Skew Between Two Test Phones** | If Phone A and Phone B have wall clocks off by 2–5 seconds, computed delivery delay (`deliveredAt - createdAt`) can be inaccurate or even negative. | **Handshake Clock Offset Calibration**: In `HELLO_SUMMARY`, compute $\Delta t = t_{\text{local}} - t_{\text{peer}}$ and log both raw wall-clock delay and offset-corrected delay in the exported CSV. |

## 7. Android UI & Experiment Workflow Design

Build a clean 3-tab Jetpack Compose interface in `MainActivity`:

1. **Tab 1: Node Control & Two-Phone Test Runner (`DashboardScreen`)**
   - **Identity & Radio Card**: Editable or preset Node ID (`NODE-A`, `NODE-B`, `NODE-C`), Foreground Service master toggle (`Start Mesh Radio` / `Stop Mesh Radio`), live radio state indicator (`IDLE`, `SCANNING`, `CONNECTING`, `SYNCING`), and active default routing strategy selector (`DIRECT_DELIVERY`, `EPIDEMIC`, `SPRAY_AND_WAIT` with $L=4$).
   - **Quick Compose Card**: Dropdown/chips to pick target (`NODE-B`, `NODE-C`), message input field, and **"Store in Buffer"** button. Immediately inserts the packet into Room with `status = "STORED"` and `createdAtEpochMs`, even when completely offline with 0 peers nearby.
   - **Live Metrics Summary Row**: Displays Total Stored, Carried (Relayed), Delivered, Avg Delay (ms), and Connection Failures count.

2. **Tab 2: Message Buffer & Store-Carry-Forward Inspector (`MessageBufferScreen`)**
   - Lists all packets in Room ordered by timestamp.
   - Each card shows `packetId` (short prefix), `Source -> Destination`, Strategy badge, `Copies (L)`, `Hop Path` (e.g., `NODE-A -> NODE-C -> NODE-B`), `Created Time`, `Received/Carried Time`, `Delivered Time`, and computed **End-to-End Delay (s)**.

3. **Tab 3: Contact & Failure Telemetry Log (`TelemetryLogScreen`)**
   - Real-time scrolling log of `ContactLogEntity` events with color-coded badges (`DISCOVERED`, `CONNECTED`, `SYNC_COMPLETE`, `FAILURE`).
   - Displays exact handshake latency (ms) and failure status codes.
   - **"Export Experiment CSV" Button**: Writes both `packets_export.csv` and `contact_logs_export.csv` to the app's external files directory and launches `Intent.ACTION_SEND` via Android `FileProvider` so the user can share the logs directly to their laptop for the report.

## 8. Standalone Multi-Node Simulator Specification (`:simulator`)

Create a self-contained Kotlin/JVM module `:simulator` (or run `SimulatorMain.kt` via `./gradlew :simulator:run`) that uses the exact same routing rules as the Android app to answer the research question across 10+ random mobile nodes.

### 8.1 Simulation Model & Parameters
- **World Area**: $500\text{ m} \times 500\text{ m}$ 2D torus or bounded square.
- **Time Step**: $\Delta t = 1.0\text{ s}$, total simulation duration $T = 3600\text{ s}$ (1 hour), with a warmup/message-generation window during the first $1800\text{ s}$ (100 random source-to-destination messages generated via Poisson arrival) and a $1800\text{ s}$ drain window so late messages have time to deliver.
- **Mobility Model (Random Waypoint)**:
  - Each node $i \in \{1 \dots N\}$ picks a random destination $(x_d, y_d) \in [0, 500]^2$ and speed $v \sim \mathcal{U}(1.2, 2.5)\text{ m/s}$ (human walking speed), moves toward $(x_d, y_d)$, pauses for $t_{\text{pause}} \sim \mathcal{U}(0, 30)\text{ s}$, and picks a new waypoint.
- **Contact & Failure Model**:
  - Two nodes $i, j$ are in radio range at step $t$ if Euclidean distance $d(i, j) \le R$.
  - On a new contact edge (nodes were out of range at $t-1$ and are in range at $t$), a connection setup succeeds with probability $1 - p_{\text{fail}}$ after a handshake delay of $t_{\text{setup}} = 2\text{ s}$. If setup fails, log a connection failure event.

### 8.2 Mandatory Experimental Matrix (Minimum 3 Settings + Extension)
Configure `SimulatorMain.kt` to run 5 random seeds per configuration and output aggregated mean metrics for all three strategies (`DIRECT_DELIVERY`, `EPIDEMIC`, `SPRAY_AND_WAIT` with $L=6$):

| Experiment Suite | Controlled Variable | Values Tested | Fixed Parameters | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **Setting 1: Node Density Sweep** | Number of nodes ($N$) | $N \in \{10, 20, 40\}$ | $R = 30\text{ m}$, $p_{\text{fail}} = 0.10$, $\text{TTL} = 1800\text{ s}$ | Measures how intermediate relay density accelerates Epidemic and Spray-and-Wait vs. Direct Delivery |
| **Setting 2: Contact Rate Sweep** | Radio range ($R$) | $R \in \{15\text{ m}, 30\text{ m}, 50\text{ m}\}$ | $N = 20$, $p_{\text{fail}} = 0.10$, $\text{TTL} = 1800\text{ s}$ | Varies pairwise contact frequency from sparse to dense |
| **Setting 3: Handshake Failure Sweep** | Connection setup failure rate ($p_{\text{fail}}$) | $p_{\text{fail}} \in \{0.0, 0.25, 0.50\}$ | $N = 20$, $R = 30\text{ m}$, $\text{TTL} = 1800\text{ s}$ | Quantifies sensitivity of each strategy to real-world BLE/Wi-Fi Direct discovery drops |

### 8.3 Complete Reference Implementation for `SimulatorMain.kt`
Place the following self-contained simulator in `simulator/src/main/kotlin/com/oppmesh/simulator/SimulatorMain.kt` so it compiles and runs out-of-the-box:

```kotlin
package com.oppmesh.simulator

import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Strategy { DIRECT_DELIVERY, EPIDEMIC, SPRAY_AND_WAIT }

data class SimPacket(
    val id: Int,
    val source: Int,
    val destination: Int,
    val createdAtSec: Int,
    val ttlSec: Int,
    var copies: Int,
    val hopCount: Int = 0
)

data class SimConfig(
    val suiteName: String,
    val numNodes: Int,
    val radioRangeMeters: Double,
    val failureProb: Double,
    val worldSizeMeters: Double = 500.0,
    val durationSec: Int = 3600,
    val messageGenWindowSec: Int = 1800,
    val totalMessages: Int = 100,
    val ttlSec: Int = 1800,
    val sprayCopiesL: Int = 6
)

data class SimResult(
    val config: SimConfig,
    val strategy: Strategy,
    val deliveryRatio: Double,
    val avgDelaySec: Double,
    val transmissionsPerDelivered: Double,
    val totalTransmissions: Int,
    val totalContacts: Int,
    val failedContacts: Int
)

class MobileNode(val id: Int, worldSize: Double, rng: Random) {
    var x = rng.nextDouble(0.0, worldSize)
    var y = rng.nextDouble(0.0, worldSize)
    var targetX = rng.nextDouble(0.0, worldSize)
    var targetY = rng.nextDouble(0.0, worldSize)
    var speed = rng.nextDouble(1.2, 2.5)
    var pauseRemaining = 0

    // packetId -> SimPacket
    val buffer = LinkedHashMap<Int, SimPacket>()
    val knownDelivered = HashSet<Int>()

    fun step(worldSize: Double, rng: Random) {
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
            targetX = rng.nextDouble(0.0, worldSize)
            targetY = rng.nextDouble(0.0, worldSize)
            speed = rng.nextDouble(1.2, 2.5)
        } else {
            x += (dx / dist) * speed
            y += (dy / dist) * speed
        }
    }
}

fun runSingleSimulation(config: SimConfig, strategy: Strategy, seed: Long): SimResult {
    val rng = Random(seed)
    val nodes = List(config.numNodes) { MobileNode(it, config.worldSizeMeters, rng) }

    // Pre-schedule message creations
    val creationsBySec = HashMap<Int, MutableList<SimPacket>>()
    for (msgId in 1..config.totalMessages) {
        val src = rng.nextInt(config.numNodes)
        var dst = rng.nextInt(config.numNodes - 1)
        if (dst >= src) dst++
        val createdSec = rng.nextInt(0, config.messageGenWindowSec)
        val initialCopies = if (strategy == Strategy.SPRAY_AND_WAIT) config.sprayCopiesL else 1
        val pkt = SimPacket(msgId, src, dst, createdSec, config.ttlSec, initialCopies, 0)
        creationsBySec.getOrPut(createdSec) { mutableListOf() }.add(pkt)
    }

    val deliveredTimeByMsg = HashMap<Int, Int>()
    var totalTransmissions = 0
    var totalContacts = 0
    var failedContacts = 0
    val activeLinks = HashSet<Long>()

    for (t in 0 until config.durationSec) {
        // 1. Inject newly created messages
        creationsBySec[t]?.forEach { pkt ->
            nodes[pkt.source].buffer[pkt.id] = pkt
        }

        // 2. Move nodes and expire old packets
        for (node in nodes) {
            node.step(config.worldSizeMeters, rng)
            val iter = node.buffer.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (t - entry.value.createdAtSec > entry.value.ttlSec) {
                    iter.remove()
                }
            }
        }

        // 3. Check pairwise proximity
        for (i in 0 until config.numNodes) {
            for (j in i + 1 until config.numNodes) {
                val dist = hypot(nodes[i].x - nodes[j].x, nodes[i].y - nodes[j].y)
                val pairKey = (i.toLong() shl 32) or j.toLong()
                if (dist <= config.radioRangeMeters) {
                    if (activeLinks.add(pairKey)) {
                        totalContacts++
                        if (rng.nextDouble() < config.failureProb) {
                            failedContacts++
                        } else {
                            totalTransmissions += exchangePackets(nodes[i], nodes[j], strategy, t, deliveredTimeByMsg)
                            totalTransmissions += exchangePackets(nodes[j], nodes[i], strategy, t, deliveredTimeByMsg)
                        }
                    }
                } else {
                    activeLinks.remove(pairKey)
                }
            }
        }
    }

    val deliveredCount = deliveredTimeByMsg.size
    val deliveryRatio = deliveredCount.toDouble() / config.totalMessages
    val avgDelay = if (deliveredCount > 0) {
        deliveredTimeByMsg.entries.map { (id, delivSec) ->
            val createdSec = creationsBySec.values.flatten().first { it.id == id }.createdAtSec
            (delivSec - createdSec).toDouble()
        }.average()
    } else 0.0
    val txPerDelivered = if (deliveredCount > 0) {
        totalTransmissions.toDouble() / deliveredCount
    } else 0.0

    return SimResult(
        config = config,
        strategy = strategy,
        deliveryRatio = deliveryRatio,
        avgDelaySec = avgDelay,
        transmissionsPerDelivered = txPerDelivered,
        totalTransmissions = totalTransmissions,
        totalContacts = totalContacts,
        failedContacts = failedContacts
    )
}

private fun exchangePackets(
    sender: MobileNode,
    receiver: MobileNode,
    strategy: Strategy,
    currentSec: Int,
    deliveredTimeByMsg: MutableMap<Int, Int>
): Int {
    // Sync delivery ACKs first
    receiver.knownDelivered.addAll(sender.knownDelivered)
    sender.knownDelivered.addAll(receiver.knownDelivered)
    sender.buffer.keys.removeAll(sender.knownDelivered)
    receiver.buffer.keys.removeAll(receiver.knownDelivered)

    var txCount = 0
    val packetsToSend = sender.buffer.values.toList()

    for (pkt in packetsToSend) {
        if (receiver.buffer.containsKey(pkt.id) || receiver.knownDelivered.contains(pkt.id)) continue

        if (pkt.destination == receiver.id) {
            // Direct delivery to final destination
            txCount++
            deliveredTimeByMsg.putIfAbsent(pkt.id, currentSec)
            sender.knownDelivered.add(pkt.id)
            receiver.knownDelivered.add(pkt.id)
            sender.buffer.remove(pkt.id)
        } else {
            when (strategy) {
                Strategy.DIRECT_DELIVERY -> {
                    // Do not forward to intermediate relays
                }
                Strategy.EPIDEMIC -> {
                    txCount++
                    receiver.buffer[pkt.id] = pkt.copy(copies = 1, hopCount = pkt.hopCount + 1)
                }
                Strategy.SPRAY_AND_WAIT -> {
                    if (pkt.copies > 1) {
                        val giveCopies = pkt.copies / 2
                        val keepCopies = pkt.copies - giveCopies
                        txCount++
                        sender.buffer[pkt.id] = pkt.copy(copies = keepCopies)
                        receiver.buffer[pkt.id] = pkt.copy(copies = giveCopies, hopCount = pkt.hopCount + 1)
                    }
                }
            }
        }
    }
    return txCount
}

fun main() {
    val configs = listOf(
        // Suite 1: Node Density Sweep
        SimConfig("Suite1_Nodes_10", numNodes = 10, radioRangeMeters = 30.0, failureProb = 0.10),
        SimConfig("Suite1_Nodes_20", numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.10),
        SimConfig("Suite1_Nodes_40", numNodes = 40, radioRangeMeters = 30.0, failureProb = 0.10),
        // Suite 2: Contact Rate (Radio Range) Sweep
        SimConfig("Suite2_Range_15m", numNodes = 20, radioRangeMeters = 15.0, failureProb = 0.10),
        SimConfig("Suite2_Range_30m", numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.10),
        SimConfig("Suite2_Range_50m", numNodes = 20, radioRangeMeters = 50.0, failureProb = 0.10),
        // Suite 3: Connection Failure Probability Sweep
        SimConfig("Suite3_Fail_0pct", numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.00),
        SimConfig("Suite3_Fail_25pct", numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.25),
        SimConfig("Suite3_Fail_50pct", numNodes = 20, radioRangeMeters = 30.0, failureProb = 0.50)
    )

    val seeds = listOf(101L, 202L, 303L, 404L, 505L)
    val csvLines = mutableListOf(
        "suite,num_nodes,radio_range_m,fail_prob,strategy,delivery_ratio,avg_delay_s,tx_per_delivered,total_tx,total_contacts,failed_contacts"
    )

    println(String.format("%-18s | %-16s | %-10s | %-11s | %-12s", "Setting", "Strategy", "DelivRatio", "AvgDelay(s)", "Tx/Delivered"))
    println("-".repeat(78))

    for (cfg in configs) {
        for (strat in Strategy.entries) {
            val runs = seeds.map { runSingleSimulation(cfg, strat, it) }
            val avgDr = runs.map { it.deliveryRatio }.average()
            val avgDelay = runs.map { it.avgDelaySec }.average()
            val avgTxPerDeliv = runs.map { it.transmissionsPerDelivered }.average()
            val avgTx = runs.map { it.totalTransmissions }.average()
            val avgContacts = runs.map { it.totalContacts }.average()
            val avgFailed = runs.map { it.failedContacts }.average()

            println(String.format("%-18s | %-16s | %9.1f%% | %11.1f | %12.2f",
                cfg.suiteName, strat.name, avgDr * 100.0, avgDelay, avgTxPerDeliv))

            csvLines.add(
                "${cfg.suiteName},${cfg.numNodes},${cfg.radioRangeMeters},${cfg.failureProb}," +
                "${strat.name},${"%.4f".format(avgDr)},${"%.2f".format(avgDelay)}," +
                "${"%.2f".format(avgTxPerDeliv)},${"%.1f".format(avgTx)}," +
                "${"%.1f".format(avgContacts)},${"%.1f".format(avgFailed)}"
            )
        }
    }

    File("simulation_results.csv").writeText(csvLines.joinToString("\n"))
    println("\nSaved full results to simulation_results.csv")
}
```

## 9. Step-by-Step Implementation Plan for the Coding Agent

Follow these phases in order and verify each milestone before proceeding:

1. **Phase 1 — Workspace Scaffolding & Shared Models (`:core-dtn`)**:
   - Initialize the Gradle multi-module setup (`settings.gradle.kts` with `:app`, `:core-dtn`, `:simulator`).
   - Implement `RoutingStrategy`, `WireFrame`, `NetworkPacketDto`, and unit tests verifying `DirectDelivery`, `Epidemic`, and `SprayAndWait` packet selection and copy-splitting logic.
2. **Phase 2 — Simulator Execution & Verification (`:simulator`)**:
   - Add `SimulatorMain.kt` to `:simulator`.
   - Execute `./gradlew :simulator:run` and verify that `simulation_results.csv` is generated with all 9 experimental rows across the 3 strategies (27 rows total).
3. **Phase 3 — Room Database & Repository Layer (`:app`)**:
   - Implement `PacketEntity`, `ContactLogEntity`, `PacketDao`, `ContactLogDao`, and `OppMeshDatabase`.
   - Implement `DtnRepository` exposing Kotlin `Flow<List<PacketEntity>>`, `Flow<List<ContactLogEntity>>`, and CSV export functions.
4. **Phase 4 — Nearby Connections & Foreground Service (`:app`)**:
   - Implement `NearbyMeshManager` using `Nearby.getConnectionsClient(context)` with `Strategy.P2P_CLUSTER`.
   - Enforce lexicographical tie-breaking on `onEndpointFound` (`localNodeId < endpointName`) and log every callback (`onEndpointFound`, `onConnectionInitiated`, `onConnectionResult`, `onDisconnected`, `onPayloadReceived`, `onPayloadTransferUpdate`) into `ContactLogDao`.
   - Wrap `NearbyMeshManager` inside `MeshSyncForegroundService` with a persistent notification and watchdog timer.
5. **Phase 5 — Jetpack Compose UI & Permission Flow (`:app`)**:
   - Build runtime permission requester for Bluetooth/Location/Wi-Fi Nearby + Battery Optimization exemption button.
   - Build `DashboardScreen`, `MessageBufferScreen`, and `TelemetryLogScreen`.
6. **Phase 6 — Real Two-Phone & Three-Phone Verification Protocol**:
   - **Test 1 (Two-Phone Store-Carry-Forward)**:
     - Set Phone 1 to `NODE-A` and Phone 2 to `NODE-B` with Wi-Fi and cellular data turned off (Airplane mode ON, then re-enable Bluetooth & Wi-Fi toggle without internet).
     - Keep phones $>20\text{ m}$ apart (or radio off on Phone B). Compose a message on `NODE-A` addressed to `NODE-B` and tap **Store in Buffer**. Confirm status is `STORED` with `createdAtEpochMs` logged.
     - Walk `NODE-A` within $1\text{--}2\text{ m}$ of `NODE-B` (with Mesh Radio ON). Verify automatic discovery, handshake log (`CONNECTED`), payload transfer, and `DELIVERED` status on both devices. Export CSV to report exact carry time, handshake latency, and delivery delay.
   - **Test 2 (Optional Three-Phone Multi-Hop Relay)**:
     - Set Phone 1 to `NODE-A` (Room 1), Phone 2 to `NODE-C` (Carrier), and Phone 3 to `NODE-B` (Room 2).
     - Compare `DIRECT_DELIVERY` (where `NODE-C` refuses the packet destined for `NODE-B`) against `EPIDEMIC` / `SPRAY_AND_WAIT` (where `NODE-C` carries the packet from `NODE-A` in Room 1 and delivers it to `NODE-B` in Room 2).
