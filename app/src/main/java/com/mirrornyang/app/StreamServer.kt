package com.mirrornyang.app

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 폰 안에서 도는 아주 작은 HTTP + WebSocket 서버.
 *
 * - GET /               -> 수신 페이지(assets/index.html)
 * - GET /jmuxer.min.js  -> H.264를 브라우저에서 재생하기 위한 라이브러리
 * - WS  /ws/h264        -> H.264(Annex B) 프레임을 바이너리 메시지로 전송
 * - WS  /ws/jpeg        -> JPEG 프레임을 바이너리 메시지로 전송 (호환 모드)
 */
class StreamServer(
    private val context: Context,
    private val listener: Listener,
) {
    enum class Kind { H264, JPEG }

    interface Listener {
        /** 새 수신기가 붙었을 때 (서버 스레드에서 호출) */
        fun onClientConnected(kind: Kind)
        fun onClientsChanged()
    }

    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<WsClient>()
    private var serverSocket: ServerSocket? = null

    /** 가장 최근의 SPS/PPS. 새 H.264 수신기에게 가장 먼저 보내야 한다. */
    @Volatile
    var h264Config: ByteArray? = null

    var port: Int = 0
        private set

    /** 8080부터 비어 있는 포트를 찾아 연다. */
    fun start(preferredPort: Int = 8080) {
        var lastError: IOException? = null
        for (p in preferredPort until preferredPort + 10) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                serverSocket = ss
                port = p
                pool.execute { acceptLoop(ss) }
                Log.i(TAG, "listening on $p")
                return
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no free port")
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        clients.forEach { it.close() }
        clients.clear()
        pool.shutdownNow()
    }

    fun clientCount(kind: Kind): Int = clients.count { it.kind == kind && it.isOpen }

    /**
     * H.264 프레임을 모든 H.264 수신기에 보낸다.
     * @return 느린 수신기가 있어서 키프레임이 새로 필요하면 true
     */
    fun broadcastH264(frame: ByteArray, isKey: Boolean, isConfig: Boolean): Boolean {
        if (isConfig) h264Config = frame
        var needKey = false
        for (c in clients) {
            if (c.kind != Kind.H264 || !c.isOpen) continue
            if (isConfig) {
                c.offerOrReset(frame)
                continue
            }
            if (c.waitingForKey) {
                if (!isKey) {
                    needKey = true
                    continue
                }
                c.waitingForKey = false
            }
            if (!c.offer(frame)) {
                // 네트워크가 못 따라오면 쌓인 걸 버리고 다음 키프레임부터 다시 시작
                c.clearQueue()
                c.waitingForKey = true
                needKey = true
            }
        }
        return needKey
    }

    /** JPEG 프레임: 수신기가 아직 이전 프레임을 보내는 중이면 버린다(항상 최신 화면만). */
    fun broadcastJpeg(frame: ByteArray) {
        for (c in clients) {
            if (c.kind != Kind.JPEG || !c.isOpen) continue
            if (c.queueSize() < 2) c.offer(frame)
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return socket.close()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return socket.close()
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            val isUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            if (isUpgrade && path.startsWith("/ws/")) {
                val kind = when (path) {
                    "/ws/h264" -> Kind.H264
                    "/ws/jpeg" -> Kind.JPEG
                    else -> return respond(socket, 404, "text/plain", "not found".toByteArray())
                }
                val key = headers["sec-websocket-key"]
                    ?: return respond(socket, 400, "text/plain", "missing key".toByteArray())
                upgrade(socket, input, key, kind)
                return
            }

            if (method != "GET" && method != "HEAD") {
                return respond(socket, 405, "text/plain", "method not allowed".toByteArray())
            }
            when (path) {
                "/", "/index.html" -> respond(socket, 200, "text/html; charset=utf-8", asset("index.html"))
                "/jmuxer.min.js" -> respond(socket, 200, "application/javascript; charset=utf-8", asset("jmuxer.min.js"))
                else -> respond(socket, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection error: ${e.message}")
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun asset(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }
        socket.getOutputStream().use { out ->
            out.write(
                ("HTTP/1.1 $code $reason\r\n" +
                    "Content-Type: $type\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.write(body)
            out.flush()
        }
        socket.close()
    }

    // ----------------------------------------------------------- WebSocket

    private fun upgrade(socket: Socket, input: InputStream, key: String, kind: Kind) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP
        )
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
        socket.soTimeout = 0

        val client = WsClient(socket, input, out, kind)
        if (kind == Kind.H264) {
            h264Config?.let { client.offer(it) }
        }
        clients.add(client)
        Log.i(TAG, "ws client connected: $kind from ${socket.inetAddress.hostAddress}")
        listener.onClientConnected(kind)
        listener.onClientsChanged()

        pool.execute { client.writeLoop() }
        client.readLoop() // 이 스레드는 수신기가 끊길 때까지 읽기 담당
        clients.remove(client)
        listener.onClientsChanged()
        Log.i(TAG, "ws client gone: $kind")
    }

    inner class WsClient(
        private val socket: Socket,
        private val input: InputStream,
        private val out: OutputStream,
        val kind: Kind,
    ) {
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_MAX)

        @Volatile
        var isOpen = true
            private set

        /** H.264: 키프레임이 올 때까지 일반 프레임을 보내지 않는다 */
        @Volatile
        var waitingForKey = true

        fun offer(frame: ByteArray): Boolean = isOpen && queue.offer(frame)

        fun offerOrReset(frame: ByteArray) {
            if (!offer(frame)) {
                queue.clear()
                offer(frame)
            }
        }

        fun clearQueue() = queue.clear()
        fun queueSize() = queue.size

        fun writeLoop() {
            try {
                while (isOpen) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    send(OP_BINARY, frame)
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun readLoop() {
            try {
                while (isOpen) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val b1 = readByte()
                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = ((readByte() shl 8) or readByte()).toLong()
                    } else if (len == 127L) {
                        len = 0
                        repeat(8) { len = (len shl 8) or readByte().toLong() }
                    }
                    if (len > MAX_INCOMING) break
                    val mask = if (masked) readFully(4) else null
                    val payload = readFully(len.toInt())
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (opcode) {
                        OP_CLOSE -> {
                            try {
                                send(OP_CLOSE, ByteArray(0))
                            } catch (_: IOException) {
                            }
                            break
                        }
                        OP_PING -> send(OP_PONG, payload)
                        else -> Unit // 수신기가 보내는 텍스트/바이너리는 지금은 쓰지 않는다
                    }
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            return b
        }

        private fun readFully(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return buf
        }

        private fun send(opcode: Int, payload: ByteArray) {
            synchronized(out) {
                val len = payload.size
                out.write(0x80 or opcode)
                when {
                    len < 126 -> out.write(len)
                    len < 65536 -> {
                        out.write(126)
                        out.write((len shr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                    else -> {
                        out.write(127)
                        for (i in 7 downTo 0) out.write(((len.toLong() shr (8 * i)) and 0xFF).toInt())
                    }
                }
                out.write(payload)
                out.flush()
            }
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            queue.clear()
            try {
                socket.close()
            } catch (_: IOException) {
            } catch (_: SocketException) {
            }
        }
    }

    companion object {
        private const val TAG = "MirrorNyang/Server"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val QUEUE_MAX = 90 // 30fps 기준 약 3초
        private const val MAX_INCOMING = 64 * 1024L

        /** CRLF로 끝나는 HTTP 헤더 한 줄을 읽는다 (최대 8KB) */
        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > 8192) throw IOException("header too long")
            }
            return buf.toString("ISO-8859-1")
        }
    }
}
