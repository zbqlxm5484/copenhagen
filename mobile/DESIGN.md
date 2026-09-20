# UI reference

기존 전달본의 IBM `design.md` 분석을 기준으로 한다.
원본 레퍼런스: https://getdesign.md/ibm/design-md
원본 배포 프로젝트: https://github.com/VoltAgent/awesome-design-md

색상은 IBM Blue `#0f62fe`, 본문 `#161616`, 보조 글자 `#525252`, 입력/보조 배경 `#f4f4f4`, 구분선 `#e0e0e0`, 오류 `#da1e28`이다.
버튼·입력창·카드는 사각형이며 그림자·그라데이션을 쓰지 않는다.
화면 여백 24dp, 요소 간격 8/12/16/20/24dp, 버튼 최소 높이 48~52dp, 헤드라인은 32sp Light로 구성한다.
화면 색·타이포그래피·공통 컴포넌트는 `ui/Components.kt` 한 곳에서 수정한다.

폰트 파일을 동봉하지 않는다. 플랫폼 Sans-serif 폰트를 사용하므로 IBM Plex와 글꼴 자체가 동일한 것은 아니다.
