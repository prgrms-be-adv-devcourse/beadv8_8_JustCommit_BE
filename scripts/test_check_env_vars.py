# scripts/check_env_vars.py 테스트 (#666). 표준 라이브러리 unittest만 쓴다
#
# 실행 (저장소 루트에서): python3 -m unittest discover -s scripts -p "test_*.py"
# make-env.sh 실행(bash · openssl 필요)은 CI 「운영 env 누락 검사」 단계가 실제로 돌려 확인한다
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_env_vars as c  # noqa: E402


class RequiredVarsTest(unittest.TestCase):
    def test_no_default_and_empty_default_are_required(self):
        text = "a: ${JWT_PUBLIC_KEY}\nb: ${INTERNAL_API_KEY:}\n"
        self.assertEqual(c.required_vars(text), {"JWT_PUBLIC_KEY": 1, "INTERNAL_API_KEY": 2})

    def test_real_default_is_skipped(self):
        text = "url: ${OPENSEARCH_URL:http://localhost:9200}\nre: ${P:10\\.\\d+|127\\.0\\.0\\.1}\n"
        self.assertEqual(c.required_vars(text), {})

    def test_lowercase_property_reference_is_skipped(self):
        self.assertEqual(c.required_vars("dim: ${custom.embedding.dimensions}\n"), {})

    def test_comments_are_skipped(self):
        text = "# ${OLD_KEY}\nkey: ${NEW_KEY:}  # 예전엔 ${OLD_KEY}\n"
        self.assertEqual(c.required_vars(text), {"NEW_KEY": 2})

    def test_first_line_is_kept(self):
        self.assertEqual(c.required_vars("a: ${K}\nb: ${K}\n"), {"K": 1})


class IgnoreTest(unittest.TestCase):
    def test_parse(self):
        text = "# 설명\n\nproduct S3_ACCESS_KEY   # 이유\n"
        self.assertEqual(c.parse_ignore(text), {("product", "S3_ACCESS_KEY")})

    def test_bad_line_fails(self):
        with self.assertRaises(ValueError):
            c.parse_ignore("S3_ACCESS_KEY\n")


class FindMissingTest(unittest.TestCase):
    REQUIRED = {"ai": {"ANTHROPIC_API_KEY": "ai.yml:54", "INTERNAL_API_KEY": "ai.yml:69"},
                "product": {"S3_ACCESS_KEY": "p.yml:55"}}

    def test_all_present(self):
        written = {"ai": {"ANTHROPIC_API_KEY", "INTERNAL_API_KEY"}, "product": set()}
        missing, stale = c.find_missing(self.REQUIRED, written, {("product", "S3_ACCESS_KEY")})
        self.assertEqual((missing, stale), ([], []))

    def test_missing_is_reported_with_location(self):
        written = {"ai": {"INTERNAL_API_KEY"}, "product": set()}
        missing, _ = c.find_missing(self.REQUIRED, written, {("product", "S3_ACCESS_KEY")})
        self.assertEqual(missing, [("ai", "ANTHROPIC_API_KEY", "ai.yml:54")])

    def test_service_without_env_file_misses_everything(self):
        missing, _ = c.find_missing({"newsvc": {"K": "n.yml:1"}}, {}, set())
        self.assertEqual(missing, [("newsvc", "K", "n.yml:1")])

    def test_stale_ignore(self):
        written = {"ai": {"ANTHROPIC_API_KEY", "INTERNAL_API_KEY"}, "product": {"S3_ACCESS_KEY"}}
        _, stale = c.find_missing(self.REQUIRED, written,
                                  {("product", "S3_ACCESS_KEY"), ("ai", "GONE_KEY")})
        self.assertEqual(stale, [("ai", "GONE_KEY"), ("product", "S3_ACCESS_KEY")])


class ScriptParsingTest(unittest.TestCase):
    def test_env_keys_ignores_values(self):
        self.assertEqual(c.env_keys("A=1\nB=x=y\n# C=2\n"), {"A", "B"})

    def test_script_keys(self):
        script = 'X=1\nEXTERNAL_KEYS="A B  C"\n'
        self.assertEqual(c.script_keys(script, "EXTERNAL_KEYS"), ["A", "B", "C"])
        self.assertEqual(c.script_keys(script, "OPTIONAL_KEYS"), [])


if __name__ == "__main__":
    unittest.main()
