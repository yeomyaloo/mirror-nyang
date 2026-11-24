import Foundation

enum MirrorConfig {
    /// 수신 페이지/스트림 포트 (안드로이드판과 같음)
    static let port: UInt16 = 8080
}

enum NetworkInfo {
    /// 같은 Wi-Fi에서 접속할 수 있는 이 기기의 IPv4 주소.
    /// en0 = Wi-Fi, bridge100 = 개인용 핫스팟을 켰을 때
    static func wifiAddress() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var candidates: [(name: String, ip: String)] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let iface = ptr.pointee
            guard let addr = iface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: iface.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                candidates.append((name, String(cString: host)))
            }
        }
        return candidates.first { $0.name == "en0" }?.ip
            ?? candidates.first { $0.name.hasPrefix("bridge") }?.ip
    }

    static func receiverURL() -> String? {
        wifiAddress().map { "http://\($0):\(MirrorConfig.port)" }
    }
}
