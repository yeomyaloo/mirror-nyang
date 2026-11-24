import CryptoKit
import Foundation
import Network

/// 아이폰 안에서 도는 작은 HTTP + WebSocket 서버 (안드로이드판 StreamServer.kt와 같은 규칙).
///
/// - GET /               -> 수신 페이지(index.html)
/// - GET /jmuxer.min.js  -> H.264 재생 라이브러리
/// - WS  /ws/h264        -> H.264(Annex B) 프레임
/// - WS  /ws/jpeg        -> JPEG 프레임 (호환 모드)
final class StreamServer {
    enum Kind { case h264, jpeg }

    /// 새 H.264 수신기가 붙었을 때 (키프레임 요청용)
    var onH264ClientJoined: (() -> Void)?

    private let port: UInt16
    private let queue = DispatchQueue(label: "mirrornyang.server")
    private var listener: NWListener?
    private var clients: [ObjectIdentifier: Client] = [:]   // queue에서만 접근
    private var h264Config: Data?                           // queue에서만 접근

    private let countLock = NSLock()
    private var counts: [Kind: Int] = [:]

    private static let maxPendingH264 = 45   // 약 1.5초분이 밀리면 버리고 키프레임부터 다시
    private static let wsGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private final class Client {
        let connection: NWConnection
        var kind: Kind?
        var pending = 0
        var waitingForKey = true
        var buffer = Data()
        init(_ connection: NWConnection) { self.connection = connection }
    }

    init(port: UInt16) {
        self.port = port
    }

