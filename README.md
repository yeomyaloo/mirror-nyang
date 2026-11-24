# 🐱 미러냥 (MirrorNyang)

같은 Wi-Fi에 있는 **삼성 TV나 PC 브라우저로 안드로이드 폰 화면을 보내는** 미러링 앱입니다.
TV와 PC에는 아무것도 설치하지 않아도 되고, 브라우저에 주소만 입력하면 됩니다.

```
[폰: 미러냥]  화면 캡처 → H.264 인코딩 → 작은 웹 서버(:8080)
        │  같은 Wi-Fi
        ▼
[TV / PC 브라우저]  http://폰IP:8080  →  실시간 화면
```

## 사용법

1. 폰에서 **미러냥**을 열고 **미러링 시작**을 누릅니다.
2. "화면을 공유/녹화할까요?" 창이 뜨면 **지금 시작**을 누릅니다.
   Android 14 이상에서는 "전체 화면 공유"를 고르세요.
3. 폰 화면에 `http://192.168.0.12:8080` 같은 주소가 뜹니다.
4. 그 주소를 브라우저에 입력합니다.
   - **PC:** 크롬이나 엣지 주소창에 입력합니다.
   - **삼성 TV:** 리모컨 홈 → **인터넷** 앱 → 주소창에 입력합니다.
     한 번 연 다음 즐겨찾기에 넣어두면 다음부터 편합니다.
5. 화면이 나오면 **전체 화면** 버튼을 누릅니다. 리모컨으로 버튼을 선택하고 확인을 누르면 됩니다.
6. 끝낼 때는 앱의 **미러링 멈추기** 버튼이나 알림의 **멈추기** 버튼을 누릅니다.

### 두 가지 모드
| 모드 | 주소 | 특징 |
|---|---|---|
| 고화질 (기본) | `http://폰IP:8080` | H.264 영상입니다. 부드럽고 지연이 적습니다(목표 0.5초 이하). |
| 호환 | `http://폰IP:8080/?mode=jpeg` | JPEG 사진을 연속으로 보냅니다(약 12fps). 오래된 TV 브라우저에서도 잘 됩니다. |

고화질 모드로 8초 동안 화면이 안 나오면 호환 모드로 자동으로 바뀝니다. 화면 오른쪽 아래 버튼으로 직접 바꿀 수도 있습니다.

## APK 빌드 (Windows)

이 PC에 이미 있는 도구만 씁니다. 새로 설치할 것은 없습니다.

| 도구 | 버전 | 위치 |
|---|---|---|
| JDK | 17 | `C:\Program Files\Java\jdk-17` |
| Gradle | 7.6.3 | `C:\Program Files\gradle-7.6.3` (PATH에 등록됨) |
| Android SDK | platform android-30, build-tools 30.0.3 | `%LOCALAPPDATA%\Android\Sdk` |

`local.properties`의 `sdk.dir`가 위 SDK 경로를 가리킵니다. 이 파일은 git에 올리지 않습니다.
다른 PC에서 빌드하려면 그 PC의 SDK 경로로 고쳐야 합니다.

```powershell
cd $env:USERPROFILE\Documents\GitHub\mirror-nyang
```

```powershell
gradle assembleDebug
```

빌드가 끝나면 APK가 `app\build\outputs\apk\debug\app-debug.apk`에 생깁니다.

첫 빌드는 Gradle이 Android Gradle Plugin과 Kotlin 같은 라이브러리를 받아 `~/.gradle` 캐시에 저장하느라 몇 분 걸립니다.

## 폰에 설치

**방법 A: 파일로 옮기기 (케이블이나 adb 없이)**
1. `app-debug.apk`를 카카오톡 "나와의 채팅", 구글 드라이브, USB 등으로 폰에 옮깁니다.
2. 폰에서 파일을 누르고, "출처를 알 수 없는 앱 설치"를 허용한 뒤 설치합니다.
3. 플레이 프로텍트 경고가 뜨면 **무시하고 설치**를 누릅니다. 직접 만든 디버그 앱이라 뜨는 경고입니다.

**방법 B: adb로 설치**
폰에서 개발자 옵션과 USB 디버깅을 켜고 USB로 연결한 뒤 실행합니다. adb는 SDK의 platform-tools 폴더에 있습니다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

## 테스트 체크리스트

