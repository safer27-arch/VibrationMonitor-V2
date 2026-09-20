Vibration Monitor - Android Studio 없이 APK 만들기
==================================================

[목표]
이 폴더 전체를 GitHub 저장소에 올리면 GitHub가 자동으로 APK를 빌드합니다.
Android Studio는 필요하지 않습니다.

[가장 쉬운 순서]
1. github.com 에 로그인합니다.
2. 오른쪽 위 + 버튼 -> New repository 를 누릅니다.
3. Repository name 에 VibrationMonitor 를 입력합니다.
4. Public 을 선택하고 Create repository 를 누릅니다.
5. 이 ZIP을 압축 해제한 뒤, 폴더 안의 파일/폴더 전체를 GitHub 저장소에 업로드합니다.
   반드시 .github 폴더도 함께 올라가야 합니다.
6. GitHub 저장소 상단 Actions 메뉴를 누릅니다.
7. 왼쪽에서 'Build Android APK'를 누릅니다.
8. Run workflow 버튼을 누릅니다.
9. 작업이 성공하면 실행 결과 페이지 아래 Artifacts에서
   'VibrationMonitor-APK'를 다운로드합니다.
10. 압축을 풀면 VibrationMonitor.apk 가 있습니다.

[스마트폰에서 바로 다운로드 가능한 링크 만들기]
1. GitHub 저장소 -> Actions
2. 'Publish APK Download' 선택
3. Run workflow 실행
4. 성공 후 저장소의 Releases 메뉴로 이동
5. 최신 Release의 Assets 아래 VibrationMonitor.apk 를 누르면 스마트폰에서 바로 다운로드할 수 있습니다.

[스마트폰 설치]
- 다운로드한 VibrationMonitor.apk 를 터치합니다.
- Android가 '알 수 없는 앱 설치' 허용을 요청하면 브라우저/파일 앱에 대해 이번 설치를 허용합니다.
- 설치 후 Vibration Monitor를 실행합니다.
- GPS 권한 요청이 나오면 허용합니다.

[현재 버전 기능]
- 스마트폰 가속도 센서 기반 실시간 진동 그래프
- 현재 / 평균 / 최대 / 최소 진동값
- SPEC(g) 설정
- SPEC 초과 시 소리/진동 경고
- 이벤트 발생 약 3초 전 + 3초 후 CSV 저장
- GPS 위치 기록
- 저장 이벤트 파일 목록 확인

[주의]
- 현재 APK는 테스트용 Debug APK입니다.
- 산업용 교정 진동계와 동일한 계측 정확도를 보장하지 않습니다.
- 자동 사진 촬영 / 이메일 / 카카오톡 전송은 다음 버전에서 추가할 기능입니다.