    func start() throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { throw NSError(domain: "MirrorNyang", code: 2) }
        let l = try NWListener(using: params, on: nwPort)
        l.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        l.stateUpdateHandler = { state in NSLog("MirrorNyang: listener \(state)") }
        l.start(queue: queue)
        listener = l
    }

    func stop() {
        queue.sync {
            listener?.cancel()
            listener = nil
            clients.values.forEach { $0.connection.cancel() }
            clients.removeAll()
        }
        updateCounts([:])
    }

    func clientCount(_ kind: Kind) -> Int {
        countLock.lock()
        defer { countLock.unlock() }
        return counts[kind] ?? 0
    }

    /// @return 느린 수신기가 있어서 키프레임이 새로 필요하면 true
    func broadcastH264(_ frame: Data, isKey: Bool, isConfig: Bool) -> Bool {
        queue.sync {
            if isConfig { h264Config = frame }
            var needKey = false
            for client in clients.values where client.kind == .h264 {
                if isConfig {
                    send(client, opcode: 0x2, payload: frame)
                    continue
                }
                if client.waitingForKey {
                    if !isKey { needKey = true; continue }
                    client.waitingForKey = false
                }
                if client.pending > Self.maxPendingH264 {
                    client.waitingForKey = true
                    needKey = true
                    continue
                }
                send(client, opcode: 0x2, payload: frame)
            }
            return needKey
        }
    }

    /// 수신기가 이전 사진을 아직 받는 중이면 버린다 (항상 최신 화면만)
    func broadcastJpeg(_ frame: Data) {
        queue.async {
            for client in self.clients.values where client.kind == .jpeg && client.pending < 2 {
                self.send(client, opcode: 0x2, payload: frame)
            }
        }
    }

    // MARK: - 연결 처리 (queue)

    private func accept(_ connection: NWConnection) {
        let client = Client(connection)
        let id = ObjectIdentifier(client)
        clients[id] = client
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.remove(client)
            default: break
            }
        }
        connection.start(queue: queue)
        readRequest(client)
    }

    private func remove(_ client: Client) {
        guard clients.removeValue(forKey: ObjectIdentifier(client)) != nil else { return }
        client.connection.cancel()
        recount()
    }

    private func readRequest(_ client: Client) {
        client.connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data { client.buffer.append(data) }
            if let end = client.buffer.range(of: Data("\r\n\r\n".utf8)) {
                let head = String(decoding: client.buffer[..<end.lowerBound], as: UTF8.self)
                client.buffer.removeAll()
                self.route(client, head: head)
            } else if isComplete || error != nil || client.buffer.count > 16 * 1024 {
                self.remove(client)
            } else {
                self.readRequest(client)
            }
        }
    }

    private func route(_ client: Client, head: String) {
        let lines = head.components(separatedBy: "\r\n")
        let parts = (lines.first ?? "").split(separator: " ")
        guard parts.count >= 2 else { return remove(client) }
        let path = String(parts[1].split(separator: "?", maxSplits: 1).first ?? "")
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let idx = line.firstIndex(of: ":") else { continue }
            headers[line[..<idx].trimmingCharacters(in: .whitespaces).lowercased()] =
                line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
        }

        if headers["upgrade"]?.lowercased() == "websocket", let key = headers["sec-websocket-key"] {
            switch path {
            case "/ws/h264": upgrade(client, key: key, kind: .h264)
            case "/ws/jpeg": upgrade(client, key: key, kind: .jpeg)
            default: respond(client, status: "404 Not Found", type: "text/plain", body: Data("not found".utf8))
            }
            return
        }

        switch path {
        case "/", "/index.html":
            respond(client, status: "200 OK", type: "text/html; charset=utf-8", body: resource("index", "html"))
        case "/jmuxer.min.js":
            respond(client, status: "200 OK", type: "application/javascript; charset=utf-8", body: resource("jmuxer.min", "js"))
        default:
            respond(client, status: "404 Not Found", type: "text/plain", body: Data("not found".utf8))
        }
    }

    private func resource(_ name: String, _ ext: String) -> Data {
        guard let url = Bundle.main.url(forResource: name, withExtension: ext),
              let data = try? Data(contentsOf: url) else { return Data("missing \(name).\(ext)".utf8) }
        return data
    }

    private func respond(_ client: Client, status: String, type: String, body: Data) {
        let header = "HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\n"
            + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.connection.send(content: Data(header.utf8) + body, completion: .contentProcessed { [weak self] _ in
            self?.remove(client)
        })
    }

    // MARK: - WebSocket (queue)

    private func upgrade(_ client: Client, key: String, kind: Kind) {
        let digest = Insecure.SHA1.hash(data: Data((key + Self.wsGUID).utf8))
        let accept = Data(digest).base64EncodedString()
        let header = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: \(accept)\r\n\r\n"
        client.connection.send(content: Data(header.utf8), completion: .contentProcessed { _ in })
        client.kind = kind
        client.waitingForKey = true
        if kind == .h264, let config = h264Config {
            send(client, opcode: 0x2, payload: config)
        }
        recount()
        NSLog("MirrorNyang: ws client \(kind)")
        if kind == .h264 { onH264ClientJoined?() }
        readFrames(client)
    }

    /// 수신기는 닫기 신호 말고는 보내는 게 없어서, 끊김(close)만 감지한다
    private func readFrames(_ client: Client) {
        client.connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, let first = data.first, first & 0x0F == 0x8 {
                self.send(client, opcode: 0x8, payload: Data())
                return self.remove(client)
            }
            if isComplete || error != nil { return self.remove(client) }
            self.readFrames(client)
        }
    }

    private func send(_ client: Client, opcode: UInt8, payload: Data) {
        var frame = Data([0x80 | opcode])
        let n = payload.count
        if n < 126 {
            frame.append(UInt8(n))
        } else if n < 65536 {
            frame.append(126)
            frame.append(UInt8(n >> 8))
            frame.append(UInt8(n & 0xFF))
        } else {
            frame.append(127)
            for i in (0..<8).reversed() { frame.append(UInt8((UInt64(n) >> (UInt64(i) * 8)) & 0xFF)) }
        }
        frame.append(payload)
        client.pending += 1
        client.connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            client.pending -= 1
            if error != nil { self?.remove(client) }
        })
    }

    private func recount() {
        var c: [Kind: Int] = [:]
        for client in clients.values {
            if let kind = client.kind { c[kind, default: 0] += 1 }
        }
        updateCounts(c)
    }

    private func updateCounts(_ c: [Kind: Int]) {
        countLock.lock()
        counts = c
        countLock.unlock()
    }
}
