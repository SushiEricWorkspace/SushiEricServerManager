"""固定minecraft-library/textをclasspath-only版へ局所修正する。素材取得・生成は行わない。"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

COMMIT = "63e3bfd67c11c6b11288ae00de590c6f6fe5aa40"
SOURCE_SHA256 = "03933b1594656ca3c526b3557b869fb9046b3e201c248f9535133c03629abd99"
FONT_FILE = "src/main/java/lib/minecraft/text/font/MinecraftFont.java"


def prepare(archive: Path, output: Path):
    data = archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError("固定ソースarchiveのhashが一致しません")
    if output.exists():
        raise FileExistsError("既存の生成先は上書きしません")
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            parts = PurePosixPath(entry.filename).parts
            if not parts or parts[0] != "text-" + COMMIT or ".." in parts:
                raise ValueError("固定ソースarchiveのパスが不正です")
        for entry in source.infolist():
            if entry.is_dir():
                continue
            target = output.joinpath(*PurePosixPath(entry.filename).parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))
    font = output / FONT_FILE
    original = font.read_text(encoding="utf8")
    patched = original.replace("import lib.minecraft.text.tooling.ToolingFonts;\n", "")
    start = patched.index("    /**\n     * Minecraft version used by the runtime font bootstrap")
    end = patched.index("    // --- shared surface ---", start)
    patched = patched[:start] + '''    /** Managerで同梱する描画素材の対象版。実行時生成には使用しない。 */
    @NotNull String DEFAULT_VERSION = "1.21.11";

''' + patched[end:]
    start = patched.index("        /**\n         * Resolves an {@code .otf}")
    end = patched.index("        /**\n         * Reads an {@code .otf}", start)
    patched = patched[:start] + '''        /** classpathの同梱フォントだけを使い、getPath用に既存cacheへ展開する。 */
        private static @NotNull Resolved resolveFont(@NotNull String fileName) {
            String classpathPath = "fonts/" + fileName;
            Path cachedPath = defaultCacheRoot().resolve("fonts").resolve(DEFAULT_VERSION).resolve(fileName);
            try (InputStream stream = Vanilla.class.getClassLoader().getResourceAsStream(classpathPath)) {
                if (stream == null)
                    throw new IllegalStateException("Missing bundled font: " + classpathPath);
                Files.createDirectories(cachedPath.getParent());
                Files.copy(stream, cachedPath, StandardCopyOption.REPLACE_EXISTING);
                return new Resolved(createFontFromPath(cachedPath), cachedPath);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read bundled font: " + classpathPath, ex);
            }
        }

''' + patched[end:]
    start = patched.index("        private static @NotNull String bootstrapFailureMessage(")
    end = patched.index("        /**\n         * Internal result", start)
    patched = patched[:start] + patched[end:]
    patched = patched.replace("User-home cache root for runtime font bootstrap.", "User-home cache root for materialized bundled fonts.")
    assert "ToolingFonts" not in patched
    font.write_text(patched, encoding="utf8", newline="\n")
    patch = "".join(difflib.unified_diff(original.splitlines(True), patched.splitlines(True),
                                       fromfile="a/" + FONT_FILE, tofile="b/" + FONT_FILE))
    (output / "classpath-only.patch").write_text(patch, encoding="utf8", newline="\n")
    patch_sha = hashlib.sha256(patch.encode()).hexdigest()
    (output / "MANAGER-NOTICE.txt").write_text(
        "minecraft-library/text (CraftedFury / Minecraft Library), Apache License 2.0\n"
        + "元commit: " + COMMIT + "\n"
        + "SushiEricServerManager向け変更: MinecraftFont.javaの対象版を1.21.11に固定。\n"
        + "フォント読込をclasspath-onlyにし、外部cache直接採用/実行時生成を除去。\n"
        + "getPath向けの同梱ファイル展開と既存の描画APIは維持。\n"
        + "patch SHA-256: " + patch_sha + "\n"
        + "ゲームの画像/フォントはこのcode-only artifactに含まない。\n",
        encoding="utf8", newline="\n")
    (output / "manager-artifact.init.gradle").write_text('''allprojects {
    apply plugin: 'maven-publish'
    afterEvaluate {
        group = 'io.github.sushiericworkspace.thirdparty'
        version = '63e3bfd67c11c6b11288ae00de590c6f6fe5aa40-classpath.2'
        tasks.withType(org.gradle.api.publish.maven.tasks.GenerateMavenPom).configureEach {
            doLast {
                destination.setText(destination.getText('UTF-8').replace('\\r\\n', '\\n'), 'UTF-8')
            }
        }
        tasks.named('jar') {
            from(rootDir) {
                include 'LICENSE.md', 'COPYRIGHT.md', 'MANAGER-NOTICE.txt'
                into 'META-INF/minecraft-text'
            }
        }
        publishing {
            publications {
                managerText(MavenPublication) {
                    from components.java
                    artifactId = 'minecraft-text'
                    pom {
                        name = 'Manager用classpath-only minecraft-library/text'
                        url = 'https://github.com/minecraft-library/text/tree/63e3bfd67c11c6b11288ae00de590c6f6fe5aa40'
                        licenses { license { name = 'Apache License 2.0'; url = 'https://www.apache.org/licenses/LICENSE-2.0' } }
                    }
                }
            }
            repositories { maven { name = 'managerText'; url = uri(providers.gradleProperty('managerTextRepository').get()) } }
        }
    }
}
''', encoding="utf8", newline="\n")
    record = dict(commit=COMMIT, archiveSha256=SOURCE_SHA256,
                  originalFontSha256=hashlib.sha256(original.encode()).hexdigest(),
                  patchedFontSha256=hashlib.sha256(patched.encode()).hexdigest(), patchSha256=patch_sha,
                  coordinate="io.github.sushiericworkspace.thirdparty:minecraft-text:" + COMMIT + "-classpath.2")
    (output / "manager-source.json").write_text(json.dumps(record, indent=2), encoding="utf8", newline="\n")
    print(json.dumps(record))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    prepare(args.archive, args.output)
