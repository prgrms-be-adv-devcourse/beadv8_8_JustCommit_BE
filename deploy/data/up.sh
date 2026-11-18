#!/usr/bin/env bash
# 데이터 서버 컨테이너 기동 (#295). 서버 ~/replant 에서: deploy/data/up.sh
# 이 서버의 사설 IP를 DATA_IP로 넣는다 → 포트를 사설 IP에만 열고, Kafka 광고 주소로 쓴다
set -euo pipefail
cd "$(dirname "$0")/../.."

# EC2 메타데이터(IMDSv2)에서 사설 IP를 읽는다. 로컬 확인 때는 DATA_IP=127.0.0.1 로 미리 넣는다
if [ -z "${DATA_IP:-}" ]; then
  token=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
  DATA_IP=$(curl -fsS -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/local-ipv4)
fi
export DATA_IP
echo "DATA_IP=$DATA_IP"

docker compose -f docker-compose.data.yml up -d --build "$@"
docker compose -f docker-compose.data.yml ps
