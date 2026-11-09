#!/usr/bin/env bash
# 로컬 개발용 RS256 키 한 쌍을 만든다. 키는 레포 밖에 두고 커밋하지 않는다.
#   사용: scripts/generate-jwt-keys.sh [저장할 폴더]   (기본: ~/.replant/jwt)
#   운영 키는 서버 ~/secrets/ 에서 따로 관리한다 (docs/reference/env-vars.md).
set -euo pipefail

DIR="${1:-$HOME/.replant/jwt}"
mkdir -p "$DIR"

if [ -e "$DIR/private.pem" ]; then
  echo "이미 키가 있습니다: $DIR/private.pem (덮어쓰지 않음. 새로 만들려면 지우고 다시 실행)" >&2
  exit 1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/private.pem" 2>/dev/null
openssl pkey -in "$DIR/private.pem" -pubout -out "$DIR/public.pem"
chmod 600 "$DIR/private.pem"

echo "만든 위치: $DIR"
echo "환경변수로 넣기 (bash):"
echo "  export JWT_PUBLIC_KEY=\"\$(cat '$DIR/public.pem')\""
echo "  export JWT_PRIVATE_KEY=\"\$(cat '$DIR/private.pem')\"   # member만"
