# CHANGELOG 조각 파일(docs/changelog.d/*.md)을 검증하고 docs/CHANGELOG.md에 통합한다 (#640)
# 규칙 원본: docs/changelog.d/README.md
#
# 사용 (저장소 루트에서):
#   python3 scripts/changelog.py check              조각 파일의 이름 · 형식만 검증 (아무것도 바꾸지 않음)
#   python3 scripts/changelog.py merge              Sprint 종료 때: CHANGELOG에 통합하고 통합한 조각 파일 삭제
#   python3 scripts/changelog.py verify-pr <base>   PR이 CHANGELOG 규칙을 지켰는지 확인 (ci.yml)
#
# - 통합은 merge_text() 하나가 한다. merge는 그 결과를 파일에 쓰고, verify-pr은 같은 함수로 기대값을 만들어 비교한다
# - 기존 기록(## 기록 아래)은 정렬 · 형식을 손대지 않고, 새 항목만 그 바로 아래에 넣는다
# - 표준 라이브러리만 쓴다
import datetime
import os
import re
import shutil
import subprocess
import sys
import tempfile

CHANGELOG = "docs/CHANGELOG.md"
FRAGMENT_DIR = "docs/changelog.d"
README = "README.md"  # 작성 규칙 문서. 조각 파일이 아니다
RECORDS_HEADING = "## 기록"

# <이슈번호>.md 또는 <이슈번호>-<식별자>.md (식별자: 영문 소문자 · 숫자 · 하이픈)
NAME_PATTERN = re.compile(r"^([1-9]\d*)(-[a-z0-9]+)*\.md$")
# - 2026-11-24 · 문서 · 바뀐 내용 (#이슈번호)
ENTRY_PATTERN = re.compile(r"^- (\d{4}-\d{2}-\d{2}) · .+ · .+ \(#(\d+)\)$")
LAST_MODIFIED_PATTERN = re.compile(r"마지막 수정 (\d{4}-\d{2}-\d{2})")


# ── 통합 로직 (파일 · git을 건드리지 않는다) ──

def read_entries(fragments):
    """조각 파일들({파일명: 내용})을 검증하고 항목 목록을 만든다. 반환: (항목 목록, 오류 목록)

    항목 = (날짜, 이슈 번호, 파일명, 파일 안 순서, 줄). 오류는 하나에서 멈추지 않고 전부 모은다.
    """
    entries = []
    errors = []
    for name in sorted(fragments):
        path = f"{FRAGMENT_DIR}/{name}"
        matched = NAME_PATTERN.match(name)
        if not matched:
            errors.append(f"{path}: 파일명은 <이슈번호>.md 또는 <이슈번호>-<식별자>.md 여야 합니다 (식별자: 영문 소문자 · 숫자 · 하이픈)")
            continue
        issue = int(matched.group(1))

        count = 0
        for number, raw in enumerate(fragments[name].splitlines(), start=1):
            line = raw.rstrip()
            if not line:
                continue
            count += 1
            entry = ENTRY_PATTERN.match(line)
            if not entry:
                errors.append(f"{path}:{number}: 형식은 `- YYYY-MM-DD · 문서 · 바뀐 내용 (#{issue})` 입니다")
                continue
            try:
                datetime.date.fromisoformat(entry.group(1))
            except ValueError:
                errors.append(f"{path}:{number}: 없는 날짜입니다: {entry.group(1)}")
                continue
            if int(entry.group(2)) != issue:
                errors.append(f"{path}:{number}: 줄 끝 번호 (#{entry.group(2)})가 파일명의 이슈 번호 {issue}와 다릅니다")
                continue
            entries.append((entry.group(1), issue, name, count, line))
        if count == 0:
            errors.append(f"{path}: 항목이 없습니다 (빈 파일이나 빈 줄만 있는 파일은 둘 수 없습니다)")
    return entries, errors


def sort_entries(entries):
    """날짜 내림차순 → 같은 날짜는 이슈 번호 내림차순 → 파일명 → 파일 안 순서."""
    return sorted(entries, key=lambda e: (-datetime.date.fromisoformat(e[0]).toordinal(), -e[1], e[2], e[3]))


