"""固定依存準備の発行物検証と、設定確定失敗時の保全を確認する。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare-development.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        version = "0.1.0-dev.20261016000000000.00000000-0000-0000-0000-000000000000"
        self.coordinates = [f"io.github.sushiericworkspace:{module}:{version}" for module in (
            "sushieric-datamodel-editor-dev", "sushieric-datamodel-mod-dev", "sushieric-combat-core-dev")]
        for coordinate in self.coordinates:
            group, module, value = coordinate.split(":")
            directory = self.root / group.replace(".", "/") / module / value
            directory.mkdir(parents=True)
            with zipfile.ZipFile(directory / f"{module}-{value}.jar", "w") as jar:
                jar.writestr("io/github/sushiericworkspace/combatcore/combat/random/StableDamageRandom.class", b"fixture")
            dependency = f"<dependencies><dependency><groupId>io.github.sushiericworkspace</groupId><artifactId>sushieric-datamodel-mod-dev</artifactId><version>{value}</version></dependency></dependencies>"
            (directory / f"{module}-{value}.pom").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">' + dependency + '</project>', encoding="utf-8")

    def test_fixed_six_artifacts_and_properties(self):
        values = prepare.artifacts(self.root, self.coordinates)
        self.assertEqual(6, len(values))
        text = prepare.fixed_properties(self.root, self.coordinates, ["1" * 40, "2" * 40], values)
        self.assertIn("dataModelDevelopmentCoordinate=" + self.coordinates[0], text)
        self.assertEqual(6, text.count("sha256."))
        output = self.root / "local.properties"
        prepare.commit_configuration(output, self.root / "receipt.json", {"state": "PREPARED"}, text)
        self.assertEqual(text, output.read_text(encoding="utf-8"))

    def test_wrong_core_dependency_version(self):
        values = prepare.artifacts(self.root, self.coordinates)
        pom = Path(values[-1]["path"])
        pom.write_text(pom.read_text().replace(self.coordinates[1].split(":")[-1], "0.1.0-dev.+"))
        with self.assertRaisesRegex(ValueError, "POM"):
            prepare.artifacts(self.root, self.coordinates)

    def test_empty_core_is_rejected(self):
        values = prepare.artifacts(self.root, self.coordinates)
        with zipfile.ZipFile(values[-2]["path"], "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0")
        with self.assertRaisesRegex(ValueError, "class"):
            prepare.artifacts(self.root, self.coordinates)

    def test_missing_artifact_is_rejected(self):
        values = prepare.artifacts(self.root, self.coordinates)
        Path(values[0]["path"]).unlink()
        with self.assertRaises(FileNotFoundError):
            prepare.artifacts(self.root, self.coordinates)

    def test_failed_replace_keeps_previous_tuple(self):
        output = self.root / "local.properties"
        output.write_text("previous")
        with patch.object(prepare.os, "replace", side_effect=OSError("fixture")):
            with self.assertRaises(OSError):
                prepare.commit_configuration(output, self.root / "receipt.json", {}, "new")
        self.assertEqual("previous", output.read_text())

    def test_existing_receipt_is_not_overwritten(self):
        output = self.root / "local.properties"
        output.write_text("previous")
        receipt = self.root / "receipt.json"
        receipt.write_text('{"state":"FAILED"}')
        with self.assertRaises(FileExistsError):
            prepare.commit_configuration(output, receipt, {}, "new")
        self.assertEqual("previous", output.read_text())
        self.assertEqual("FAILED", json.loads(receipt.read_text())["state"])


if __name__ == "__main__":
    unittest.main()
