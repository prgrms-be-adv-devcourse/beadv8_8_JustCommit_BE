-- 카카오 연결 해제 웹훅: 재시도를 다 써서 멈춘(FAILED) 수신 기록을 저장된 재개 단계로 되돌린다 (#422)
-- 절차 · 작업 기록 → docs/infra/kakao-unlink-runbook.md (앱 시작 경로에 연결하지 않는다)
-- 입력: 같은 트랜잭션에서 먼저 set_config('replant.ops.receipt_id', '<기록 id>', true)
-- 잠금 순서는 처리기와 같은 회원 → 기록. 조건이 하나라도 맞지 않으면 예외로 전부 롤백되고 기록은 FAILED 그대로 남는다
-- 아래 NOTICE는 "실행 결과"일 뿐 커밋 성공의 증거가 아니다 (커밋 뒤 별도 조회로 확인)
DO $$
DECLARE
  v_id          bigint := current_setting('replant.ops.receipt_id')::bigint;
  v_member_id   bigint;
  v_provider_id varchar;
  v_target      bigint;
  m_status      varchar;
  m_version     integer;
  m_provider_id varchar;
  r_status      varchar;
  r_resume      varchar;
  r_member_id   bigint;
  r_provider_id varchar;
  r_version     integer;
  r_handles     boolean;
  r_attempt     integer;
  v_rows        integer;
BEGIN
  PERFORM set_config('lock_timeout', '3s', true);

  -- 1) 잠그기 전에 대상 회원을 찾는다. 연결된 회원이 없으면(PENDING에서 멈춤) 카카오 회원번호로 찾는다
  SELECT member_id, provider_id INTO v_member_id, v_provider_id
    FROM member_kakao_unlink_receipt WHERE id = v_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION '수신 기록 %이 없음', v_id;
  END IF;
  v_target := COALESCE(v_member_id,
      (SELECT id FROM member WHERE provider = 'KAKAO' AND provider_id = v_provider_id));

  -- 2) 회원 → 기록 순서로 잠근다
  IF v_target IS NOT NULL THEN
    SELECT status, token_version, provider_id INTO m_status, m_version, m_provider_id
      FROM member WHERE id = v_target FOR UPDATE;
  END IF;
  SELECT status, resume_state, member_id, provider_id, unlink_version, handles_withdrawal, attempt_no
    INTO r_status, r_resume, r_member_id, r_provider_id, r_version, r_handles, r_attempt
    FROM member_kakao_unlink_receipt WHERE id = v_id FOR UPDATE;

  -- 3) 잠근 뒤 다시 확인: 회원 연결 · 상태 · 재개 단계
  IF r_member_id IS DISTINCT FROM v_member_id OR r_provider_id IS DISTINCT FROM v_provider_id THEN
    RAISE EXCEPTION '잠그는 사이 기록 %의 회원 연결이 바뀜: 다시 확인 후 실행', v_id;
  END IF;
  IF r_member_id IS NULL AND v_target IS NOT NULL AND m_provider_id IS DISTINCT FROM r_provider_id THEN
    RAISE EXCEPTION '잠그는 사이 회원 %의 카카오 회원번호가 바뀜: 다시 확인 후 실행', v_target;
  END IF;
  IF r_status <> 'FAILED' THEN
    RAISE EXCEPTION '기록 %은 FAILED가 아님(%): 재개하지 않음', v_id, r_status;
  END IF;
  IF r_resume IS NULL OR r_resume NOT IN ('PENDING', 'INACTIVATED', 'NOTICE_PENDING') THEN
    RAISE EXCEPTION '기록 %의 재개 단계가 올바르지 않음(%): 원인 조사', v_id, r_resume;
  END IF;

  -- 4) 재개 단계별 조건
  IF r_resume = 'PENDING' THEN
    -- 아직 INACTIVE로 바꾸기 전이라 ACTIVE · INACTIVE 모두 정상. 회원이 없거나 탈퇴면 종료 스크립트(IGNORED) 대상
    IF v_target IS NULL OR m_status = 'DELETED' THEN
      RAISE EXCEPTION '기록 %: 회원이 없거나 탈퇴함 → 재개하지 않음(종료 스크립트로 IGNORED)', v_id;
    END IF;
  ELSE
    -- INACTIVATED · NOTICE_PENDING: 그 연결 해제 상태가 아직 유효해야 함
    IF v_target IS NULL OR m_status <> 'INACTIVE' OR m_version IS DISTINCT FROM r_version THEN
      RAISE EXCEPTION '기록 %: 연결 해제 상태가 유효하지 않음(회원 %, 버전 % / 기록 버전 %) → 재개하지 않음(종료 스크립트로 INVALIDATED)',
          v_id, m_status, m_version, r_version;
    END IF;
    IF r_resume = 'NOTICE_PENDING' AND NOT r_handles THEN
      RAISE EXCEPTION '기록 %: 안내 대기인데 자동 탈퇴 처리 기록이 아님 → 원인 조사', v_id;
    END IF;
  END IF;

  -- 5) 재개: 처리권(attempt_no)을 올려 이전 작업자를 무효화하고, 스케줄러가 바로 다시 집게 한다
  UPDATE member_kakao_unlink_receipt
     SET status = resume_state, resume_state = NULL, failure_count = 0, attempt_no = attempt_no + 1,
         lease_until = NULL, next_attempt_at = clock_timestamp(), updated_at = localtimestamp
   WHERE id = v_id AND status = 'FAILED';
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows <> 1 THEN
    RAISE EXCEPTION '기록 % 재개 반영이 %건: 롤백', v_id, v_rows;
  END IF;

  RAISE NOTICE '재개 예정(커밋 후 조회로 확인): 기록 %, FAILED → %, attempt_no % → %', v_id, r_resume, r_attempt, r_attempt + 1;
END
$$;
