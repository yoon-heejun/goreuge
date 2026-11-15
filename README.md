# 고르게

**매달 충전할 날짜를 계산하고, 한 번만 100%로 충전한 뒤 평소 목표를 복원하는 Pleos 차량 앱 프로토타입입니다.**

35%에서 평소처럼 충전하려던 운전자가 이번 달 완충을 계획합니다. 현재 잔량과 하루 평균 주행 거리, 완속 충전 가능한 요일을 사용해 20%까지의 예상 거리와 충전 후보일을 보여줍니다. 시연 기본값은 하루 23km와 월·목 밤 9시입니다. 20%까지 약 60~68km이며, 11월 16일 월요일의 도착 예상 잔량은 17~20%입니다. 거리와 잔량은 모의 입력에 따른 추정치입니다.

## Pleos에서 실행하기

[서명된 release APK](apk/SA_고르게_release.apk) · [SHA-256](apk/SHA256SUMS.txt)

```sh
adb install -r "apk/SA_고르게_release.apk"
adb shell am start -n io.cellbalance.app/.MainActivity
```

1. **이 일정으로 예약**을 누릅니다. 앱에 계획이 저장되고 평소 목표 80%가 유지됩니다.
2. **시연 도구 → 충전일 도착 · 18%**를 누릅니다. **이번만 100% 충전**을 선택하면 모의 목표가 100%로 바뀝니다. 약 8초 후 완충 완료, 80% 복원, 다음 권장일이 표시됩니다.
3. **시연 도구**에서 새 회차를 준비합니다. 도착 잔량이 24%이면 평소 목표를 유지합니다. 충전 중 신호를 끊거나 중단하면 기존 목표를 복원하고 이번 회차를 완료로 기록하지 않습니다.
4. 시연 도구에서 하루 평균 주행 거리, 평소 목표, 완속 충전 가능 요일을 바꿔 후보일을 다시 계산할 수 있습니다. 완료 뒤 **다음 달 계획 보기**로 다음 회차를 재현합니다.
5. 홈의 **완충 이력**에서 완료 날짜와 시작·완료 잔량을 최근 순으로 확인합니다. 모의 완료 기록은 다음 회차와 앱 재실행 후에도 남습니다.
6. **차량 데이터 → 시험 · 18%에서 충전 시작 → 이번만 차량 목표 100%**를 누릅니다. Pleos 에뮬레이터의 P단에서 실행하고 첫 실행에는 차량 데이터 권한을 허용합니다. 현재 차량 목표를 읽어 저장한 뒤 공개 SDK로 공통 목표를 설정합니다. **시험 · 100% 도달** 또는 **시험 · 케이블 분리**를 누르면 앱이 원래 목표를 복원합니다. 앱을 강제 종료한 경우 다시 실행하면 복원을 시도합니다.

## 구현 및 검증 범위

- 모의 운행에서 날짜 제안, 앱 내 계획 저장, 회차별 100% 목표 적용과 완료·중단 후 평소 목표 복원, 완료일과 다음 권장 시점이 동작합니다. 예약은 앱에 계획을 저장하는 기능입니다. 충전기 예약·외부 알림은 후속 범위입니다.
- 차량 데이터 화면은 공개 Vehicle SDK 2.3.2로 Pleos 에뮬레이터의 공통 충전 목표를 쓰고 Android Automotive 차량 서비스로 값을 다시 읽습니다. 현재 이미지의 Virtual Console은 충전 신호 주입을 허용하지 않아 충전 시작·완료·분리 검증은 화면의 시험 신호를 사용했습니다. 이력에 모의 운행과 Pleos 연동 시험을 따로 표시합니다.
- 실차 연결에는 차종별 지원과 권한, 실제 완속 세션과 현재 목표의 조회, 복원 확인이 필요합니다. SDK의 capability가 반환한 값은 설정 가능한 후보 목록입니다. [공개 Vehicle Data API](https://document.pleos.ai/api-reference/vehicle-data-api/vehicle-data-api/common/get-battery-charging-status)의 `targetStateOfCharge.standard`, 플러그와 완속 상태, 차량 발생 시각은 후속 입력 후보입니다. 승인된 데이터로 원래 목표를 확인하고 회차별 동의를 받아 저장한 뒤 목표를 변경합니다. 중단·재시작 후 원래 값이 읽히는지 확인할 수 있어야 차량 연동을 활성화합니다.
- 검증 환경은 Apple Silicon Mac, Pleos Connect Emulator **v2.1.0**, AAOS API 34 ARM64, 화면 **2560×1440**입니다. APK는 **0.4.1**, minSdk 28, targetSdk 35, 시연용 키로 서명한 release입니다. 앱 실행에 계정이나 API 키가 필요하지 않습니다.

## 소스 빌드

JDK 17, Android SDK 35, Build Tools 35.0.0과 공식 Maven 접근이 필요합니다. Android SDK 경로는 `ANDROID_HOME` 또는 `local.properties`에 지정합니다.

```sh
cd android-app
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

빌드 결과는 서명 전 APK입니다. Pretendard는 SIL Open Font License로 앱 소스에 포함합니다.

## 근거

- [현대자동차 아이오닉 5 2027 사용설명서의 배터리 주의사항](https://ownersmanual.hyundai.com/full_webhelp/NE1/2027/ko_KR/foreword_starting_using_battery_precautions.html)
- [Pleos Playground](https://developers.pleos.ai/playground), [공개 Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [충전 목표 변경 API](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/ev-battery/set-ev-charge-percent-limit)
