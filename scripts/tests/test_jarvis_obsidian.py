#!/usr/bin/env python3
"""
Tests for the host-side Obsidian bridge (scripts/jarvis-obsidian.py).

Run:  python3 scripts/tests/test_jarvis_obsidian.py
Uses an isolated temp vault — never touches the real ~/JarvisVault.
"""
import importlib.util
import os
import tempfile
import unittest

_TMP = tempfile.mkdtemp(prefix="jarvis-vault-test-")
os.environ["JARVIS_OBSIDIAN_VAULT_PATH"] = _TMP
os.environ["JARVIS_OBSIDIAN_ENABLED"] = "true"

_HERE = os.path.dirname(os.path.abspath(__file__))
_MOD_PATH = os.path.join(_HERE, "..", "jarvis-obsidian.py")
_spec = importlib.util.spec_from_file_location("jarvis_obsidian", _MOD_PATH)
obs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(obs)


class SecretFilterTests(unittest.TestCase):
    def test_redacts_openai_key(self):
        red, hit = obs.redact_secrets("my key sk-abcd1234efgh5678ijkl is here")
        self.assertTrue(hit)
        self.assertNotIn("sk-abcd1234efgh5678ijkl", red)
        self.assertIn("[REDACTED]", red)

    def test_redacts_api_key_assignment(self):
        red, hit = obs.redact_secrets("api_key=supersecretvalue123")
        self.assertTrue(hit)
        self.assertNotIn("supersecretvalue123", red)

    def test_redacts_bearer_token(self):
        red, hit = obs.redact_secrets("Authorization: Bearer abc123xyz789tok")
        self.assertTrue(hit)

    def test_clean_text_untouched(self):
        red, hit = obs.redact_secrets("just a normal note about Jarvis")
        self.assertFalse(hit)
        self.assertEqual(red, "just a normal note about Jarvis")


class PathSafetyTests(unittest.TestCase):
    def test_normal_path_inside_vault(self):
        p = obs.vault_path("03_Memory", "note.md")
        self.assertTrue(p.startswith(os.path.realpath(_TMP)))

    def test_traversal_escape_rejected(self):
        with self.assertRaises(ValueError):
            obs.vault_path("../../etc/passwd")

    def test_absolute_escape_rejected(self):
        with self.assertRaises(ValueError):
            obs.vault_path("..", "..", "..", "tmp", "evil")

    def test_slug_sanitizes(self):
        self.assertNotIn("/", obs.safe_slug("a/b\\c:d*e"))
        self.assertNotIn(" ", obs.safe_slug("hello world"))
        self.assertTrue(obs.safe_slug(""))  # never empty


class NoteCreationTests(unittest.TestCase):
    def test_make_note_writes_file_in_vault(self):
        path = obs.make_note("Test Title", "body text", folder_key="memory", note_type="memory")
        self.assertIsNotNone(path)
        self.assertTrue(os.path.exists(path))
        self.assertTrue(os.path.realpath(path).startswith(os.path.realpath(_TMP)))
        content = open(path, encoding="utf-8").read()
        self.assertIn("# Test Title", content)
        self.assertIn("#jarvis", content)        # frontmatter tag
        self.assertIn("type: memory", content)

    def test_make_note_redacts_secret_body(self):
        path = obs.make_note("Creds", "token=Bearer leakedtokenvalue123", folder_key="memory")
        content = open(path, encoding="utf-8").read()
        self.assertNotIn("leakedtokenvalue123", content)
        self.assertIn("[REDACTED]", content)

    def test_daily_append_idempotent_header(self):
        obs.append_daily("first entry")
        obs.append_daily("second entry")
        import glob
        dailies = glob.glob(os.path.join(_TMP, "01_Daily", "*.md"))
        self.assertEqual(len(dailies), 1)                       # one file per day
        content = open(dailies[0], encoding="utf-8").read()
        self.assertEqual(content.count("# Daily"), 1)           # header written once
        self.assertIn("first entry", content)
        self.assertIn("second entry", content)


if __name__ == "__main__":
    unittest.main(verbosity=2)
