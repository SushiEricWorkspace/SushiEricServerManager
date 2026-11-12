"""登録済みManager taskの固定DM/Coreを明示発行し、このcheckoutの開発入力を固定する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True


def artifacts(repository, coordinates):
    """正規発行した3座標のJAR/POMとCoreの依存版・主要classを確認する。"""
    values = []
    for coordinate in coordinates:
        group, module, version = coordinate.split(":")
        for extension in ("jar", "pom"):
            path = repository / group.replace(".", "/") / module / version / f"{module}-{version}.{extension}"
            data = path.read_bytes()
            values.append({"coordinate": coordinate, "path": str(path), "extension": extension,
                           "sha256": hashlib.sha256(data).hexdigest()})
    editor, mod, core = coordinates
    if editor.rsplit(":", 1)[1] != mod.rsplit(":", 1)[1]:
        raise ValueError("Editor用とMod用DataModelの版が一致しません")
    pom = ET.parse(values[-1]["path"])
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    dependencies = [f"{x.findtext('m:groupId', namespaces=ns)}:{x.findtext('m:artifactId', namespaces=ns)}:{x.findtext('m:version', namespaces=ns)}"
                    for x in pom.findall("m:dependencies/m:dependency", ns)]
    if mod not in dependencies:
        raise ValueError("CoreのPOMが固定Mod用DataModelを参照していません")
    with zipfile.ZipFile(values[-2]["path"]) as jar:
        if "io/github/sushiericworkspace/combatcore/combat/random/StableDamageRandom.class" not in jar.namelist():
            raise ValueError("Coreの主要classがありません。固定develop依存から発行してください")
    return values


def fixed_properties(repository, coordinates, commits, publications):
    values = {"format": "1", "dataModelDevelopmentCoordinate": coordinates[0],
              "combatCoreDataModelCoordinate": coordinates[1], "combatCoreDevelopmentCoordinate": coordinates[2],
              "dataModelDevelopmentRepository": str(repository), "combatCoreDevelopmentRepository": str(repository),
              "dataModelSourceCommit": commits[0], "combatCoreSourceCommit": commits[1]}
    for item in publications:
        module = item["coordinate"].split(":")[1]
        values[f"sha256.{module}.{item['extension']}"] = item["sha256"]
    # Java PropertiesでWindowsのseparatorをescapeとして読まないよう、値はslash表記にする。
    return "".join(f"{key}={value.replace(chr(92), '/')}\n" for key, value in values.items())


def commit_configuration(output, receipt_path, receipt, properties):
    """検証完了後だけreceiptを新規保存し、直前の正常local入力をatomicに置き換える。"""
    with receipt_path.open("x", encoding="utf-8") as f:
        json.dump(receipt, f, ensure_ascii=False, indent=2)
    temporary = output.with_name(output.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as f:
        f.write(properties)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task-root", type=Path, required=True)
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repository", type=Path, required=True, help="通常共有Mavenの絶対パス")
    args = parser.parse_args()
    root = args.task_root.absolute()
    manager = Path(__file__).resolve().parent.parent
    repository = args.repository
    if not repository.is_absolute() or root.parent.name != "worktrees" or manager != root / "SushiEricServerManager":
        parser.error("登録済みtaskのManagerから実行し、repositoryは絶対パスで指定してください")
    sys.path.insert(0, str(root / ".github/scripts"))
    helper = runpy.run_path(str(root / ".github/scripts/build-dev-runtime.py"))
    from dev_server.build_inputs import canonical, capture_source, verify_sources
    from dev_server.build_lock import build_lock
    names = {"SushiEricServerManager", "SushiEricDataModel", "SushiEricCombatCore", ".github"}
    repository.mkdir(parents=True, exist_ok=True)
    # 同じ通常repositoryのmetadata更新も既存OS lockで直列化する。
    with build_lock(root / ".build.lock"), build_lock(repository / ".manager-development-publish.lock"):
        plans = helper["registered_task"](root, args.owner, names)
        for name in ("SushiEricDataModel", "SushiEricCombatCore", ".github"):
            if plans[name]["kind"] != "dependency":
                raise ValueError(f"{name}は固定detached依存として登録してください")
        source_lock = {"sourceLockVersion": 1, "sources": [capture_source(root / name, name) for name in sorted(names)]}
        build_id = uuid.uuid4().hex
        evidence = root / f"manager-development-{build_id}"
        evidence.mkdir()
        (evidence / "source-lock.json").write_bytes(canonical(source_lock))

        def run(repo, *tasks):
            wrapper = repo / ("gradlew.bat" if os.name == "nt" else "gradlew")
            command = [str(wrapper), *tasks, "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process"]
            with (evidence / f"{repo.name}-dry-run.log").open("x", encoding="utf-8") as log:
                probe = subprocess.run([*command, "--dry-run"], cwd=repo, stdout=log, stderr=subprocess.STDOUT)
            probe.check_returncode()
            lines = (evidence / f"{repo.name}-dry-run.log").read_text(encoding="utf-8")
            tasks_seen = [line.split()[0] for line in lines.splitlines() if line.startswith(":") and line.endswith(" SKIPPED")]
            if ":publishDevelopment" not in tasks_seen or ":test" not in tasks_seen or any(
                    name.rsplit(":", 1)[-1].startswith(("runServer", "runGameTest", "runClient")) or name.rsplit(":", 1)[-1] == "gameTest"
                    for name in tasks_seen):
                raise ValueError("依存発行のdry-runが空・不足、または起動taskを含みます")
            with (evidence / f"{repo.name}-build.log").open("x", encoding="utf-8") as log:
                subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)

        try:
            run(root / "SushiEricDataModel", "test", "build", "publishDevelopment", f"-PdataModelDevelopmentRepository={repository}")
            editor = helper["publication"](root / "SushiEricDataModel", "editor", "sushieric-datamodel-editor-dev")
            mod = helper["publication"](root / "SushiEricDataModel", "mod", "sushieric-datamodel-mod-dev")
            run(root / "SushiEricCombatCore", "test", "build", "publishDevelopment", f"-PdataModelDevelopmentRepository={repository}",
                f"-PdataModelDevelopmentCoordinate={mod}", f"-PcombatCoreDevelopmentRepository={repository}")
            core = helper["publication"](root / "SushiEricCombatCore", "core", "sushieric-combat-core-dev")
            coordinates = [editor, mod, core]
            publications = artifacts(repository, coordinates)
            verify_sources(source_lock)
            helper["registered_task"](root, args.owner, names)
            commits = [plans[name]["base_commit"] for name in ("SushiEricDataModel", "SushiEricCombatCore")]
            properties = fixed_properties(repository, coordinates, commits, publications)
            receipt = {"state": "PREPARED", "task": root.name, "owner": args.owner,
                       "sourceLockSha256": hashlib.sha256(canonical(source_lock)).hexdigest(),
                       "publications": publications, "configurationSha256": hashlib.sha256(properties.encode()).hexdigest()}
            commit_configuration(manager / ".manager-development.properties", evidence / "receipt.json", receipt, properties)
            print(json.dumps({"evidence": str(evidence), **receipt}, ensure_ascii=False, indent=2))
        except BaseException as error:
            (evidence / "failure.json").write_text(json.dumps({"state": "FAILED", "error": str(error)}, ensure_ascii=False, indent=2), encoding="utf-8")
            raise


if __name__ == "__main__":
    main()
