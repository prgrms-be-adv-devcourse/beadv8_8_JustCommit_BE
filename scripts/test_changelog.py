# scripts/changelog.py 테스트 (#640). 표준 라이브러리 unittest만 쓴다
#
# 실행 (저장소 루트에서): python3 -m unittest discover -s scripts -p "test_*.py"
# - MergeTextTest: 통합 로직(merge_text)을 문자열로만 확인
# - MergeCommandTest: 임시 폴더에서 check · merge 명령 확인
# - VerifyPrTest: 임시 git 저장소에서 PR 규칙(verify-pr) 확인
import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import changelog  # noqa: E402

HEADER = (
    "# 문서 변경 기록\n"
    "\n"
    "> 언제 읽나: 작업을 시작하기 전, 최근에 바뀐 설계를 확인할 때\n"
    "> 상태: ✅ 확정 · 마지막 수정 2026-11-22 · 관련: 모든 docs\n"
    "\n"
    "안내 문구\n"
    "\n"
    "## 기록\n"
    "\n"
)
# 실제 CHANGELOG처럼 날짜가 섞여 있고 끝에 번호가 없는 줄도 있다 (통합이 건드리면 안 되는 부분)
OLD_RECORDS = (
    "- 2026-11-22 · reference/external-apis.md · 판매 주문 상세 (#627)\n"
    "- 2026-11-20 · reference/kafka-topics.md · 문구 삭제\n"
    "- 2026-11-21 · infra/deploy.md · 은행 서버 (#624)\n"
)
BASE = HEADER + OLD_RECORDS

E546 = "- 2026-11-24 · conventions/auth.md · 발급 조건 추가 (#546)"
E640 = "- 2026-11-24 · AGENTS.md · 조각 파일 방식 (#640)"
E611 = "- 2026-11-25 · flows/signup-and-replica.md · 재가입 안내 (#611)"
E500 = "- 2026-11-10 · services/member.md · 옛 날짜 항목 (#500)"