- [ ] 폰과 PC/TV가 **같은 Wi-Fi**에 연결되어 있습니다. 게스트 Wi-Fi는 기기끼리 통신을 막는 경우가 많습니다.
- [ ] 앱에서 시작을 누르면 주소와 QR 코드가 뜹니다.
- [ ] PC 크롬에서 주소를 열면 1~2초 안에 화면이 나옵니다.
- [ ] 폰에서 스크롤해보면 PC 화면이 0.5초 정도 안에 따라옵니다.
- [ ] 삼성 TV 인터넷 앱에서 같은 주소가 열립니다. 안 되면 `/?mode=jpeg`로 엽니다.
- [ ] PC와 TV에서 동시에 열어도 둘 다 나옵니다.
- [ ] 알림의 **멈추기**를 누르면 수신 화면에 "연결이 끊겼어요"가 뜹니다.

문제가 생기면 로그를 봅니다. adb를 쓸 때만 가능합니다.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s MirrorNyang MirrorNyang/Server MirrorNyang/H264
```

## 문제 해결

| 증상 | 해결 |
|---|---|
| 브라우저에서 접속이 안 됨 | 같은 Wi-Fi인지 확인하세요. 공유기의 "AP 격리"나 "게스트 네트워크" 기능을 끄세요. 주소의 IP가 폰의 Wi-Fi IP인지도 확인하세요. |
| 주소가 안 뜸 / "Wi-Fi 주소를 찾지 못했어요" | 폰을 Wi-Fi에 연결한 뒤 다시 시작하세요. |
| 검은 화면만 나옴 | 주소 끝에 `/?mode=jpeg`를 붙여 호환 모드로 열어보세요. |
| 넷플릭스 같은 앱 화면이 검게 나옴 | 저작권 보호(DRM) 화면은 안드로이드가 캡처를 막습니다. 정상 동작입니다. |
| 폰을 가로로 돌리면 화면이 작아짐 | MVP에서는 시작할 때의 방향으로 크기가 고정됩니다. 가로로 쓰려면 폰을 가로로 둔 상태에서 시작하세요. |

## 구조

```
app/src/main/
├── java/com/mirrornyang/app/
│   ├── MainActivity.kt    # 시작/정지 버튼, 접속 주소 + QR 표시
│   ├── MirrorService.kt   # 포그라운드 서비스: MediaProjection, 알림, 파이프라인 관리
│   ├── H264Encoder.kt     # VirtualDisplay → MediaCodec(H.264, Baseline, 720p, 4Mbps)
│   ├── JpegCapturer.kt    # 호환 모드: ImageReader → JPEG (960px, 약 12fps)
│   ├── StreamServer.kt    # 직접 구현한 HTTP + WebSocket 서버 (외부 라이브러리 없음)
│   └── NetUtil.kt         # 폰의 사설망 IPv4 주소 찾기
└── assets/
    ├── index.html         # 수신 페이지 (구형 TV 브라우저도 돌도록 ES5로 작성)
    └── jmuxer.min.js      # H.264 → MSE 재생 라이브러리 (jmuxer 2.0.7, MIT)
```

- 미러링 영상은 수신기가 처음 접속할 때부터 만들기 시작합니다. 아무도 보지 않으면 인코딩하지 않습니다.
- 네트워크가 느린 수신기는 쌓인 프레임을 버리고 다음 키프레임부터 다시 받습니다. 그래서 지연이 계속 쌓이지 않습니다.
- 수신 페이지는 버퍼가 0.5초 넘게 쌓이면 맨 끝으로 건너뜁니다.

## 설계 결정 메모

- **수신은 브라우저로 합니다.** 삼성 TV나 PC에 따로 앱을 깔 필요가 없습니다.
  - Miracast는 서드파티 앱에 API가 없어서 쓸 수 없습니다.
  - DLNA는 버퍼링 때문에 지연이 수 초라 미러링에 맞지 않습니다.
  - Google Cast는 삼성 TV에서 지원하지 않습니다.
- **WebCodecs 대신 MSE(jmuxer)를 씁니다.** WebCodecs는 HTTPS나 localhost 같은 보안 컨텍스트에서만 동작하는데, `http://192.168.x.x`는 보안 컨텍스트가 아닙니다.
- **기기는 주소를 화면에 표시해서 연결합니다.** 브라우저는 mDNS 탐색을 할 수 없기 때문입니다. 카메라가 있는 기기를 위해 QR 코드도 같이 보여줍니다.
- **compileSdk/targetSdk는 30입니다.** 이 PC에 설치된 SDK에 맞춘 값입니다. APK로 직접 배포하므로 Play 스토어의 targetSdk 요구사항과는 상관없습니다.

## 다음 단계 아이디어

- 소리 전송 (Android 10 이상, AudioPlaybackCapture)
- 화면 회전 따라가기
- 접속 PIN: 지금은 같은 Wi-Fi에 있는 누구나 주소를 알면 볼 수 있습니다. 공용 Wi-Fi에서는 쓰지 마세요.
- 화질과 비트레이트 설정 화면
