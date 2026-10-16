#!/usr/bin/env python3
"""JDK 21製Windowsインストーラーを実行せず、検査用にアプリ領域を展開する。"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


def extract(installer: Path, output: Path, seven_zip: Path, dark: Path) -> Path:
    """新規出力先にMSIとアプリを静的展開する。インストールや既存出力の削除は行わない。"""
    installer = installer.resolve(strict=True)
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    pe = output / "pe"
    payload = output / "payload"
    msi = output / "payload.msi"
    wxs = output / "payload.wxs"
    application = output / "application"
    with (output / "7zip.log").open("w", encoding="utf-8") as log:
        subprocess.run(
            [str(seven_zip), "x", "-tPE", str(installer), ".rsrc/0/RCDATA/MSI", f"-o{pe}", "-y"],
            check=True, stdout=log, stderr=subprocess.STDOUT,
        )
    shutil.copyfile(pe / ".rsrc/0/RCDATA/MSI", msi)
    with (output / "dark.log").open("w", encoding="utf-8") as log:
        subprocess.run(
            [str(dark), "-nologo", str(msi), "-x", str(payload), "-o", str(wxs)],
            check=True, stdout=log, stderr=subprocess.STDOUT,
        )

    namespace = "{http://schemas.microsoft.com/wix/2006/wi}"
    tree = ET.parse(wxs).getroot()
    install = next(node for node in tree.iter(namespace + "Directory") if node.get("Id") == "INSTALLDIR")

    def copy_files(node: ET.Element, relative: Path) -> None:
        if node.tag == namespace + "Directory" and node is not install:
            relative /= node.attrib["Name"]
        if node.tag == namespace + "File":
            source = Path(node.attrib["Source"]).resolve(strict=True)
            destination = (application / relative / node.attrib["Name"]).resolve()
            if not source.is_relative_to(payload) or not destination.is_relative_to(application):
                raise ValueError("MSIのファイル配置が検査領域の外を指しています。")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
        for child in node:
            copy_files(child, relative)

    copy_files(install, Path())
    files = [file for file in application.rglob("*") if file.is_file()]
    summary = {
        "installer": str(installer),
        "sha256": hashlib.sha256(installer.read_bytes()).hexdigest(),
        "application": str(application),
        "files": len(files),
        "jars": len([file for file in files if file.suffix == ".jar"]),
    }
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return application


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--installer", required=True, type=Path, help="JDK 21のjpackageが生成したexe")
    parser.add_argument("--output", required=True, type=Path, help="まだ存在しない検査用ディレクトリ")
    parser.add_argument("--seven-zip", required=True, type=Path, help="既存7z.exeのパス")
    parser.add_argument("--dark", required=True, type=Path, help="WiX 3のdark.exeのパス")
    args = parser.parse_args()
    print(extract(args.installer, args.output, args.seven_zip.resolve(strict=True), args.dark.resolve(strict=True)))


if __name__ == "__main__":
    main()