def run(command, *args):
    """명령 함수를 실행하고 (종료 코드, 출력)을 돌려준다."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = command(*args)
    return code, out.getvalue()


class MergeTextTest(unittest.TestCase):

    def test_조각_1개는_기록_제목_바로_아래에_들어간다(self):
        merged, errors = changelog.merge_text(BASE, {"546.md": E546 + "\n"})

        self.assertEqual(errors, [])
        self.assertEqual(merged, HEADER.replace("2026-11-22", "2026-11-24") + E546 + "\n" + OLD_RECORDS)

    def test_여러_조각은_날짜_내림차순_같은_날짜는_이슈_번호_내림차순(self):
        merged, errors = changelog.merge_text(BASE, {
            "500.md": E500 + "\n", "546.md": E546 + "\n", "611.md": E611 + "\n", "640.md": E640 + "\n"})

        self.assertEqual(errors, [])
        added = merged[len(HEADER):-len(OLD_RECORDS)].splitlines()
        self.assertEqual(added, [E611, E640, E546, E500])

    def test_한_파일의_여러_줄은_같은_날짜면_파일_안_순서를_지킨다(self):
        first = "- 2026-11-24 · a.md · 첫 줄 (#546)"
        second = "- 2026-11-24 · b.md · 둘째 줄 (#546)"

        merged, _errors = changelog.merge_text(BASE, {"546.md": first + "\n\n" + second + "\n"})

        self.assertEqual(merged[len(HEADER):-len(OLD_RECORDS)].splitlines(), [first, second])

    def test_같은_이슈의_식별자_파일은_파일명_순서(self):
        docs = "- 2026-11-24 · b.md · 문서 보완 (#546)"

        merged, errors = changelog.merge_text(BASE, {"546-docs.md": docs + "\n", "546.md": E546 + "\n"})

        self.assertEqual(errors, [])
        self.assertEqual(merged[len(HEADER):-len(OLD_RECORDS)].splitlines(), [docs, E546])

    def test_기존_기록은_순서와_글자가_그대로다(self):
        merged, _errors = changelog.merge_text(BASE, {"546.md": E546 + "\n", "500.md": E500 + "\n"})

        self.assertTrue(merged.endswith(OLD_RECORDS))
        self.assertEqual(merged.count(OLD_RECORDS), 1)

    def test_기존_기록에_이미_있는_줄은_다시_넣지_않는다(self):
        already = BASE.replace(OLD_RECORDS, E546 + "\n" + OLD_RECORDS)

        merged, errors = changelog.merge_text(already, {"546.md": E546 + "\n"})

        self.assertEqual(errors, [])
        self.assertEqual(merged, already)

    def test_조각끼리_같은_줄은_한_번만_넣는다(self):
        merged, errors = changelog.merge_text(BASE, {"546.md": E546 + "\n", "546-docs.md": E546 + "\n"})

        self.assertEqual(errors, [])
        self.assertEqual(merged.count(E546), 1)

    def test_마지막_수정은_새_항목이_더_늦을_때만_바뀐다(self):
        later, _ = changelog.merge_text(BASE, {"611.md": E611 + "\n"})
        earlier, _ = changelog.merge_text(BASE, {"500.md": E500 + "\n"})

        self.assertIn("마지막 수정 2026-11-25", later)
        self.assertIn("마지막 수정 2026-11-22", earlier)

    def test_넣을_항목이_없으면_본문_그대로(self):
        merged, errors = changelog.merge_text(BASE, {})

        self.assertEqual((merged, errors), (BASE, []))

    def test_기존_기록이_없는_파일에도_넣는다(self):
        merged, errors = changelog.merge_text(HEADER, {"546.md": E546 + "\n"})

        self.assertEqual(errors, [])
        self.assertEqual(merged, HEADER.replace("2026-11-22", "2026-11-24") + E546 + "\n")

    def test_검증_오류는_전부_모아서_돌려준다(self):
        merged, errors = changelog.merge_text(BASE, {
            "abc.md": E546 + "\n",                                          # 파일명
            "546_docs.md": E546 + "\n",                                     # 식별자에 밑줄
            "546.md": "- 2026-02-30 · a.md · 없는 날짜 (#546)\n",           # 날짜
            "547.md": "2026-11-24 · a.md · 앞에 `- ` 없음 (#547)\n",        # 형식
            "548.md": "- 2026-11-24 · 구분자 하나뿐 (#548)\n",              # 형식
            "549.md": "- 2026-11-24 · a.md · 번호 다름 (#550)\n",           # 번호 불일치
            "551.md": "# 제목\n" + "- 2026-11-24 · a.md · 정상 (#551)\n",   # 항목이 아닌 줄
            "552.md": "",                                                   # 빈 파일
            "553.md": "\n  \n",                                             # 빈 줄뿐
        })

        self.assertIsNone(merged)
        self.assertEqual(len(errors), 9)
        text = "\n".join(errors)
        for name in ["abc.md", "546_docs.md", "546.md:1", "547.md:1", "548.md:1", "549.md:1", "551.md:1", "552.md", "553.md"]:
            self.assertIn(f"docs/changelog.d/{name}", text)

    def test_기록_제목이나_마지막_수정이_없으면_오류(self):
        no_heading, errors1 = changelog.merge_text(BASE.replace("## 기록", "## 다른 제목"), {"546.md": E546 + "\n"})
        no_date, errors2 = changelog.merge_text(BASE.replace("마지막 수정 2026-11-22", "수정일 없음"), {"546.md": E546 + "\n"})

        self.assertIsNone(no_heading)
        self.assertIn("## 기록", errors1[0])
        self.assertIsNone(no_date)
        self.assertIn("마지막 수정", errors2[0])


class TempDirTest(unittest.TestCase):
    """임시 폴더를 저장소 루트처럼 쓴다."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = directory.name

    def write(self, path, text):
        full = os.path.join(self.root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="") as f:
            f.write(text)

    def read(self, path):
        with open(os.path.join(self.root, path), encoding="utf-8", newline="") as f:
            return f.read()

    def exists(self, path):
        return os.path.exists(os.path.join(self.root, path))

    def docs(self):
        """docs 폴더에 있는 것 (임시 파일이 남지 않았는지 볼 때 쓴다)."""
        return sorted(os.listdir(os.path.join(self.root, "docs")))

    def fragments(self):
        return sorted(name for name in os.listdir(os.path.join(self.root, changelog.FRAGMENT_DIR)) if name != "README.md")