def merge_text(changelog_text, fragments):
    """CHANGELOG 본문에 조각 파일들의 항목을 넣은 새 본문을 만든다. 반환: (새 본문, 오류 목록)

    - 새 항목은 `## 기록` 바로 아래에 정렬해 넣는다. 기존 기록은 한 글자도 바꾸지 않는다
    - 기존 기록에 이미 있는 줄, 이번 조각들 사이의 같은 줄은 한 번만 넣는다
    - 머리말의 「마지막 수정」은 기존 날짜와 새 항목의 가장 늦은 날짜 중 더 늦은 쪽 (과거로 돌아가지 않음)
    - 넣을 항목이 없으면 본문을 그대로 돌려준다. 오류가 있으면 본문 대신 None
    """
    entries, errors = read_entries(fragments)

    lines = changelog_text.split("\n")
    heading = lines.index(RECORDS_HEADING) if RECORDS_HEADING in lines else -1
    if heading < 0:
        errors.append(f"{CHANGELOG}: `{RECORDS_HEADING}` 제목을 찾지 못했습니다")
    modified = next((i for i, line in enumerate(lines[:max(heading, 0)]) if LAST_MODIFIED_PATTERN.search(line)), -1)
    if heading >= 0 and modified < 0:
        errors.append(f"{CHANGELOG}: 머리말에서 `마지막 수정 YYYY-MM-DD`를 찾지 못했습니다")
    if errors:
        return None, errors

    existing = set(lines[heading + 1:])
    added = []
    for date, _issue, _name, _order, line in sort_entries(entries):
        if line in existing:
            continue
        existing.add(line)
        added.append((date, line))
    if not added:
        return changelog_text, []

    # 제목 다음의 빈 줄을 건너뛴 자리 = 기존 첫 기록의 바로 위
    position = heading + 1
    while position < len(lines) and lines[position] == "":
        position += 1
    new_lines = [line for _date, line in added]
    if position == len(lines):
        # 기존 기록이 하나도 없는 파일: 제목 · 빈 줄 · 새 항목 · 끝 줄바꿈
        lines = lines[:heading + 1] + [""] + new_lines + [""]
    else:
        lines[position:position] = new_lines

    current = LAST_MODIFIED_PATTERN.search(lines[modified]).group(1)
    latest = max(date for date, _line in added)
    if latest > current:
        lines[modified] = LAST_MODIFIED_PATTERN.sub(f"마지막 수정 {latest}", lines[modified], count=1)
    return "\n".join(lines), []


def records_of(text):
    """`## 기록` 줄부터 끝까지 (없으면 None). 전환 전 PR의 비교에만 쓴다."""
    lines = text.split("\n")
    if RECORDS_HEADING not in lines:
        return None
    return "\n".join(lines[lines.index(RECORDS_HEADING):])


# ── 파일 · git 읽기 ──

def read_file(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def load_fragments(root):
    """작업 트리의 조각 파일들을 {파일명: 내용}으로 읽는다. README와 숨김 파일(.DS_Store 등)은 조각이 아니다."""
    directory = os.path.join(root, FRAGMENT_DIR)
    if not os.path.isdir(directory):
        return {}
    fragments = {}
    for name in sorted(os.listdir(directory)):
        if name == README or name.startswith("."):
            continue
        path = os.path.join(directory, name)
        # 폴더는 내용 없이 올린다 → 파일명 검증에서 걸린다
        fragments[name] = "" if os.path.isdir(path) else read_file(path)
    return fragments


def git(root, *args):
    """git 명령을 실행해 출력을 돌려준다. 실패하면 원인을 담아 RuntimeError."""
    result = subprocess.run(["git", *args], cwd=root, capture_output=True)
    if result.returncode != 0:
        reason = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git {' '.join(args)} 실패: {reason}")
    return result.stdout.decode("utf-8")


def load_base(root, base):
    """기준 커밋의 CHANGELOG 본문, 조각 파일들, README 존재 여부를 읽는다.

    기준 커밋 = base와 HEAD가 갈라진 지점(git merge-base).
    - GitHub Actions: HEAD가 임시 merge commit이라 기준 커밋은 PR의 base SHA 그대로다
    - 로컬: HEAD가 내 브랜치 끝이라 기준 커밋은 브랜치를 만든 지점이다
    어느 쪽이든 PR 생성 뒤 develop에 들어온 다른 조각 파일은 "이 PR이 지운 조각"으로 잡히지 않는다.
    """
    commit = git(root, "merge-base", base, "HEAD").strip()
    changelog = git(root, "show", f"{commit}:{CHANGELOG}")
    paths = [p for p in git(root, "ls-tree", "-r", "-z", "--name-only", commit, "--", FRAGMENT_DIR + "/").split("\0") if p]
    fragments = {}
    has_readme = False
    for path in paths:
        name = path[len(FRAGMENT_DIR) + 1:]
        if name == README:
            has_readme = True
        elif not name.startswith("."):
            fragments[name] = git(root, "show", f"{commit}:{path}")
    return changelog, fragments, has_readme


# ── 명령 (종료 코드: 0 = 정상, 1 = 실패) ──

def report(errors):
    for error in errors:
        print(f"❌ {error}")
    return 1


def cmd_check(root):
    fragments = load_fragments(root)
    _entries, errors = read_entries(fragments)
    if errors:
        return report(errors)
    print(f"✅ 조각 파일 {len(fragments)}개 형식 확인")
    return 0


def cmd_merge(root):
    fragments = load_fragments(root)
    if not fragments:
        print("통합할 조각 파일이 없습니다.")
        return 0

    path = os.path.join(root, CHANGELOG)
    text = read_file(path)
    merged, errors = merge_text(text, fragments)
    if errors:
        return report(errors)

    # 기록을 먼저 쓰고 조각을 나중에 지운다: 중간에 멈춰도 기록이 빠지지 않고,
    # 다시 실행하면 이미 들어간 줄은 다시 넣지 않고 남은 조각만 지운다
    if merged != text:
        # 같은 폴더에 임시 파일을 만들어 다 쓴 뒤 한 번에 교체한다 (쓰기 · 교체가 실패하면 기존 CHANGELOG는 그대로)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", dir=os.path.dirname(path),
                                             prefix="CHANGELOG.", suffix=".tmp", delete=False) as f:
                temporary = f.name
                f.write(merged)
            shutil.copymode(path, temporary)  # 임시 파일은 소유자만 읽게 만들어지므로 기존 파일의 권한을 따른다
            os.replace(temporary, path)
        except OSError:
            if temporary and os.path.exists(temporary):
                os.remove(temporary)
            raise
    for name in sorted(fragments):
        os.remove(os.path.join(root, FRAGMENT_DIR, name))

    before = set(text.split("\n"))
    added = sum(1 for line in merged.split("\n") if line not in before and line.startswith("- "))
    print(f"✅ {CHANGELOG}에 항목 {added}개 추가, 조각 파일 {len(fragments)}개 삭제: {', '.join(sorted(fragments))}")
    return 0


