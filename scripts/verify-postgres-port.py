"""固定task GAVでManagerのPG先行portだけを検証する。正式配布・CoreのPG受入は行わない。"""
import argparse
import ctypes
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import time

VERSION = "0.1.0-dev.20261127210110513.86e5742c-78db-44fe-918d-5a69dfb7ca64"
GROUP = "io.github.sushiericworkspace"
HASHES = {
    "sushieric-datamodel-editor-dev": {
        "jar": "a01715ad7a2353b95b382761f87496189be87b27447645675085c90b6cd32cc7",
        "pom": "543b8997654f09b800d9d7defddabfb83575b4d52b1b53fb5dfe03451cb303a9",
        "module": "d60dfa6d36aa97db913d7bc5133b4d1afb27f92b6cbd135275117c73da53e404",
    },
    "sushieric-datamodel-host-adapter-dev": {
        "runtime.zip": "805e83aadcf88153315f822b1a0b89f3bad7766bf050db5d1dd2376cad45bbee",
        "pom": "4ef589f743a0c9796c5c147ad2d32cf24a0064fb80c8ee9f376bab70aa628aec",
    },
    "sushieric-datamodel-mod-dev": {
        "jar": "a01715ad7a2353b95b382761f87496189be87b27447645675085c90b6cd32cc7",
        "pom": "3d6b4269d842ca03006d4ccc24a2d15d9eafd0ae23f84efd6865caa413641036",
        "module": "a9e887c5b4cac42ad0bc0774155475f50357ece42f5213195aad630a4c33c953",
    },
}

def resources():
    class Memory(ctypes.Structure):
        _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
            (name, ctypes.c_ulonglong) for name in
            ("total", "available", "total_page", "available_page", "total_virtual", "available_virtual", "extended")]
    memory = Memory()
    memory.length = ctypes.sizeof(memory)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
        raise OSError("空きRAMを確認できません")
    return memory.available / 1024**3, shutil.disk_usage("C:/").free / 1024**3

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--core-repository", type=Path, required=True)
    parser.add_argument("--core-coordinate", required=True)
    parser.add_argument("--core-datamodel-coordinate", required=True)
    parser.add_argument("--work-root", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--host-java-home", type=Path)
    parser.add_argument("--tests", action="append", default=[])
    parser.add_argument("tasks", nargs="+")
    args = parser.parse_args()
    task_names = {task.rsplit(":", 1)[-1] for task in args.tasks}
    if not task_names <= {"build", "test", "compileKotlin", "compileTestKotlin",
                          "compilePostgresPortTestKotlin", "postgresPortTest",
                          "verifyPostgresPortInputs", "verifyReleaseContents"}:
        parser.error("検証taskの完全名を指定してください。起動・包装・releaseはこのrunnerの対象外です")
    pg = "postgresPortTest" in task_names
    if pg and (args.host_java_home is None or not args.host_java_home.is_absolute()):
        parser.error("PG試験には固定Host用JDKの絶対pathも指定してください")
    if os.name != "nt" or not all(p.is_absolute() for p in
            (args.repository, args.core_repository, args.work_root, args.java_home)):
        parser.error("今回の検証はWindowsと明示した絶対pathを必要とします")
    for module, extensions in HASHES.items():
        for extension, expected in extensions.items():
            suffix = "-runtime.zip" if extension == "runtime.zip" else "." + extension
            path = args.repository / GROUP.replace(".", "/") / module / VERSION / (module + "-" + VERSION + suffix)
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError("固定task artifactのhash不一致: " + path.name)
    until = time.monotonic() + 6 * 60 * 60
    while True:
        ram, disk = resources()
        print(f"resource-gate RAM={ram:.6f}GiB C={disk:.6f}GiB", flush=True)
        if ram >= (24.0 if pg else 16.0) and disk >= 100.0:
            break
        if time.monotonic() >= until:
            raise RuntimeError("resource-waitの上限に到達しました")
        time.sleep(180)
    work = args.work_root
    work.mkdir(parents=True, exist_ok=True)
    temp = work / "tmp"
    temp.mkdir(exist_ok=True)
    env = os.environ.copy()
    env.update(JAVA_HOME=str(args.java_home), TEMP=str(temp), TMP=str(temp),
               GRADLE_USER_HOME=str(work / "gradle-home"))
    # 共有依存キャッシュはGradleの読取専用機能で使い、task側に不足分だけ保存する。
    cache = Path.home() / ".gradle/caches/modules-2"
    if cache.is_dir():
        env["GRADLE_RO_DEP_CACHE"] = str(cache.parent)
    distribution = Path.home() / ".gradle/wrapper/dists/gradle-9.5.0-bin"
    target = work / "gradle-home/wrapper/dists/gradle-9.5.0-bin"
    if distribution.is_dir() and not target.exists():
        shutil.copytree(distribution, target)
    init = work / "validation.init.gradle"
    init.write_text("""
allprojects {
    tasks.withType(Test).configureEach {
        def base = new File(System.getenv('TEMP'), project.name + '-' + name)
        systemProperty 'glass.platform', 'headless'
        systemProperty 'prism.order', 'sw'
        systemProperty 'postgresPortHeadlessValidation', 'true'
        systemProperty 'java.io.tmpdir', base.absolutePath
        systemProperty 'sushieric.test.configRoot', new File(base, 'config').absolutePath
        systemProperty 'user.home', new File(base, 'home').absolutePath
        doFirst { base.mkdirs(); new File(base, 'home').mkdirs() }
    }
}
""", encoding="utf-8")
    command = [str(Path(__file__).resolve().parent.parent / "gradlew.bat"), *args.tasks,
        "--no-daemon", "--console=plain", "--max-workers=2",
        "--project-cache-dir", str(work / "project-cache"), "-I", str(init),
        "-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8 -Djava.io.tmpdir=" + str(temp).replace("\\", "/"),
        "-Pkotlin.compiler.execution.strategy=in-process", "-PpostgresPortValidation=true",
        "-PpostgresPortWorkRoot=" + str(work),
        "-PpostgresPortHostJavaHome=" + str(args.host_java_home or args.java_home),
        "-PdataModelDevelopmentCoordinate=" + GROUP + ":sushieric-datamodel-editor-dev:" + VERSION,
        "-PdataModelDevelopmentRepository=" + str(args.repository),
        "-PcombatCoreDevelopmentRepository=" + str(args.core_repository),
        "-PcombatCoreDevelopmentCoordinate=" + args.core_coordinate,
        "-PcombatCoreDataModelCoordinate=" + args.core_datamodel_coordinate,
        "-PpostgresPortEditorSha256=" + HASHES["sushieric-datamodel-editor-dev"]["jar"],
        "-PpostgresPortHostSha256=" + HASHES["sushieric-datamodel-host-adapter-dev"]["runtime.zip"]]
    for pattern in args.tests:
        command.extend(["--tests", pattern])
    import msvcrt
    with (work / ".build.lock").open("a+b") as lock:
        lock.write(b"0"); lock.flush(); lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            result = subprocess.run(command, env=env, cwd=Path(__file__).resolve().parent.parent)
        finally:
            lock.seek(0); msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
    raise SystemExit(result.returncode)

if __name__ == "__main__":
    main()