class MergeCommandTest(TempDirTest):

    def setUp(self):
        super().setUp()
        self.write("docs/CHANGELOG.md", BASE)
        self.write("docs/changelog.d/README.md", "# 작성 규칙\n")

    def test_정상_통합은_기록을_넣고_조각을_지우며_README는_남긴다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")
        self.write("docs/changelog.d/611.md", E611 + "\n")

        code, out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 0)
        self.assertEqual(self.read("docs/CHANGELOG.md"),
                         HEADER.replace("2026-11-22", "2026-11-25") + E611 + "\n" + E546 + "\n" + OLD_RECORDS)
        self.assertEqual(self.fragments(), [])
        self.assertTrue(self.exists("docs/changelog.d/README.md"))
        self.assertIn("항목 2개 추가", out)

    def test_조각_파일이_없으면_아무것도_바꾸지_않고_정상_종료(self):
        code, out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 0)
        self.assertIn("통합할 조각 파일이 없습니다", out)
        self.assertEqual(self.read("docs/CHANGELOG.md"), BASE)

    def test_조각_폴더가_없어도_정상_종료(self):
        os.remove(os.path.join(self.root, "docs/changelog.d/README.md"))
        os.rmdir(os.path.join(self.root, "docs/changelog.d"))

        code, _out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 0)
        self.assertEqual(self.read("docs/CHANGELOG.md"), BASE)

    def test_검증에_실패하면_CHANGELOG와_조각_파일을_모두_그대로_둔다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")  # 정상 조각이 섞여 있어도 통합하지 않는다
        self.write("docs/changelog.d/611.md", "형식이 아닌 줄\n")
        self.write("docs/changelog.d/700.md", "\n")

        code, out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 1)
        self.assertIn("611.md:1", out)
        self.assertIn("700.md", out)
        self.assertEqual(self.read("docs/CHANGELOG.md"), BASE)
        self.assertEqual(self.fragments(), ["546.md", "611.md", "700.md"])
        self.assertEqual(self.docs(), ["CHANGELOG.md", "changelog.d"])

    def test_임시_파일을_쓰지_못하면_CHANGELOG와_조각_파일을_모두_그대로_둔다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")

        with mock.patch("tempfile.NamedTemporaryFile", side_effect=OSError("쓰기 실패")):
            with self.assertRaises(OSError):
                run(changelog.cmd_merge, self.root)

        self.assertEqual(self.read("docs/CHANGELOG.md"), BASE)
        self.assertEqual(self.fragments(), ["546.md"])
        self.assertEqual(self.docs(), ["CHANGELOG.md", "changelog.d"])

    def test_교체에_실패하면_CHANGELOG와_조각_파일을_그대로_두고_임시_파일을_남기지_않는다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")

        with mock.patch("os.replace", side_effect=OSError("교체 실패")):
            with self.assertRaises(OSError):
                run(changelog.cmd_merge, self.root)

        self.assertEqual(self.read("docs/CHANGELOG.md"), BASE)
        self.assertEqual(self.fragments(), ["546.md"])
        self.assertEqual(self.docs(), ["CHANGELOG.md", "changelog.d"])

    def test_통합해도_CHANGELOG의_파일_권한은_그대로다(self):
        path = os.path.join(self.root, "docs/CHANGELOG.md")
        os.chmod(path, 0o644)
        self.write("docs/changelog.d/546.md", E546 + "\n")

        run(changelog.cmd_merge, self.root)

        self.assertEqual(os.stat(path).st_mode & 0o777, 0o644)

    def test_두_번_실행해도_결과가_같다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")
        run(changelog.cmd_merge, self.root)
        once = self.read("docs/CHANGELOG.md")

        code, _out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 0)
        self.assertEqual(self.read("docs/CHANGELOG.md"), once)

    def test_조각_삭제_도중에_실패해도_다시_실행하면_누락도_중복도_없다(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")
        self.write("docs/changelog.d/611.md", E611 + "\n")
        real_remove = os.remove

        def fail_on_second(path):
            if path.endswith("611.md"):
                raise OSError("삭제 실패")
            real_remove(path)

        # 기록은 썼고 첫 조각만 지운 상태에서 멈춘다
        with mock.patch("os.remove", fail_on_second):
            with self.assertRaises(OSError):
                run(changelog.cmd_merge, self.root)
        self.assertEqual(self.fragments(), ["611.md"])
        after_failure = self.read("docs/CHANGELOG.md")
        self.assertIn(E546, after_failure)
        self.assertIn(E611, after_failure)

        code, _out = run(changelog.cmd_merge, self.root)

        self.assertEqual(code, 0)
        self.assertEqual(self.read("docs/CHANGELOG.md"), after_failure)  # 다시 넣지 않음
        self.assertEqual(self.fragments(), [])                           # 남은 조각만 삭제

    def test_check는_오류를_알리고_아무것도_바꾸지_않는다(self):
        self.write("docs/changelog.d/546.md", "- 2026-11-24 · a.md · 번호 다름 (#1)\n")

        code, out = run(changelog.cmd_check, self.root)

        self.assertEqual(code, 1)
        self.assertIn("546.md:1", out)
        self.assertEqual(self.fragments(), ["546.md"])

    def test_check는_숨김_파일을_조각으로_보지_않는다(self):
        self.write("docs/changelog.d/.DS_Store", "x")
        self.write("docs/changelog.d/546.md", E546 + "\n")

        code, out = run(changelog.cmd_check, self.root)

        self.assertEqual(code, 0)
        self.assertIn("조각 파일 1개", out)

    def test_파일을_읽지_못하면_원인을_출력하고_종료_코드_1(self):
        self.write("docs/changelog.d/546.md", E546 + "\n")
        os.remove(os.path.join(self.root, "docs/CHANGELOG.md"))

        code, out = self.main("merge")

        self.assertEqual(code, 1)
        self.assertIn("오류", out)
        self.assertIn("CHANGELOG.md", out)

    def test_git_명령이_실패하면_원인을_출력하고_종료_코드_1(self):
        code, out = self.main("verify-pr", "no-such-ref")  # 임시 폴더는 git 저장소가 아니다

        self.assertEqual(code, 1)
        self.assertIn("오류", out)
        self.assertIn("git merge-base", out)

    def test_알_수_없는_명령은_사용법을_출력하고_종료_코드_1(self):
        code, out = self.main("unknown")

        self.assertEqual(code, 1)
        self.assertIn("사용:", out)

    def main(self, *args):
        """저장소 루트를 임시 폴더로 두고 main을 실행한다."""
        previous = os.getcwd()
        os.chdir(self.root)
        try:
            out = io.StringIO()
            with contextlib.redirect_stdout(out), mock.patch.object(sys.stdout, "reconfigure", create=True):
                code = changelog.main(["changelog.py", *args])
            return code, out.getvalue()
        finally:
            os.chdir(previous)


class VerifyPrTest(TempDirTest):
    """develop(base)과 PR 브랜치를 실제 git 저장소로 만든다."""

    def setUp(self):
        super().setUp()
        self.git("init", "-q", "-b", "develop")
        self.write("docs/CHANGELOG.md", BASE)

    def git(self, *args):
        subprocess.run(
            ["git", "-c", "user.name=test", "-c", "user.email=test@test", "-c", "commit.gpgsign=false", *args],
            cwd=self.root, check=True, capture_output=True)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def after_transition(self, fragments=()):
        """전환이 끝난 develop: README가 있고, 아직 통합하지 않은 조각이 있을 수 있다."""
        self.write("docs/changelog.d/README.md", "# 작성 규칙\n")
        for name, text in fragments:
            self.write(f"docs/changelog.d/{name}", text)
        self.commit("develop")
        self.git("switch", "-q", "-c", "pr")

    def verify(self, base="develop"):
        return run(changelog.cmd_verify_pr, self.root, base)

    # ── 일반 PR ──

    def test_일반_PR_조각만_추가하면_통과(self):
        self.after_transition()
        self.write("docs/changelog.d/546.md", E546 + "\n")
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    def test_일반_PR이_기록을_직접_고치면_실패(self):
        self.after_transition()
        self.write("docs/CHANGELOG.md", HEADER + E546 + "\n" + OLD_RECORDS)
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("직접 수정하지 않습니다", out)

    def test_일반_PR이_안내_문구나_머리말만_고쳐도_실패(self):
        self.after_transition()
        self.write("docs/CHANGELOG.md", BASE.replace("안내 문구", "바꾼 안내 문구"))
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("직접 수정하지 않습니다", out)

    def test_일반_PR이_기존_조각_파일을_고치면_실패(self):
        self.after_transition([("546.md", E546 + "\n")])
        self.write("docs/changelog.d/546.md", E546.replace("발급 조건", "고친 내용") + "\n")
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("546.md", out)
        self.assertIn("고치지 않습니다", out)

    def test_일반_PR은_기존_조각을_두고_식별자_파일을_추가할_수_있다(self):
        self.after_transition([("546.md", E546 + "\n")])
        self.write("docs/changelog.d/546-docs.md", "- 2026-11-25 · a.md · 문서 보완 (#546)\n")
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    def test_일반_PR의_조각_형식이_틀리면_실패(self):
        self.after_transition()
        self.write("docs/changelog.d/546.md", "형식이 아닌 줄\n")
        self.commit("pr")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("546.md:1", out)

    # ── 통합 PR ──

    def test_통합_PR_스크립트_실행_결과는_통과(self):
        self.after_transition([("546.md", E546 + "\n"), ("611.md", E611 + "\n")])
        run(changelog.cmd_merge, self.root)
        self.commit("merge changelog")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    def test_통합_PR은_일부_조각만_통합해도_통과(self):
        self.after_transition([("546.md", E546 + "\n"), ("611.md", E611 + "\n")])
        merged, _errors = changelog.merge_text(BASE, {"546.md": E546 + "\n"})
        self.write("docs/CHANGELOG.md", merged)
        os.remove(os.path.join(self.root, "docs/changelog.d/546.md"))
        self.commit("merge only 546")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    def test_통합_PR에서_옛_기록을_손으로_고치면_실패(self):
        self.after_transition([("546.md", E546 + "\n")])
        run(changelog.cmd_merge, self.root)
        self.write("docs/CHANGELOG.md", self.read("docs/CHANGELOG.md").replace("문구 삭제", "문구 삭제 (#485)"))
        self.commit("merge and edit")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("통합 결과와 다릅니다", out)

    def test_조각을_통합하지_않고_지우기만_하면_실패(self):
        self.after_transition([("546.md", E546 + "\n")])
        os.remove(os.path.join(self.root, "docs/changelog.d/546.md"))
        self.commit("delete only")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("546.md", out)

    # ── README ──

    def test_README를_지우면_실패(self):
        self.after_transition()
        os.remove(os.path.join(self.root, "docs/changelog.d/README.md"))
        self.write("docs/changelog.d/546.md", E546 + "\n")
        self.commit("delete readme")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("README.md가 없습니다", out)

    def test_README_내용은_고칠_수_있다(self):
        self.after_transition()
        self.write("docs/changelog.d/README.md", "# 작성 규칙\n\n보완한 설명\n")
        self.commit("edit readme")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    # ── 최초 전환 PR (base에 README가 없다) ──

    def before_transition(self):
        self.commit("develop")
        self.git("switch", "-q", "-c", "pr")

    def test_전환_PR은_안내_문구를_고치고_README와_조각을_추가할_수_있다(self):
        self.before_transition()
        self.write("docs/CHANGELOG.md", BASE.replace("안내 문구", "조각 파일로 씁니다").replace("마지막 수정 2026-11-22", "마지막 수정 2026-11-24"))
        self.write("docs/changelog.d/README.md", "# 작성 규칙\n")
        self.write("docs/changelog.d/640.md", E640 + "\n")
        self.commit("transition")

        code, out = self.verify()

        self.assertEqual(code, 0, out)

    def test_전환_PR도_기록은_고칠_수_없다(self):
        self.before_transition()
        self.write("docs/CHANGELOG.md", BASE.replace("문구 삭제", "문구 삭제 (#485)"))
        self.write("docs/changelog.d/README.md", "# 작성 규칙\n")
        self.commit("transition")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("기록은 수정하지 않습니다", out)

    def test_전환_PR에_README가_없으면_실패(self):
        self.before_transition()
        self.write("docs/changelog.d/640.md", E640 + "\n")
        self.commit("transition without readme")

        code, out = self.verify()

        self.assertEqual(code, 1)
        self.assertIn("README.md가 없습니다", out)

    # ── PR을 만든 뒤 develop에 새 조각이 들어온 경우 ──

    def develop_gets_new_fragment(self):
        """다른 PR이 머지돼 develop에 611.md가 생긴다. 예전 develop은 태그 old-develop으로 남긴다."""
        self.git("tag", "old-develop", "develop")
        self.git("switch", "-q", "develop")
        self.write("docs/changelog.d/611.md", E611 + "\n")
        self.commit("other pr")
        self.git("switch", "-q", "pr")

    def checkout_merge_commit(self):
        """GitHub Actions처럼 develop에 PR을 합친 임시 merge commit을 체크아웃한다."""
        self.git("switch", "-q", "--detach", "develop")
        self.git("merge", "-q", "--no-ff", "-m", "merge pr", "pr")

    def test_일반_PR_뒤에_develop에_새_조각이_들어와도_통과(self):
        self.after_transition()
        self.write("docs/changelog.d/546.md", E546 + "\n")
        self.commit("pr")
        self.develop_gets_new_fragment()

        on_branch = self.verify("develop")       # 로컬: HEAD = 브랜치 끝
        self.checkout_merge_commit()
        on_merge = self.verify("develop")        # Actions: HEAD = merge commit, base = 최신 develop
        on_merge_old_base = self.verify("old-develop")  # base SHA가 예전 develop이어도

        for code, out in [on_branch, on_merge, on_merge_old_base]:
            self.assertEqual(code, 0, out)

    def test_통합_PR_뒤에_develop에_새_조각이_들어와도_통과하고_그_조각은_남는다(self):
        self.after_transition([("546.md", E546 + "\n")])
        run(changelog.cmd_merge, self.root)
        self.commit("merge changelog")
        self.develop_gets_new_fragment()

        on_branch = self.verify("develop")
        self.checkout_merge_commit()
        on_merge = self.verify("develop")
        on_merge_old_base = self.verify("old-develop")

        for code, out in [on_branch, on_merge, on_merge_old_base]:
            self.assertEqual(code, 0, out)
        self.assertEqual(self.fragments(), ["611.md"])
        self.assertNotIn(E611, self.read("docs/CHANGELOG.md"))


if __name__ == "__main__":
    unittest.main()
