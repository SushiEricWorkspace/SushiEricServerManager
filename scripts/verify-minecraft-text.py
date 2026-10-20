"""classpath-only artifactの欠落時・外部cache・IO失敗を専用child JVMで確認する。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def verify(jar: Path, dependencies: Path, java_home: Path, work: Path):
    if work.exists():
        raise FileExistsError("検証原本のあるディレクトリは上書きしません")
    work.mkdir(parents=True)
    with zipfile.ZipFile(jar) as archive:
        for name in ["lib/minecraft/text/font/MinecraftFont.class", "lib/minecraft/text/font/MinecraftFont$Vanilla.class"]:
            code = archive.read(name)
            if b"lib/minecraft/text/tooling/ToolingFonts" in code:
                raise ValueError("font初期化にToolingFonts参照が残っています")
        for name in ["LICENSE.md", "COPYRIGHT.md", "MANAGER-NOTICE.txt"]:
            archive.read("META-INF/minecraft-text/" + name)
        if any(name.endswith((".otf", ".ttf", ".ttc")) for name in archive.namelist()):
            raise ValueError("code-only artifactにフォントが含まれています")
    source = Path(__file__).parent / "minecraft-text/FontClasspathProbe.java"
    classes = work / "classes"
    javac = java_home / "bin/javac.exe" if os.name == "nt" else java_home / "bin/javac"
    java = java_home / "bin/java.exe" if os.name == "nt" else java_home / "bin/java"
    subprocess.run([str(javac), "-encoding", "UTF-8", "-d", str(classes), str(source)], check=True)
    urls = work / "classpath.txt"
    urls.write_text(str(jar.resolve()) + "\n" + dependencies.read_text(encoding="utf8"), encoding="utf8")
    results = []
    for mode in ["missing", "external-cache", "io-error"]:
        case = work / mode
        home, appdata, fake_bin = case / "home", case / "appdata", case / "bin"
        for path in [home, appdata, fake_bin]:
            path.mkdir(parents=True)
        cache = appdata / "minecraft-library/fonts/1.21.11" if os.name == "nt" else home / ".cache/minecraft-library/fonts/1.21.11"
        if mode == "external-cache":
            cache.mkdir(parents=True)
            for name in ["Regular", "Bold", "Italic", "BoldItalic", "Galactic", "Illageralt"]:
                (cache / ("Minecraft-" + name + ".otf")).write_bytes(b"external-cache-must-not-be-used")
        before = {str(p.relative_to(case)): hashlib.sha256(p.read_bytes()).hexdigest() for p in case.rglob("*.otf")}
        marker = case / "unexpected-generator.txt"
        for name in ["git", "python", "python3"]:
            if os.name == "nt":
                (fake_bin / (name + ".cmd")).write_text('@echo unexpected>>"' + str(marker) + '"\r\nexit /b 99\r\n')
            else:
                command = fake_bin / name
                command.write_text("#!/bin/sh\nprintf unexpected >> '" + str(marker) + "'\nexit 99\n")
                command.chmod(0o700)
        env = os.environ.copy()
        env.update(PATH=str(fake_bin), LOCALAPPDATA=str(appdata), APPDATA=str(appdata), HOME=str(home))
        command = [str(java), "-Djava.awt.headless=true", "-Duser.home=" + str(home), "-cp", str(classes),
                   "FontClasspathProbe", str(urls), "io-error" if mode == "io-error" else "missing"]
        result = subprocess.run(command, env=env, capture_output=True, text=True, encoding="utf8")
        (case / "stdout.log").write_text(result.stdout, encoding="utf8")
        (case / "stderr.log").write_text(result.stderr, encoding="utf8")
        after = {str(p.relative_to(case)): hashlib.sha256(p.read_bytes()).hexdigest() for p in case.rglob("*.otf")}
        record = dict(mode=mode, exitCode=result.returncode, generatorCalled=marker.exists(), cacheUnchanged=before == after)
        results.append(record)
        (work / "result.json").write_text(json.dumps(results, indent=2), encoding="utf8")
        if result.returncode or marker.exists() or (mode != "io-error" and before != after):
            raise RuntimeError("classpath-only検証が失敗しました: " + mode)
    print(json.dumps(results))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("dependencies", type=Path)
    parser.add_argument("java_home", type=Path)
    parser.add_argument("work", type=Path)
    args = parser.parse_args()
    verify(args.jar, args.dependencies, args.java_home, args.work)
