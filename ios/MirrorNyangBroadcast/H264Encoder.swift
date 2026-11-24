import CoreImage
import CoreMedia
import Foundation
import ImageIO
import VideoToolbox

/// VideoToolbox H.264 인코더. 출력은 브라우저(jmuxer)가 바로 먹을 수 있는 Annex B 형식.
final class H264Encoder {
    /// (데이터, 키프레임 여부, SPS/PPS 여부)
    typealias Output = (Data, Bool, Bool) -> Void

    private let output: Output
    private var session: VTCompressionSession?
    private let lock = NSLock()
    private var forceKeyFrame = true
    private var lastKeyRequest = Date.distantPast

    private static let maxSide: Double = 1280
    private static let startCode: [UInt8] = [0, 0, 0, 1]

    init(output: @escaping Output) {
        self.output = output
    }

    /// 새 수신기가 바로 화면을 볼 수 있도록 다음 프레임을 키프레임으로 (0.5초에 한 번까지)
    func requestKeyFrame() {
        lock.lock()
        defer { lock.unlock() }
        let now = Date()
        if now.timeIntervalSince(lastKeyRequest) < 0.5 { return }
        lastKeyRequest = now
        forceKeyFrame = true
    }

    func encode(_ sampleBuffer: CMSampleBuffer) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if session == nil {
            createSession(sourceWidth: CVPixelBufferGetWidth(pixelBuffer),
                          sourceHeight: CVPixelBufferGetHeight(pixelBuffer))
        }
        guard let session = session else { return }

        lock.lock()
        let key = forceKeyFrame
        forceKeyFrame = false
        lock.unlock()

        let props: CFDictionary? = key
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary
            : nil
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        VTCompressionSessionEncodeFrame(session, imageBuffer: pixelBuffer, presentationTimeStamp: pts,
                                        duration: .invalid, frameProperties: props,
                                        infoFlagsOut: nil) { [weak self] status, _, encoded in
            guard status == noErr, let encoded = encoded else { return }
            self?.handle(encoded)
        }
    }

    func stop() {
        if let session = session {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
        }
        session = nil
    }

    // MARK: - 내부

    private func createSession(sourceWidth: Int, sourceHeight: Int) {
        // 긴 변을 1280에 맞추고 16의 배수로 (메모리 50MB 제한 + 브라우저 호환성)
        let scale = min(1.0, Self.maxSide / Double(max(sourceWidth, sourceHeight)))
        func align(_ v: Double) -> Int32 { Int32(max(16, Int((v / 16).rounded()) * 16)) }
        let width = align(Double(sourceWidth) * scale)
        let height = align(Double(sourceHeight) * scale)

        var created: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: nil, width: width, height: height, codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
            outputCallback: nil, refcon: nil, compressionSessionOut: &created)
        guard status == noErr, let s = created else {
            NSLog("MirrorNyang: VTCompressionSessionCreate failed \(status)")
            return
        }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel,
                             value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: 3_000_000))
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: 30))
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: 60))
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: NSNumber(value: 2))
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
        NSLog("MirrorNyang: encoder \(width)x\(height)")
    }

    private func handle(_ sampleBuffer: CMSampleBuffer) {
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[CFString: Any]]
        let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        let isKey = !notSync

        // 키프레임마다 SPS/PPS를 먼저 보낸다
        if isKey, let format = CMSampleBufferGetFormatDescription(sampleBuffer),
           let config = Self.parameterSets(format) {
            output(config, false, true)
        }

        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        let length = CMBlockBufferGetDataLength(block)
        var avcc = Data(count: length)
        let copied = avcc.withUnsafeMutableBytes { raw -> OSStatus in
            guard let base = raw.baseAddress else { return -1 }
            return CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: base)
        }
        guard copied == noErr else { return }

        // AVCC(4바이트 길이 + NAL) -> Annex B(시작 코드 + NAL)
        var annexB = Data(capacity: length + 16)
        var offset = 0
        while offset + 4 <= length {
            let nalLength = Int(avcc[offset]) << 24 | Int(avcc[offset + 1]) << 16
                | Int(avcc[offset + 2]) << 8 | Int(avcc[offset + 3])
            offset += 4
            guard nalLength > 0, offset + nalLength <= length else { break }
            annexB.append(contentsOf: Self.startCode)
            annexB.append(avcc.subdata(in: offset..<(offset + nalLength)))
            offset += nalLength
        }
        if !annexB.isEmpty {
            output(annexB, isKey, false)
        }
    }

    private static func parameterSets(_ format: CMFormatDescription) -> Data? {
        var count = 0
        guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            format, parameterSetIndex: 0, parameterSetPointerOut: nil, parameterSetSizeOut: nil,
            parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil) == noErr else { return nil }
        var data = Data()
        for i in 0..<count {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            guard CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format, parameterSetIndex: i, parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil) == noErr,
                let p = pointer else { return nil }
            data.append(contentsOf: startCode)
            data.append(p, count: size)
        }
        return data
    }
}

/// 호환 모드: 화면을 JPEG 사진으로 (약 10fps, 긴 변 960px)
final class JpegEncoder {
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private var last: CFAbsoluteTime = 0

    func encode(_ sampleBuffer: CMSampleBuffer) -> Data? {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - last >= 0.1, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        last = now
        var image = CIImage(cvPixelBuffer: pixelBuffer)
        let scale = min(1.0, 960 / max(image.extent.width, image.extent.height))
        image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return context.jpegRepresentation(
            of: image, colorSpace: colorSpace,
            options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.6])
    }
}
