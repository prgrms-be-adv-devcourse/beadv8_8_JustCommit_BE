# 실패 테스트 XML(test-failures artifact)을 AI가 읽기 좋은 짧은 Markdown으로 줄인다. ci-failure-ai.yml이 쓴다 (#534)
#
# 사용: python3 .github/scripts/failure_digest.py <XML 폴더> > failures.md
# - 실패 테스트마다 모듈 · 클래스 · 이름 · 메시지 · 스택 앞 25줄 (최대 20개)
# - 내용은 PR 코드가 만든 값이다. 여기서는 줄이기만 하고, 믿지 않는 데이터라는 것은 프롬프트에서 알린다
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_FAILURES = 20
MAX_STACK_LINES = 25
MAX_MESSAGE = 300


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    out = []
    count = 0
    for path in sorted(glob.glob(os.path.join(base, "**", "*.xml"), recursive=True)):
        rel = os.path.relpath(path, base).replace("\\", "/")
        module = rel.split("/build/test-results/")[0]
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            failed = case.find("failure")
            if failed is None:
                failed = case.find("error")
            if failed is None:
                continue
            count += 1
            if count > MAX_FAILURES:
                continue
            stack = (failed.text or "").strip().splitlines()[:MAX_STACK_LINES]
            out.append(f"### {count}. `{module}` {case.get('classname')} > {case.get('name')}")
            message = " ".join((failed.get("message") or "").split())  # 여러 줄 메시지는 한 줄로 (원문은 아래 스택에)
            out.append(f"- 메시지: {message[:MAX_MESSAGE]}")
            out.append("```")
            out.extend(line.replace("```", "'''") for line in stack)
            out.append("```")
            out.append("")

    if count == 0:
        print("(실패 테스트 XML 없음 — 컴파일 실패 등. failed-log.txt를 보세요)")
        return
    if count > MAX_FAILURES:
        out.append(f"…외 {count - MAX_FAILURES}개 생략")
    print(f"실패 테스트 {count}개\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
