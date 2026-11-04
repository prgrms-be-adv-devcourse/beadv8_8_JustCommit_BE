# AWS 사용 규칙

> 언제 읽나: AWS 리소스를 만들거나 권한을 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: AWS 계정 지침 (운영진)

## 허용 범위

| 항목 | 허용 |
|---|---|
| EC2 | t3.large 1대 + t3.medium 1대 (최대 t3.large) |
| EBS | gp3 최대 50GB |
| 네트워크 | VPC 팀당 1 · IGW 1 · Elastic IP 인스턴스당 1 |
| S3 | 범용 버킷 1개 |
| 리전 | 서울(ap-northeast-2) · 버지니아 북부(us-east-1) |

그 밖의 서비스(ACM, ElastiCache, RDS, Lambda, Rekognition 등)는 aws 채널에 근거와 함께 문의합니다. 기본 방침은 관리형 대신 직접 구성입니다 (예: Let’s Encrypt).

## 태그

모든 리소스에 순서대로: 1. `Name` = `team06-<리소스 이름>` 2. `Team` = `devcos-team06`

## IAM 역할

⬜ product가 Rekognition · S3를 쓰려면 EC2 인스턴스 역할에 권한을 붙이고, 컨테이너에서 역할 자격 증명을 받도록 IMDSv2 hop limit을 2로 바꿉니다. 액세스 키를 코드 · `.env`에 두지 않는 방식입니다.

## 주의

- 액세스 키 유출은 2OUT, 계정 외부 공유 · 목적 외 사용은 1OUT입니다. pem · 키를 push하지 않습니다.
- EC2는 09:00~18:00 자동 실행 (수료 후 10:00~19:00), 그 밖 시간은 수동 Start/Stop
- 지원 기간이 끝나기 2주 안에 개인 계정 · 로컬로 옮깁니다.
