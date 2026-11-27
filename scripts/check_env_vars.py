#!/usr/bin/env python3
# 운영 env 누락 검사 (#666). PR CI(ci.yml)에서 실행한다
#
# 서비스 application*.yml의 환경변수 자리 중 운영에서 값이 꼭 있어야 하는 것이
# deploy/scripts/make-env.sh가 만드는 {서비스}.env에 들어가는지 확인한다.
# 11/21 챗봇 키 누락처럼 「코드에는 새 변수, make-env.sh에는 없음」을 PR 단계에서 막는다.
#
#   python3 scripts/check_env_vars.py
#
# - 검사 대상: `${X}`(기본값 없음) · `${X:}`(빈 기본값). `${X:값}`처럼 기본값이 있는 것은 건너뛴다
# - 소문자 설정 이름(`${custom.embedding.model}`)과 주석은 건너뛴다
# - make-env.sh는 글자로 해석하지 않고 실제로 실행한다: 임시 SECRETS_DIR에 가짜 값을 채우고
#   kubectl은 아무것도 안 하는 가짜로 바꾼다. 화면 · 로그에 값은 출력하지 않는다 (가짜 값이기도 하다)
# - 운영에서 넣지 않는 변수는 deploy/scripts/env-check-ignore.txt에 「서비스 변수  # 이유」로 적는다
# 필요: bash · openssl (GitHub 러너 · Git Bash에 있음)
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAKE_ENV = ROOT / "deploy" / "scripts" / "make-env.sh"
IGNORE_FILE = ROOT / "deploy" / "scripts" / "env-check-ignore.txt"

# ${NAME} · ${NAME:} · ${NAME:기본값}. 대문자로 시작하는 이름만 환경변수로 본다
PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}")
# 줄 맨 앞이나 공백 뒤의 # 부터는 YAML 주석
COMMENT = re.compile(r"(^|\s)#.*$")


def modules(root=ROOT):
    """서비스 이름 → 모듈 폴더. gateway + services/*"""
    found = {}
    if (root / "gateway").is_dir():
        found["gateway"] = root / "gateway"
    services = root / "services"
    if services.is_dir():
        for d in sorted(services.iterdir()):
            if d.is_dir():
                found[d.name] = d
    return found


def required_vars(text):
    """YAML 글에서 운영에 값이 필요한 변수 → 첫 줄 번호"""
    found = {}
    for no, line in enumerate(text.splitlines(), 1):
        for m in PLACEHOLDER.finditer(COMMENT.sub("", line)):
            name, default = m.group(1), m.group(2)
            if default is None or default == "":
                found.setdefault(name, no)
    return found


def required_by_service(root=ROOT):
    """서비스 → {변수: '파일:줄'}. src/main/resources/application*.yml만 (테스트 설정 제외)"""
    result = {}
    for svc, path in modules(root).items():
        needed = {}
        for yml in sorted((path / "src" / "main" / "resources").glob("application*.y*ml")):
            rel = yml.relative_to(root).as_posix()
            for name, no in required_vars(yml.read_text(encoding="utf-8")).items():
                needed.setdefault(name, f"{rel}:{no}")
        result[svc] = needed
    return result


def parse_ignore(text):
    """예외 목록 → {(서비스, 변수)}. 한 줄에 「서비스 변수  # 이유」"""
    ignored = set()
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2:
            raise ValueError(f"env-check-ignore.txt {no}줄: 「서비스 변수  # 이유」 형식이 아니에요: {raw!r}")
        ignored.add((parts[0], parts[1]))
    return ignored


def env_keys(text):
    """KEY=값 형식 env 글 → 키 집합 (값은 보지 않는다)"""
    return {line.split("=", 1)[0] for line in text.splitlines() if "=" in line and not line.startswith("#")}


def find_missing(required, written, ignored):
    """빠진 것 [(서비스, 변수, 위치)] · 쓸모없어진 예외 [(서비스, 변수)]"""
    missing = []
    for svc in sorted(required):
        for name, where in sorted(required[svc].items()):
            if name not in written.get(svc, set()) and (svc, name) not in ignored:
                missing.append((svc, name, where))
    stale = sorted(
        (svc, name) for svc, name in ignored
        if name not in required.get(svc, {}) or name in written.get(svc, set())
    )
    return missing, stale


