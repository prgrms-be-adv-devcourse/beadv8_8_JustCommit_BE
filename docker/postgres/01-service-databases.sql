-- 로컬 개발용: 서비스별 Database · 계정 생성 (#231, 규칙 #253)
-- 여러 번 실행해도 안전 (이미 있으면 건너뜀). 기존 justcommit DB와 데이터는 건드리지 않음
-- 비밀번호는 로컬 전용 개발값. 운영은 환경변수로 받는 별도 절차 (인프라 담당)

-- 1) 서비스 계정: {service}_app
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', r, r)
FROM unnest(ARRAY['member_app', 'product_app', 'market_app', 'payment_app', 'settlement_app', 'notification_app']) AS r
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r)
\gexec

-- 2) 서비스 DB: {service}_db, 소유자는 해당 서비스 계정
SELECT format('CREATE DATABASE %I OWNER %I', s || '_db', s || '_app')
FROM unnest(ARRAY['member', 'product', 'market', 'payment', 'settlement', 'notification']) AS s
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = s || '_db')
\gexec

-- 3) 모든 계정(PUBLIC)의 기본 접속 권한 회수 → 자기 계정만 접속
SELECT format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', s || '_db')
FROM unnest(ARRAY['member', 'product', 'market', 'payment', 'settlement', 'notification']) AS s
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', s || '_db', s || '_app')
FROM unnest(ARRAY['member', 'product', 'market', 'payment', 'settlement', 'notification']) AS s
\gexec
