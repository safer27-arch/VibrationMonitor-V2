Vibration Monitor v1 - 쉬운 안내

이 파일은 Android 프로젝트 원본입니다.
현재 이 대화 환경에는 Android SDK/Gradle 빌드 도구가 없어 APK로 직접 컴파일하지는 못했습니다.

v1 기능
1. 스마트폰 가속도 센서로 실시간 진동값 표시
2. 실시간 그래프
3. 현재/평균/최대/최소 진동값
4. SPEC(g) 값 직접 입력
5. SPEC 초과 시 소리 + 진동 알림
6. SPEC 초과 약 3초 전 + 3초 후 데이터를 CSV 저장
7. GPS 위치와 정확도도 CSV에 함께 저장
8. 저장 파일 목록 확인

주의
- 스마트폰 내장 센서는 산업용 교정 진동계가 아닙니다.
- 현재 버전은 사진 자동촬영, 이메일, 카카오톡 전송 전 단계입니다.
- Android 14 이상에서는 백그라운드 카메라/위치 사용에 추가 제한이 있어 다음 버전에서 Foreground Service 구조로 확장해야 합니다.

CSV 저장 위치
앱 전용 Documents/events 폴더
(파일관리 앱에서 Android/data/com.example.vibrationmonitor/files/Documents/events 쪽에 저장될 수 있습니다.)

다음 단계
- 설치 가능한 APK 빌드
- 백그라운드 측정
- SPEC 초과 시 사진 촬영
- 이메일 자동 전송
- 카카오톡 알림
