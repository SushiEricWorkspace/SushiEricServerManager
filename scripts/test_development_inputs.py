"""実Gradleでsettings/root/recordとProvider供給元のtuple選択を確認する。準備済taskのみで実行する。"""
import json
import locale
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parent.parent


class DevelopmentInputsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.local = ROOT / ".manager-development.properties"
        cls.original = cls.local.read_bytes()
        cls.values = dict(line.split("=", 1) for line in cls.original.decode().splitlines())
        cls.keys = [key for key in cls.values if key not in ("format",) and not key.startswith("sha256.")]
        cls.folder = ROOT / "build/startup-input-tests"
        cls.folder.mkdir(parents=True, exist_ok=True)
        cls.init = cls.folder / "inspect.gradle"
        cls.init.write_text('''import groovy.json.JsonOutput
        gradle.settingsEvaluated { settings ->
            gradle.ext.startupRepositories = settings.dependencyResolutionManagement.repositories.findAll {
                it.name in ['dataModelDevelopment', 'combatCoreDevelopment']
            }.collectEntries { [(it.name): it.url.toString()] }
        }
        gradle.projectsEvaluated {
            allprojects { project ->
                project.tasks.register('inspectStartupInputs') {
                    doLast {
                        def root = project.rootProject
                        def inputs = root.tasks.getByName('recordCombatCoreDependency').inputs.properties
                        println('STARTUP_JSON=' + JsonOutput.toJson([
                            dataModel: root.configurations.api.dependencies.find { it.name.startsWith('sushieric-datamodel') }.toString(),
                            core: root.configurations.implementation.dependencies.find { it.name.startsWith('sushieric-combat-core') }.toString(),
                            dataModelCommit: inputs.dataModelSourceCommit,
                            coreCommit: inputs.combatCoreSourceCommit,
                            repositories: gradle.ext.startupRepositories,
                            main: project.extensions.getByName('application').mainClass.get()
                        ]))
                    }
                }
            }
        }
        ''', encoding="utf-8")

    def tearDown(self):
        self.local.write_bytes(self.original)

    def run_gradle(self, name, *args, env=None, expected=0, init=None):
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper), *args, "-I", str(init or self.init), "--console=plain"]
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (self.folder / f"{name}.log").write_bytes(result.stdout)
        text = result.stdout.decode(locale.getpreferredencoding(False), errors="replace")
        self.assertEqual(expected, result.returncode, text[-4000:])
        return text

    def selected(self, text):
        return json.loads(next(line.removeprefix("STARTUP_JSON=") for line in text.splitlines() if line.startswith("STARTUP_JSON=")))

    def explicit(self):
        return [f"-P{key}={self.values[key]}" for key in self.keys]

    def test_local_root_and_development_and_app_version(self):
        root = self.selected(self.run_gradle("local-root", ":inspectStartupInputs", "-PappVersion=0.2.2"))
        dev = self.selected(self.run_gradle("local-development", ":development:inspectStartupInputs"))
        self.assertIn(self.values["dataModelDevelopmentCoordinate"].split(":")[-1], root["dataModel"])
        self.assertEqual(self.values["dataModelSourceCommit"], root["dataModelCommit"])
        self.assertEqual(root["repositories"], dev["repositories"])
        self.assertTrue(root["main"].endswith(".Launcher")); self.assertTrue(dev["main"].endswith(".DevelopmentLauncher"))

    def test_explicit_tuple_never_reads_invalid_local(self):
        self.local.write_text('invalid=\\uZZZZ', encoding="utf-8")
        root = self.selected(self.run_gradle("explicit", ":inspectStartupInputs", *self.explicit(), "-PdataModelSourceCommit=" + "3" * 40))
        self.assertEqual("3" * 40, root["dataModelCommit"])

    def test_partial_environment_or_system_never_mix_local(self):
        self.local.write_text('invalid=\\uZZZZ', encoding="utf-8")
        environment = dict(os.environ, ORG_GRADLE_PROJECT_combatCoreSourceCommit="2" * 40)
        out = self.run_gradle("partial-env", "help", env=environment, expected=1)
        self.assertIn("scripts/prepare-development.py", out); self.assertNotIn("Malformed", out)
        out = self.run_gradle("partial-system", "help", "-Dorg.gradle.project.combatCoreSourceCommit=" + "2" * 40, expected=1)
        self.assertIn("scripts/prepare-development.py", out); self.assertNotIn("Malformed", out)

    def test_project_property_never_mixes_local(self):
        properties = ROOT / "gradle.properties"
        original = properties.read_bytes()
        try:
            self.local.write_text('invalid=\\uZZZZ', encoding="utf-8")
            with properties.open("ab") as output:
                output.write(b"\ncombatCoreSourceCommit=" + b"2" * 40 + b"\n")
            text = self.run_gradle("partial-project-property", "help", expected=1)
            self.assertIn("scripts/prepare-development.py", text)
            self.assertNotIn("Malformed", text)
        finally:
            properties.write_bytes(original)

    def test_formal_mode_and_task_never_read_local(self):
        self.local.write_text('invalid=\\uZZZZ', encoding="utf-8")
        root = self.selected(self.run_gradle("formal", ":inspectStartupInputs", "-PdataModelReleaseVersion=1.0.0",
            "-PcombatCoreReleaseVersion=1.0.0", "-PcombatCoreDataModelCoordinate=io.github.sushiericworkspace:sushieric-datamodel:1.0.0",
            "-PdataModelSourceCommit=" + "1" * 40, "-PcombatCoreSourceCommit=" + "2" * 40))
        self.assertIn("1.0.0", root["core"])
        out = self.run_gradle("formal-task", "releaseWindowsInstaller", "--dry-run", expected=1)
        self.assertIn("dataModelReleaseVersion", out); self.assertNotIn("Malformed", out)

    def test_modified_hash_and_missing_config_are_rejected(self):
        text = self.original.decode(); key = next(key for key in self.values if key.startswith("sha256."))
        self.local.write_text(text.replace(self.values[key], "0" * 64), encoding="utf-8")
        out = self.run_gradle("modified-hash", "help", expected=1); self.assertIn("scripts/prepare-development.py", out)
        self.local.unlink()
        out = self.run_gradle("missing", "help", expected=1); self.assertIn("prepare-development.py", out)

    def test_settings_snapshot_survives_atomic_config_replacement(self):
        init = self.folder / "replace.gradle"
        init.write_text(self.init.read_text() + '''
        gradle.settingsEvaluated { settings ->
            new File(settings.settingsDir, '.manager-development.properties').text = 'invalid=\\\\uZZZZ'
        }
        ''', encoding="utf-8")
        root = self.selected(self.run_gradle("snapshot", ":inspectStartupInputs", init=init))
        self.assertEqual(self.values["combatCoreSourceCommit"], root["coreCommit"])


if __name__ == "__main__":
    unittest.main()
