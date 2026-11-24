import Network
import ReplayKit
import SwiftUI

private let cream = Color(red: 1.0, green: 0.965, blue: 0.925)
private let peach = Color(red: 1.0, green: 0.624, blue: 0.478)
private let brown = Color(red: 0.357, green: 0.227, blue: 0.161)

struct ContentView: View {
    @State private var address = NetworkInfo.receiverURL()

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Text("🐱").font(.system(size: 72)).padding(.top, 24)
                Text("미러냥").font(.largeTitle.bold()).foregroundColor(brown)
                Text("폰 화면을 TV·PC로 냥~ 하고 보내요").foregroundColor(brown)

                ZStack {
                    Circle().fill(peach).frame(width: 96, height: 96)
                    BroadcastButton().frame(width: 96, height: 96)
                }
                .padding(.top, 16)
                Text("동그라미를 누르고 **방송 시작**을 누르세요")
                    .foregroundColor(brown)

                VStack(spacing: 6) {
                    Text("TV/PC 브라우저에 이 주소를 입력하세요").font(.subheadline).foregroundColor(brown)
                    if let address = address {
                        Text(address)
                            .font(.title2.bold())
                            .foregroundColor(Color(red: 0.85, green: 0.33, blue: 0.31))
                            .textSelection(.enabled)
                    } else {
                        Text("Wi-Fi 주소를 찾지 못했어요.\nWi-Fi에 연결했는지 확인해 주세요.")
                            .multilineTextAlignment(.center)
                            .foregroundColor(.red)
                    }
                    Button("주소 새로고침") { address = NetworkInfo.receiverURL() }
                        .font(.footnote)
                }
                .padding()
                .background(RoundedRectangle(cornerRadius: 16).fill(Color.white.opacity(0.7)))
                .padding(.top, 12)

                Text("""
                TV/PC와 아이폰이 같은 Wi-Fi에 있어야 해요.
                삼성 TV는 "인터넷" 앱에서 주소를 입력하세요.
                제어 센터의 화면 기록 버튼을 길게 눌러 "미러냥"을 골라도 시작돼요.
                화면이 안 나오면 주소 끝에 /?mode=jpeg 를 붙여 보세요.
                끝낼 때는 화면 위쪽 빨간 표시나 제어 센터에서 방송을 멈추세요.
                """)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .foregroundColor(brown.opacity(0.8))
                .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(cream.ignoresSafeArea())
        .onAppear {
            address = NetworkInfo.receiverURL()
            LocalNetworkPermission.trigger()
        }
    }
}

/// 시스템 방송 시작 버튼. 누르면 iOS의 "방송 시작" 화면이 뜬다.
struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 96, height: 96))
        picker.preferredExtension = Self.extensionBundleID()
        picker.showsMicrophoneButton = false
        picker.tintColor = .white
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}

    /// Sideloadly/AltStore로 재서명하면 번들 ID가 바뀔 수 있어서, 앱 안의 확장을 직접 찾는다.
    static func extensionBundleID() -> String? {
        guard let url = Bundle.main.builtInPlugInsURL,
              let items = try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)
        else { return nil }
        return items.first { $0.pathExtension == "appex" }.flatMap { Bundle(url: $0)?.bundleIdentifier }
    }
}

/// iOS 14+ 로컬 네트워크 권한 창을 미리 띄운다.
/// (방송 확장은 권한 창을 띄울 수 없어서, 앱을 처음 열 때 받아둬야 TV/PC가 접속할 수 있다)
enum LocalNetworkPermission {
    private static var listener: NWListener?

    static func trigger() {
        guard listener == nil, let l = try? NWListener(using: .tcp) else { return }
        l.service = NWListener.Service(name: "MirrorNyang", type: "_mirrornyang._tcp")
        l.newConnectionHandler = { $0.cancel() }
        l.start(queue: .main)
        listener = l
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
            l.cancel()
            listener = nil
        }
    }
}
