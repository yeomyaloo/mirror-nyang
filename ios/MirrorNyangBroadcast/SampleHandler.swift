import ReplayKit

/// ReplayKit 방송 확장. 제어 센터나 앱에서 "방송 시작"을 누르면 iOS가 이 클래스를 실행한다.
/// 화면 프레임 -> H.264(또는 JPEG) -> 작은 웹 서버 -> 같은 Wi-Fi의 TV/PC 브라우저
/// 주의: 방송 확장은 메모리를 약 50MB까지만 쓸 수 있다.
class SampleHandler: RPBroadcastSampleHandler {
    private var server: StreamServer?
    private var h264: H264Encoder?
    private var jpeg: JpegEncoder?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        let server = StreamServer(port: MirrorConfig.port)
        server.authToken = MirrorConfig.currentToken()
        let encoder = H264Encoder { [weak server, weak self] data, isKey, isConfig in
            if server?.broadcastH264(data, isKey: isKey, isConfig: isConfig) == true {
                self?.h264?.requestKeyFrame()
            }
        }
        server.onH264ClientJoined = { [weak encoder] in encoder?.requestKeyFrame() }
        do {
            try server.start()
        } catch {
            finishBroadcastWithError(NSError(
                domain: "MirrorNyang", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "서버를 시작하지 못했어요: \(error.localizedDescription)"]))
            return
        }
        self.server = server
        self.h264 = encoder
        self.jpeg = JpegEncoder()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video, let server = server else { return }
        // 보는 사람이 없으면 인코딩하지 않는다 (배터리/메모리 절약)
        if server.clientCount(.h264) > 0 {
            h264?.encode(sampleBuffer)
        }
        if server.clientCount(.jpeg) > 0, let data = jpeg?.encode(sampleBuffer) {
            server.broadcastJpeg(data)
        }
    }

    override func broadcastPaused() {}
    override func broadcastResumed() {
        h264?.requestKeyFrame()
    }

    override func broadcastFinished() {
        h264?.stop()
        server?.stop()
        h264 = nil
        jpeg = nil
        server = nil
    }
}