def script_keys(script, var):
    """make-env.sh의 VAR="A B C" 줄 → [A, B, C]"""
    m = re.search(rf'^{var}="([^"]*)"', script, re.M)
    return m.group(1).split() if m else []


def run_make_env(services, make_env=MAKE_ENV):
    """가짜 비밀값으로 make-env.sh를 실행하고 서비스 → env 키 집합을 돌려준다"""
    script = make_env.read_text(encoding="utf-8")
    tmp = Path(tempfile.mkdtemp(prefix="env-check-"))
    try:
        secrets, fakebin = tmp / "secrets", tmp / "bin"
        secrets.mkdir()
        fakebin.mkdir()
        (secrets / "redis.env").write_text("REDIS_PASSWORD=dummy\n")
        (secrets / "db-accounts.env").write_text(
            "".join(f"{s.upper()}_APP_PASSWORD=dummy\n" for s in services))
        ext = script_keys(script, "EXTERNAL_KEYS") + script_keys(script, "OPTIONAL_KEYS")
        # 선택 값도 채워야 opt 줄이 env에 들어가 「make-env.sh가 다루는지」를 볼 수 있다
        (secrets / "external.env").write_text("".join(f"{k}=dummy\n" for k in ext))
        kubectl = fakebin / "kubectl"
        kubectl.write_text("#!/bin/sh\ncat >/dev/null 2>&1\nexit 0\n")
        kubectl.chmod(0o755)

        env = dict(os.environ, SECRETS_DIR=str(secrets), HOME=str(tmp))
        bash = os.environ.get("BASH_BIN", "bash")
        # PATH는 bash 안에서 앞에 붙인다 (Windows 경로를 bash 형식으로 바꾸지 않아도 되게)
        proc = subprocess.run(
            [bash, "-c", 'PATH="$FAKEBIN:$PATH" exec bash "$MAKE_ENV"'],
            env=dict(env, FAKEBIN=fakebin.as_posix(), MAKE_ENV=make_env.as_posix()),
            stdin=subprocess.DEVNULL, capture_output=True, text=True, encoding="utf-8",
        )
        if proc.returncode != 0:
            raise RuntimeError(f"make-env.sh 실행 실패 (종료 코드 {proc.returncode})\n{proc.stdout}{proc.stderr}")
        return {
            s: env_keys((secrets / f"{s}.env").read_text(encoding="utf-8"))
            for s in services if (secrets / f"{s}.env").exists()
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    required = required_by_service()
    ignored = parse_ignore(IGNORE_FILE.read_text(encoding="utf-8")) if IGNORE_FILE.exists() else set()
    written = run_make_env(list(required))
    missing, stale = find_missing(required, written, ignored)

    for svc in sorted(required):
        print(f"  {svc}: 검사 {len(required[svc])}개 · make-env.sh가 쓰는 키 {len(written.get(svc, set()))}개")
    for svc, name in stale:
        print(f"⚠️ 예외 목록의 {svc} {name}는 더 이상 필요 없어요 (env-check-ignore.txt에서 지워 주세요)")
    if missing:
        print("\n❌ 운영 env에 없는 환경변수가 있어요. 운영에서 앱이 뜨지 않거나 기능이 조용히 꺼질 수 있어요.")
        for svc, name, where in missing:
            print(f"  - {svc}: {name}  ({where})")
        print("\n고치는 법: deploy/scripts/make-env.sh의 그 서비스 env에 줄을 추가하거나,")
        print("운영에서 넣지 않는 값이면 deploy/scripts/env-check-ignore.txt에 「서비스 변수  # 이유」를 적어요.")
        print("변수 설명은 docs/reference/env-vars.md에 함께 추가해요.")
        return 1
    print("✅ 운영에 필요한 환경변수가 모두 make-env.sh에 있어요")
    return 0


if __name__ == "__main__":
    sys.exit(main())
