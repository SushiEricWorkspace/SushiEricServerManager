import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** classpath欠落/読取エラーのchild JVM専用fixture。描画成功は検証しない。 */
public class FontClasspathProbe {
    public static void main(String[] args) throws Exception {
        URL[] urls = Files.readAllLines(Path.of(args[0])).stream().map(path -> {
            try { return Path.of(path).toUri().toURL(); }
            catch (MalformedURLException ex) { throw new RuntimeException(ex); }
        }).toArray(URL[]::new);
        String mode = args[1];
        try (URLClassLoader loader = new URLClassLoader(urls, null) {
            @Override public InputStream getResourceAsStream(String name) {
                if (mode.equals("io-error") && name.startsWith("fonts/"))
                    return new InputStream() {
                        public int read() throws IOException { throw new IOException("fixture-read-error"); }
                    };
                return super.getResourceAsStream(name);
            }
        }) {
            try {
                Class.forName("lib.minecraft.text.font.MinecraftFont$Vanilla", true, loader);
                throw new AssertionError("素材なしで初期化が成功しました");
            } catch (ExceptionInInitializerError ex) {
                Throwable cause = ex.getCause();
                String expected = mode.equals("io-error") ? "Unable to read bundled font: fonts/" : "Missing bundled font: fonts/";
                if (!(cause instanceof IllegalStateException) || !cause.getMessage().startsWith(expected))
                    throw new AssertionError("想定外の初期化失敗", ex);
                if (mode.equals("io-error") && !(cause.getCause() instanceof IOException))
                    throw new AssertionError("一次IO例外が失われました", ex);
                System.out.println("EXPECTED_FAILURE " + mode + " " + cause.getMessage());
            }
        }
    }
}
