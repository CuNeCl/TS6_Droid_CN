package dev.tsdroid.bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Resolves DNS SRV records for TeamSpeak server discovery.
 *
 * TeamSpeak servers can be advertised via SRV records:
 *   _ts3._udp.example.com.  SRV  priority weight port target.
 *
 * When a user enters a bare hostname (no port), the client should look up
 * the SRV record to find the actual host:port. If no SRV record exists,
 * the default TS port 9987 is used.
 */
object DnsSrvResolver {

    private const val TAG = "DnsSrvResolver"
    private const val DEFAULT_TS_PORT = 9987
    private const val DNS_TIMEOUT_MS = 3000

    /** SRV service prefixes tried in order. */
    private val SRV_PREFIXES = listOf("_ts3._udp.", "_ts._udp.")

    data class SrvRecord(
        val priority: Int,
        val weight: Int,
        val port: Int,
        val target: String,
    )

    /**
     * Resolve a server address, performing SRV lookup when no port is given.
     *
     * - If [address] already contains a port (host:port), it is returned as-is.
     * - If [address] is a bare hostname, SRV lookup is attempted for each
     *   prefix in [SRV_PREFIXES]. The first successful lookup wins.
     * - If SRV lookup fails or times out, the address is returned with the
     *   default TS port [DEFAULT_TS_PORT].
     *
     * @return a resolved "host:port" string suitable for [dev.tslib.Client].
     */
    suspend fun resolve(address: String): String = withContext(Dispatchers.IO) {
        val trimmed = address.trim()

        // Already has a port → use as-is
        if (trimmed.contains(":")) return@withContext trimmed

        // IPv6 literal without port (e.g. [::1]) → append default port
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end >= 0) {
                val host = trimmed.substring(0, end + 1)
                return@withContext "$host:$DEFAULT_TS_PORT"
            }
        }

        // Bare hostname → try SRV lookup
        val hostname = trimmed.removeSuffix(".")
        for (prefix in SRV_PREFIXES) {
            val queryName = "$prefix$hostname"
            try {
                val records = querySrv(queryName)
                if (records.isNotEmpty()) {
                    val selected = selectByPriorityWeight(records)
                    Log.i(TAG, "SRV resolved $queryName → ${selected.target}:${selected.port}")
                    return@withContext "${selected.target}:${selected.port}"
                }
            } catch (e: Exception) {
                Log.d(TAG, "SRV query failed for $queryName: ${e.message}")
            }
        }

        // No SRV record found → use default port
        Log.i(TAG, "No SRV record for $hostname, using default port $DEFAULT_TS_PORT")
        "$hostname:$DEFAULT_TS_PORT"
    }

    /**
     * Perform a raw DNS SRV query via UDP.
     * Falls back to the system DNS servers discovered via InetAddress.
     */
    private fun querySrv(name: String): List<SrvRecord> {
        val dnsServers = getDnsServers()
        if (dnsServers.isEmpty()) return emptyList()

        val query = buildDnsQuery(name)
        val response = ByteArray(1024)

        for (server in dnsServers) {
            DatagramSocket().use { socket ->
                socket.soTimeout = DNS_TIMEOUT_MS
                try {
                    socket.send(DatagramPacket(query, query.size, server, 53))
                    val packet = DatagramPacket(response, response.size)
                    socket.receive(packet)
                    return parseDnsResponse(packet.data, packet.length, query.size)
                } catch (e: SocketTimeoutException) {
                    // Try next DNS server
                    continue
                } catch (e: Exception) {
                    Log.d(TAG, "DNS query to $server failed: ${e.message}")
                    continue
                }
            }
        }
        return emptyList()
    }

    /** Build a minimal DNS query packet for SRV record lookup. */
    private fun buildDnsQuery(name: String): ByteArray {
        val question = mutableListOf<Byte>()

        // Transaction ID (arbitrary, used for matching)
        question.addAll(listOf(0xAB.toByte(), 0xCD.toByte()))

        // Flags: standard query, recursion desired
        question.addAll(listOf(0x01.toByte(), 0x00.toByte()))

        // Questions: 1
        question.addAll(listOf(0x00.toByte(), 0x01.toByte()))

        // Answer RRs: 0
        question.addAll(listOf(0x00.toByte(), 0x00.toByte()))

        // Authority RRs: 0
        question.addAll(listOf(0x00.toByte(), 0x00.toByte()))

        // Additional RRs: 0
        question.addAll(listOf(0x00.toByte(), 0x00.toByte()))

        // QNAME: encode each label
        for (label in name.split(".")) {
            if (label.isEmpty()) continue
            question.add(label.length.toByte())
            for (c in label.toByteArray(Charsets.US_ASCII)) {
                question.add(c)
            }
        }
        question.add(0x00.toByte()) // null terminator

        // QTYPE: SRV (33)
        question.addAll(listOf(0x00.toByte(), 0x21.toByte()))

        // QCLASS: IN (1)
        question.addAll(listOf(0x00.toByte(), 0x01.toByte()))

        return question.toByteArray()
    }

    /** Parse the DNS response, extracting SRV records from the answer section. */
    private fun parseDnsResponse(data: ByteArray, length: Int, queryLen: Int): List<SrvRecord> {
        val input = DataInputStream(ByteArrayInputStream(data, 0, length))

        // Skip transaction ID (2) + flags (2) + qdcount (2) + ancount (2) + nscount (2) + arcount (2)
        input.skipBytes(2) // transaction ID
        val flags = input.readUnsignedShort()
        val qdcount = input.readUnsignedShort()
        val ancount = input.readUnsignedShort()
        input.skipBytes(4) // nscount + arcount

        // Check for DNS error (RCODE in lower 4 bits of flags)
        val rcode = flags and 0x000F
        if (rcode != 0) return emptyList()

        // Skip the question section
        repeat(qdcount) {
            skipName(input)
            input.skipBytes(4) // QTYPE (2) + QCLASS (2)
        }

        // Parse answer section
        val records = mutableListOf<SrvRecord>()
        repeat(ancount) {
            skipName(input)
            val type = input.readUnsignedShort()
            val rclass = input.readUnsignedShort()
            input.skipBytes(4) // TTL (4)
            val rdlength = input.readUnsignedShort()

            if (type == 33 && rclass == 1) { // SRV record, class IN
                val priority = input.readUnsignedShort()
                val weight = input.readUnsignedShort()
                val port = input.readUnsignedShort()
                val target = readName(input, data)
                records.add(SrvRecord(priority, weight, port, target))
            } else {
                // Skip unknown record data
                input.skipBytes(rdlength)
            }
        }
        return records
    }

    /** Skip a DNS name (handles compression pointers). */
    private fun skipName(input: DataInputStream) {
        while (true) {
            val len = input.readUnsignedByte()
            if (len == 0) return
            if ((len and 0xC0) == 0xC0) {
                input.skipBytes(1) // pointer (2 bytes total)
                return
            }
            input.skipBytes(len)
        }
    }

    /** Read a DNS name (handles compression pointers). */
    private fun readName(input: DataInputStream, fullData: ByteArray): String {
        val labels = mutableListOf<String>()
        var pos = -1

        while (true) {
            val len = input.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or input.readUnsignedByte()
                if (pos < 0) {
                    // Save current position to return to after following pointer
                    // (DataInputStream doesn't support mark/reset well, so we
                    //  handle this by reading the pointer target from fullData)
                }
                // Follow pointer
                val pointerLabels = readNameFromOffset(fullData, ptr)
                labels.addAll(pointerLabels)
                return labels.joinToString(".")
            } else {
                val bytes = ByteArray(len)
                input.readFully(bytes)
                labels.add(String(bytes, Charsets.US_ASCII))
            }
        }
        return labels.joinToString(".")
    }

    /** Read a DNS name starting at a specific offset (for compression pointers). */
    private fun readNameFromOffset(data: ByteArray, offset: Int): List<String> {
        val labels = mutableListOf<String>()
        var pos = offset
        var jumps = 0

        while (pos < data.size && jumps < 128) {
            val len = data[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                if (pos >= data.size) break
                val ptr = ((len and 0x3F) shl 8) or (data[pos].toInt() and 0xFF)
                labels.addAll(readNameFromOffset(data, ptr))
                jumps++
                break
            } else {
                if (pos + len > data.size) break
                labels.add(String(data, pos, len, Charsets.US_ASCII))
                pos += len
            }
        }
        return labels
    }

    /**
     * Select a single SRV record from the list using the standard
     * priority/weight algorithm (RFC 2782).
     */
    private fun selectByPriorityWeight(records: List<SrvRecord>): SrvRecord {
        // Group by priority, pick the lowest priority group
        val lowestPriority = records.minOf { it.priority }
        val group = records.filter { it.priority == lowestPriority }

        if (group.size == 1) return group.first()

        // Weighted selection within the priority group
        val totalWeight = group.sumOf { it.weight }
        if (totalWeight == 0) return group.random()

        var r = (1..totalWeight).random()
        for (record in group) {
            r -= record.weight
            if (r <= 0) return record
        }
        return group.last()
    }

    /**
     * Discover system DNS servers.
     * On Android, we try common public DNS servers as fallback since
     * the system DNS resolver doesn't expose SRV records.
     */
    private fun getDnsServers(): List<InetAddress> {
        val servers = mutableListOf<InetAddress>()

        // Try to get system DNS servers from connectivity manager
        try {
            // On modern Android, the system DNS resolver doesn't expose SRV.
            // We use well-known public DNS servers that support SRV records.
            val candidates = listOf(
                "8.8.8.8",         // Google Public DNS
                "8.8.4.4",         // Google Public DNS (secondary)
                "1.1.1.1",         // Cloudflare DNS
                "1.0.0.1",         // Cloudflare DNS (secondary)
                "223.5.5.5",       // Alibaba DNS (China)
                "119.29.29.29",    // Tencent DNSPod (China)
            )
            for (ip in candidates) {
                try {
                    servers.add(InetAddress.getByName(ip))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return servers
    }
}