def cmd_verify_pr(root, base):
    base_changelog, base_fragments, base_has_readme = load_base(root, base)
    head_changelog = read_file(os.path.join(root, CHANGELOG))
    head_fragments = load_fragments(root)

    # 1. 조각 파일 형식
    _entries, errors = read_entries(head_fragments)

    # 2. README는 항상 있어야 한다 (전환 전 · 후를 가르는 기준이라 지우지 않는다. 내용 수정은 자유)
    if not os.path.isfile(os.path.join(root, FRAGMENT_DIR, README)):
        errors.append(f"{FRAGMENT_DIR}/{README}가 없습니다. 이 파일은 지우지 않습니다")

    # 3. base에 있던 조각 파일은 그대로이거나(일반 PR) 지워져야(통합 PR) 한다
    removed = {}
    for name, text in base_fragments.items():
        if name not in head_fragments:
            removed[name] = text
        elif head_fragments[name] != text:
            errors.append(f"{FRAGMENT_DIR}/{name}: 이미 develop에 있는 조각 파일은 고치지 않습니다. "
                          f"추가 변경은 <이슈번호>-<식별자>.md로 새로 씁니다")

    # 4. CHANGELOG는 "base에 이 PR이 지운 조각을 통합한 결과"와 같아야 한다
    expected, merge_errors = merge_text(base_changelog, removed)
    if merge_errors:
        errors.extend(merge_errors)
    elif base_has_readme:
        # 전환 후: 파일 전체. 지운 조각이 없으면 expected는 base 그대로다
        if head_changelog != expected:
            if removed:
                errors.append(f"{CHANGELOG}가 조각 파일 통합 결과와 다릅니다. `python3 scripts/changelog.py merge` 결과를 "
                              f"그대로 커밋하고, 조각 파일은 통합 없이 지우지 않습니다 (이 PR이 지운 조각: {', '.join(sorted(removed))})")
            else:
                errors.append(f"{CHANGELOG}는 직접 수정하지 않습니다. 변경 기록은 {FRAGMENT_DIR}/<이슈번호>.md에 씁니다")
    elif records_of(head_changelog) != records_of(expected):
        # 전환 전(base에 README가 없는 최초 전환 PR): 안내 문구는 바꿀 수 있고 기록 영역만 본다
        errors.append(f"{CHANGELOG}의 `{RECORDS_HEADING}` 아래 기록은 수정하지 않습니다")

    if errors:
        return report(errors)
    print("✅ CHANGELOG 규칙 확인")
    return 0


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")  # 한글 · 이모지 출력 (Windows 기본 cp949에서도)
    args = argv[1:]
    root = os.getcwd()
    try:
        if args == ["check"]:
            return cmd_check(root)
        if args == ["merge"]:
            return cmd_merge(root)
        if len(args) == 2 and args[0] == "verify-pr":
            return cmd_verify_pr(root, args[1])
    except (OSError, RuntimeError, UnicodeError) as e:
        # 파일 읽기 · 쓰기 실패, git 명령 실패, UTF-8이 아닌 파일
        print(f"❌ 오류: {e}")
        return 1
    print("사용: python3 scripts/changelog.py check | merge | verify-pr <base>")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
