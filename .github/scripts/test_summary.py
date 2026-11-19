# CI 테스트 결과(JUnit XML)를 모듈별 표 + 실패 목록 Markdown으로 만든다. ci.yml 「테스트 결과 댓글」 단계가 PR 댓글로 쓴다 (#534)
#
# 사용: python3 .github/scripts/test_summary.py <커밋 SHA> > summary.md
# - 결과 파일: **/build/test-results/test/*.xml (모듈 경로 = build/ 앞부분)
# - 테스트 메시지는 PR 코드가 만든 값이라 그대로 믿지 않는다: 코드 글꼴 안에 넣고 @멘션 · 표 깨짐을 막는다
import glob
import sys
import xml.etree.ElementTree as ET

MARKER = "<!-- ci-test-summary -->"
MAX_FAILURES = 20
MAX_MESSAGE = 150


def clean(text, limit):
    """여러 줄을 한 줄로 합치고, 코드 글꼴 · 멘션을 깨뜨리는 문자를 바꾼다."""
    line = " ".join((text or "").split())
    line = line.replace("`", "'").replace("@", "@​")
    return line[:limit] + ("…" if len(line) > limit else "")


def fmt_time(seconds):
    m, s = divmod(int(round(seconds)), 60)
    return f"{m}m {s}s" if m else f"{s}s"


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # 이모지 · 한글 출력 (Windows 기본 cp949에서도)
    sha = (sys.argv[1] if len(sys.argv) > 1 else "")[:7]
    modules = {}  # 모듈 경로 -> [통과, 실패, 건너뜀, 시간]
    failures = []  # (모듈, 클래스, 테스트, 메시지)

    for path in sorted(glob.glob("**/build/test-results/test/*.xml", recursive=True)):
        module = path.replace("\\", "/").split("/build/test-results/")[0] or "."
        stats = modules.setdefault(module, [0, 0, 0, 0.0])
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            stats[3] += float(case.get("time") or 0)
            failed = case.find("failure")
            if failed is None:
                failed = case.find("error")
            if failed is not None:
                stats[1] += 1
                cls = (case.get("classname") or "").rsplit(".", 1)[-1]
                message = failed.get("message") or ""
                if len(message.strip().rstrip(":")) < 30:  # "java.lang.AssertionError:"처럼 비면 스택 앞부분을 쓴다
                    message = failed.text or message
                failures.append((module, cls, case.get("name") or "", message))
            elif case.find("skipped") is not None:
                stats[2] += 1
            else:
                stats[0] += 1

    out = [MARKER]
    if not modules:
        out.append(f"🧪 테스트 결과 · 커밋 `{sha}` · ⚠️ 결과 파일 없음")
        out.append("")
        out.append("빌드는 돌았지만 테스트 결과가 없습니다. 컴파일 실패일 수 있으니 Actions 로그의 Build 단계를 확인하세요.")
        print("\n".join(out))
        return

    total_failed = sum(s[1] for s in modules.values())
    total = sum(s[0] + s[1] + s[2] for s in modules.values())
    status = f"❌ {total_failed}개 실패" if total_failed else "✅ 모두 통과"
    out.append(f"🧪 테스트 결과 · 커밋 `{sha}` · {status} (전체 {total}개)")
    out.append("")
    out.append("| 모듈 | 통과 | 실패 | 건너뜀 | 시간 |")
    out.append("|---|---:|---:|---:|---:|")
    for module, (passed, failed, skipped, seconds) in sorted(modules.items()):
        mark = "❌ " if failed else ""
        out.append(f"| {mark}`{module}` | {passed} | {failed} | {skipped} | {fmt_time(seconds)} |")

    if failures:
        out.append("")
        out.append("**실패한 테스트**")
        for module, cls, name, message in failures[:MAX_FAILURES]:
            out.append(f"- `{module}` {clean(cls, 80)} > {clean(name, 100)} — `{clean(message, MAX_MESSAGE)}`")
        if len(failures) > MAX_FAILURES:
            out.append(f"- …외 {len(failures) - MAX_FAILURES}개 (Checks 탭 「JUnit Test Report」에서 전체 보기)")
        out.append("")
        out.append("스택 전체는 Checks 탭이나 Actions 실행의 `test-report` artifact에서 볼 수 있습니다.")

    print("\n".join(out))


if __name__ == "__main__":
    main()
