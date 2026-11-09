# AWS 사용 규칙

> 언제 읽나: AWS 리소스를 만들거나 권한을 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-08 · 관련: AWS 계정 지침 (운영진)

## 허용 범위

| 항목 | 허용 |
|---|---|
| EC2 | t3.large 1대 + t3.medium 1대 |
| EBS | gp3 최대 50GB |
| 네트워크 | VPC 1 · Elastic IP 인스턴스당 1 |
| S3 | 버킷 1개 (`team06-replant-images`, `products/*` · `species/*`만 공개 읽기). 경로 `products/{판매자ID}/{UUID}.확장자` · `species/{농사로ID}/{파일명}`. 업로드는 서버가 발급한 10분짜리 Presigned PUT으로 프론트가 직접 (jpg · png, 최대 10MB). 등록되지 않은 사진은 수명 주기 규칙(태그 `status=pending`, 1일 후 삭제)으로 지움. CORS는 운영 도메인과 로컬만. 로컬은 RustFS |
| 리전 | **서울(ap-northeast-2)만** |

RDS · ElastiCache · Rekognition 등 그 밖의 서비스는 aws 채널에 근거와 함께 문의합니다 (기본은 직접 구성). AWS 설정을 바꾸면 운영진 현황판을 갱신합니다.

## 태그

모든 리소스에 순서대로: 1. `Name` = `team06-<리소스 이름>` 2. `Team` = `devcos-team06`

## IAM 역할

- 앱은 AWS 키 없이 EC2 IAM 역할을 씁니다 (S3 두 경로의 `PutObject` · `DeleteObject`만).
- IMDSv2 필수 + 홉 제한 2 (컨테이너가 역할을 쓰려면 필요, 새 인스턴스마다 설정).
- 사진 검수는 AWS 서비스를 쓰지 않습니다 (OpenAI · Anthropic API) → [ADR 0012](../decisions/0012-review-moderation-haiku.md)

## 주의

- 액세스 키 · `.pem`은 레포 · 채팅 · 문서에 남기지 않습니다 (유출 2OUT).
- 외부 공개 포트는 80 · 443만, SSH 22는 팀원 IP만 엽니다.
- HTTPS는 Let’s Encrypt + Certbot 자동 갱신 (replant.kro.kr)
- EC2는 09:00~18:00 자동 실행, 그 밖 시간은 수동 Start/Stop
- 지원 기간이 끝나기 2주 안에 개인 계정 · 로컬로 옮깁니다.
