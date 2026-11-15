# 가짜 외부 서버 (#351)

부하 측정 때 실제 택배사 · 토스를 부르지 않도록 WireMock 하나를 앱 앞에 둔다. 서버의 `docker-compose.prod.yml`(실험 브랜치) 서비스 `perf-fakes`로 뜬다.

| 대상 | 동작 | 매핑 |
|---|---|---|
| 택배 `/api/v1/shipments/**` | 404-express로 그대로 중계하고 **지연만 더함** (배송 진행 · 웹훅은 404-express가 함) | `mappings/courier.json` |
| 토스 `POST /v1/payments/confirm` | 요청의 `paymentKey` · `orderId` · `amount`를 돌려주며 항상 `DONE` | `mappings/toss-confirm.json` |

앱은 `perf` 프로필에서 두 `base-url`을 `http://perf-fakes:8080`으로 본다 (`application-perf.yml`).

## 지연 바꾸기 (가설 4)

SSH 터널(`-L 18089:127.0.0.1:8089`)을 연 상태에서:

```bash
perf/fakes/set-delay.sh courier 1000   # 택배 예약 응답 1초 늦춤
perf/fakes/set-delay.sh toss 5000      # 토스 승인 5초 늦춤
perf/fakes/set-delay.sh courier 0      # 원래대로
```

- 재시작 없이 바로 적용된다. 컨테이너를 다시 띄우면 매핑 파일 값(0)으로 돌아간다.
- 앱의 택배 호출은 응답 대기 5초에서 끊긴다 (`CourierClient` read timeout). 5초 이상이면 타임아웃 경로를 재게 된다.
- 기준선 측정도 이 중계를 거치므로 중계 한 단계의 비용은 모든 측정에 똑같이 들어간다.

## 404-express 배송 진행 켜고 끄기

404-express `.env`의 `AUTO_PROGRESS=false`로 끈다 (재시작 필요). 주문 한계점 측정은 끄고, 섞어서 · Soak는 켠다.
