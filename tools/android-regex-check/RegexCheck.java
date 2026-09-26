import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Compiles each pattern of extract.py's output with the device's java.util.regex (ICU on Android).
 * Run on the device through app_process by check.sh. Prints one FAIL line per rejected pattern and
 * a summary; exits non-zero when any pattern fails.
 */
public final class RegexCheck {
    public static void main(String[] args) throws Exception {
        int ok = 0, failed = 0;
        try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                String where = line.substring(0, tab);
                String pattern = new String(Base64.getDecoder().decode(line.substring(tab + 1)), StandardCharsets.UTF_8);
                try {
                    Pattern.compile(pattern);
                    ok++;
                } catch (RuntimeException e) {
                    failed++;
                    System.out.println("FAIL " + where + "  " + pattern + "  -> " + e.getMessage().split("\n")[0]);
                }
            }
        }
        System.out.println("android regex check: " + ok + " compiled, " + failed + " rejected");
        System.exit(failed == 0 ? 0 : 1);
    }
}
