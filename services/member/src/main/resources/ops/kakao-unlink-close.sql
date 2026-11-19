-- 카카오 연결 해제 웹훅: 더 이상 유효하지 않다고 확인된 FAILED 수신 기록을 끝낸다 (#422)
-- 알림을 끄려는 종료가 아니다. 유효성을 잃은 것이 확인된 경우에만 끝내고, 그 밖은 예외로 롤백한다
-- 절차 · 작업 기록 → docs/infra/kakao-unlink-runbook.md (앱 시작 경로에 연결하지 않는다)
-- 입력: 같은 트랜잭션에서 먼저 set_config('replant.ops.receipt_id', '<기록 id>', true)
-- 결과는 처리기가 같은 경우에 남기는 것과 같다: PENDING에서 회원 없음 · 탈퇴 → IGNORED(MEMBER_NOT_FOUND),
-- INACTIVATED · NOTICE_PENDING에서 재연결 · 탈퇴 · 버전 불일치 → INVALIDATED (last_error · 실패 횟수 · 처리 기록 표시는 그대로)
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
  r_attempt     integer;
  v_final       varchar;
  v_rows        integer;
BEGIN
  PERFORM set_config('lock_timeout', '3s', true);

  -- 1) 잠그기 전에 대상 회원을 찾는다. 연결된 회원이 없으면 카카오 회원번호로 찾는다 (탈퇴하면 회원번호가 비워져 못 찾음)
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
  SELECT status, resume_state, member_id, provider_id, unlink_version, attempt_no
    INTO r_status, r_resume, r_member_id, r_provider_id, r_version, r_attempt
    FROM member_kakao_unlink_receipt WHERE id = v_id FOR UPDATE;

  -- 3) 잠근 뒤 다시 확인
  IF r_member_id IS DISTINCT FROM v_member_id OR r_provider_id IS DISTINCT FROM v_provider_id THEN
    RAISE EXCEPTION '잠그는 사이 기록 %의 회원 연결이 바뀜: 다시 확인 후 실행', v_id;
  END IF;
  IF r_member_id IS NULL AND v_target IS NOT NULL AND m_provider_id IS DISTINCT FROM r_provider_id THEN
    RAISE EXCEPTION '잠그는 사이 회원 %의 카카오 회원번호가 바뀜: 다시 확인 후 실행', v_target;
  END IF;
  IF r_status <> 'FAILED' THEN
    RAISE EXCEPTION '기록 %은 FAILED가 아님(%): 끝내지 않음', v_id, r_status;
  END IF;
  IF r_resume IS NULL OR r_resume NOT IN ('PENDING', 'INACTIVATED', 'NOTICE_PENDING') THEN
    RAISE EXCEPTION '기록 %의 재개 단계가 올바르지 않음(%): 원인 조사', v_id, r_resume;
  END IF;

  -- 4) 유효성을 잃은 것이 확인된 경우만 끝낸다
  IF r_resume = 'PENDING' THEN
    -- 아직 INACTIVE로 바꾸기 전이라 회원이 ACTIVE인 게 정상. 회원이 없거나 탈퇴했을 때만 끝냄
    IF v_target IS NOT NULL AND m_status <> 'DELETED' THEN
      RAISE EXCEPTION '기록 %: 회원이 있어(%) 끝낼 대상이 아님 → 원인을 해결한 뒤 재개 스크립트', v_id, m_status;
    END IF;
    v_final := 'IGNORED';
  ELSE
    -- INACTIVATED · NOTICE_PENDING: 재연결(ACTIVE) · 탈퇴 · 다시 해제(버전 다름)로 그 연결 해제 상태가 끝났을 때만
    IF v_target IS NOT NULL AND m_status = 'INACTIVE' AND m_version IS NOT DISTINCT FROM r_version THEN
      RAISE EXCEPTION '기록 %: 연결 해제 상태가 아직 유효함 → 원인을 해결한 뒤 재개 스크립트', v_id;
    END IF;
    v_final := 'INVALIDATED';
  END IF;

  UPDATE member_kakao_unlink_receipt
     SET status = v_final, resume_state = NULL, attempt_no = attempt_no + 1,
         lease_until = NULL, next_attempt_at = NULL, updated_at = localtimestamp,
         last_error = CASE WHEN v_final = 'IGNORED' THEN 'MEMBER_NOT_FOUND' ELSE last_error END
   WHERE id = v_id AND status = 'FAILED';
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  IF v_rows <> 1 THEN
    RAISE EXCEPTION '기록 % 종료 반영이 %건: 롤백', v_id, v_rows;
  END IF;

  RAISE NOTICE '종료 예정(커밋 후 조회로 확인): 기록 %, FAILED(재개 단계 %) → %, attempt_no % → %',
      v_id, r_resume, v_final, r_attempt, r_attempt + 1;
END
$$;
