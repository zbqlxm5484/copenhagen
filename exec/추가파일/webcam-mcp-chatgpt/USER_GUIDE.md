# 설치부터 ChatGPT 촬영까지

## 1. 준비

- Windows x64 노트북, 내장 또는 USB 웹캠.
- [Python 공식 사이트](https://www.python.org/downloads/windows/)의 Python 3.11 x64. 설치할 때 Python Launcher와 Tcl/Tk를 포함합니다.
- 최초 의존성 설치 및 ChatGPT 연결에 인터넷 필요.
- 본인 ChatGPT 계정의 사용자 정의 MCP 앱/개발자 모드 접근 권한.
- 본인 OpenAI Platform 조직에서 터널을 만들 권한(Read + Manage), 사용할 권한(Read + Use).
- 본인이 발급한 런타임 API 키. 관리자 키는 사용하지 않습니다.

ChatGPT 구독과 Platform 터널 권한은 별도입니다. 계정에서 기능이 제공되지 않거나 조직 정책으로 막혀 있으면 관리자 확인이 필요합니다. 키를 발급했다고 앱 사용 권한이 자동 부여되지는 않습니다.

카메라 가림막을 열고 Windows의 카메라 접근 및 데스크톱 앱 카메라 접근 권한을 허용합니다. 카메라를 사용하는 회의 앱은 종료합니다.

## 2. 본인 터널 만들기

1. 본인 계정으로 [OpenAI Platform Tunnels](https://platform.openai.com/settings/organization/tunnels)에 로그인합니다.
2. `Create tunnel`을 누릅니다.
3. 이름: `Laptop Webcam PoC` 또는 본인이 구분할 수 있는 이름.
4. 설명: `개인 노트북 웹캠에서 요청 시 사진 한 장을 촬영하는 로컬 MCP`.
5. 자신의 Platform 조직 및 사용할 ChatGPT 워크스페이스를 선택합니다.
6. 생성된 `tunnel_...` 형태의 ID를 복사합니다.

ChatGPT에서 터널이 보이지 않으면 해당 ChatGPT 워크스페이스 연결과 Read + Use 권한을 확인합니다. 다른 사람의 터널 ID를 복사해 쓰지 않습니다.

## 3. 본인 키 발급

[Platform API keys](https://platform.openai.com/settings/organization/api-keys)에서 본인 런타임 키를 발급합니다. 해당 키의 주체가 터널 Read + Use 권한을 가져야 합니다. 키 값은 본인이 보관하고 채팅이나 테스트 보고서에 붙여넣지 않습니다.

## 4. 노트북에서 연결 실행

1. ZIP 전체를 압축 해제합니다. 가상환경을 다른 PC에서 복사하지 않습니다.
2. `Connect ChatGPT.cmd`를 더블클릭합니다.
3. `Your OpenAI tunnel ID`에 자신의 ID를 입력합니다.
4. 최초 실행 시 가상환경과 패키지가 설치되고 로컬 MCP 연결 검사가 실행됩니다.
5. `Runtime API key` 프롬프트에서 자신의 키를 붙여넣고 Enter를 누릅니다. 입력은 숨겨집니다.
6. 연결 검사에 오류가 없다면 터널이 실행됩니다. 창을 유지합니다.
7. 브라우저에서 [로컬 터널 준비 상태](http://127.0.0.1:18765/readyz)를 열어 `ready`를 확인합니다. 오류가 나면 실행 창의 원인을 확인합니다.

API 키는 현재 프로세스 환경으로만 전달되고 소스·프로필·로그 파일에 저장하도록 구현하지 않았습니다. 런처는 기존 환경 변수 키를 자동 재사용하지 않고 매번 입력을 받습니다.

프로필은 `profiles/터널ID/webcam.yaml`에 생성됩니다. 각 계정의 ID별로 분리되지만 같은 PC에서는 한 번에 터널 하나만 실행합니다. 다른 터널이 18765번 포트를 쓰면 실행이 중단됩니다.

## 5. 본인 ChatGPT에 앱 등록

터널을 실행한 상태에서 본인 계정으로 [ChatGPT 플러그인](https://chatgpt.com/plugins)에 접속합니다.

1. 개발자 모드가 필요하면 설정에서 활성화합니다. 메뉴 위치와 접근 가능 여부는 계정에 따라 다를 수 있습니다.
2. `앱 만들기` → `MCP 앱 만들기`를 선택합니다.
3. 아래 값을 입력합니다.

| 항목 | 값 |
|---|---|
| 이름 | Laptop Webcam PoC |
| 설명 | 내 노트북 웹캠 사진 촬영 |
| 연결 | 터널 |
| 사용 가능한 터널 | 2단계에서 본인이 생성한 터널 |
| MCP 인증 | 인증 없음 |

여기서 ‘인증 없음’은 노트북 내부 MCP에 별도 OAuth를 두지 않았다는 뜻입니다. 외부 접근은 계정과 연결된 OpenAI Secure MCP Tunnel에서 제어합니다. 이 MCP 주소를 공개 포트로 노출하지 않습니다.

4. 안내를 확인하고 `만들기` → `연결하기`를 진행합니다.
5. 도구 목록에 `camera_status`와 `capture_photo`가 표시되는지 확인합니다.

## 6. 사용

ChatGPT 새 대화에서 `@Laptop`을 입력해 설치한 앱을 선택합니다.

먼저 연결만 확인:

> camera_status 도구로 연결 상태를 확인해줘. 사진은 촬영하지 마.

정상 결과는 ready=true입니다. 이는 서버 상태이며 실제 카메라의 성공을 보장하지 않습니다.

촬영 및 분석:

> capture_photo로 노트북 웹캠 사진 한 장 찍고, 무엇이 보이는지 설명해줘.

촬영 승인 메시지가 표시되면 내용을 확인합니다. 촬영되는 카메라는 **MCP를 실행 중인 노트북의 웹캠**입니다. 사진은 ChatGPT로 전송됩니다. 스마트폰에서 요청하더라도 스마트폰 카메라를 촬영하는 구조가 아닙니다. 모바일 앱 지원 여부는 별도이며 우선 PC 웹에서 검증합니다.

사진을 서버가 파일로 저장하지는 않습니다. 로컬 촬영 UI는 `photos`에 저장하므로 저장 동작이 다릅니다.

## 7. 종료·재실행·계정 변경

- 종료: 터널 창에서 Ctrl+C → 창 닫기 → `Stop Webcam.cmd` 실행.
- 재실행: `Connect ChatGPT.cmd`에서 자신의 터널 ID와 키를 다시 입력. ChatGPT 앱 재등록은 보통 필요하지 않습니다.
- 계정 변경: 기존 터널을 먼저 종료하고, 새 계정의 터널 ID와 키로 실행한 후 새 계정에 앱을 등록합니다.
- 같은 PC에 다른 배포본이 이미 실행 중이면 먼저 그 배포본을 종료합니다. 포트 8765는 한 서버만 사용할 수 있습니다.
- 로컬 촬영 UI를 종료하면 로컬 MCP도 종료되므로 ChatGPT 사용 중에는 로컬 UI 실행/종료를 병행하지 않는 편이 좋습니다.
- 노트북 종료·절전·네트워크 단절·터널 종료 시 ChatGPT에서 촬영할 수 없습니다.

## 8. 문제 해결

| 증상 | 확인할 사항 |
|---|---|
| py/Python을 찾지 못함 | Python 3.11 x64와 Python Launcher 설치 |
| tkinter 오류 | Python 설치의 Tcl/Tk 구성 확인 |
| 포트 8765 충돌 | 기존 로컬 MCP를 종료. 다른 앱을 임의 종료하지 않음 |
| 포트 18765 충돌 | 기존 터널 종료 후 한 개만 실행 |
| 401/403 또는 doctor 실패 | 키 유효성, 조직·터널 권한, 터널 ID 확인 |
| ChatGPT에서 터널이 안 보임 | 동일 ChatGPT 워크스페이스 연결, Read + Use 권한 확인 |
| 앱 생성/도구 탐색 실패 | 터널 창 유지, readyz 확인, 로컬 서버 실행 확인 |
| 카메라 열기 실패 | Windows 권한, 가림막, 회의 앱, 카메라 번호 확인 |
| 약 15초 후 시간 초과 | 장치 점유·드라이버 확인 후 재실행 |
| 사진 없이 답변만 함 | 앱을 명시적으로 선택하고 capture_photo 호출 요청 |

카메라 하드웨어별 호환성은 추가 테스트 대상입니다. 개발 PC에서는 1280×720 JPEG 촬영 및 반복 사용, ChatGPT camera_status 호출을 검증했습니다. 이번 새 계정 배포본은 별도 계정의 실제 인증·촬영까지 검증한 것은 아닙니다.

## 9. 데이터와 배포

최초 ZIP에는 키·터널 ID·계정 ID·사진·로그·가상환경을 포함하지 않습니다. 사용 후 폴더에는 사진, 로그, 터널 ID가 들어간 프로필이 생길 수 있으므로 다른 사용자에게는 원본 ZIP을 전달합니다. API 키를 포함한 폴더를 공유하지 않습니다.

포함된 OpenAI tunnel-client v0.0.14 및 cloudflared의 라이선스·고지 파일은 `bin/tunnel-client`에 함께 제공합니다.

공식 문서: [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels), [ChatGPT 개발자 모드](https://developers.openai.com/api/docs/guides/developer-mode).
