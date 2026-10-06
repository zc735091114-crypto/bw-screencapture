import com.bharatwallet.paytmstmt.BindingContract;
import com.bharatwallet.paytmstmt.OtpWindow;
import com.bharatwallet.paytmstmt.StatementParser;

public class ContractTest {
    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + " — " + detail);
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        for (BindingContract.Result r : BindingContract.runSuite()) {
            System.out.println((r.passed ? "PASS " : "FAIL ") + r.name + " — " + r.detail);
            if (!r.passed) failed++;
        }
        OtpWindow window = new OtpWindow();
        window.start(1000L);
        if (!window.canSubmit(1000L) || !window.canSubmit(60999L)) failed++;
        if (window.canSubmit(61000L) || !window.canResend(61000L)) failed++;
        if (window.secondsLeft(1000L) != 60 || window.secondsLeft(61000L) != 0) failed++;
        window.markResent();
        if (window.canResend(62000L)) failed++;

        // --- StatementParser: local-only file checks (same bytecode as device) --
        String csv = "date,description,utr,amount\n"
                + "05-10-2026,UPI Tester One,123456789012,250.00\n"
                + "04-10-2026,UPI Tester Two,234567890123,99.50\n"
                + "03-10-2026,Wallet cashback,,15.00\n"
                + "02-10-2026,Hello world,,\n";
        StatementParser.Result r =
                StatementParser.parse("stmt.csv", csv.getBytes("UTF-8"));
        check("CSV parses with date+amount row counting",
                r.errorCode == null && r.rows.size() == 5 && r.statementRows == 3
                        && r.utrPresent && "CSV".equals(r.kind),
                "rows=" + r.rows.size() + " stmt=" + r.statementRows);
        check("Header-only line is not a statement row",
                !StatementParser.isStatementRow("date,description,utr,amount"),
                "needs date AND amount");
        check("PDF is refused honestly, never misparsed",
                StatementParser.parse("stmt.pdf",
                        "%PDF-1.4 fake".getBytes("UTF-8")).errorCode.equals("PDF_NOT_PARSED"),
                "no PDF library on this toolchain");
        check("Empty file is a coded error, not a crash",
                StatementParser.parse("x.csv", new byte[0]).errorCode.equals("FILE_EMPTY"),
                "red-state path");
        check("XLSX shared-string sheet parses to the same rows",
                xlsxRoundTrip(), "zip+XML string ops only");

        if (failed > 0) throw new AssertionError(failed + " contract failures");
    }

    /** Build a minimal .xlsx in memory and parse it back. */
    static boolean xlsxRoundTrip() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos);
        String shared = "<?xml version=\"1.0\"?><sst xmlns=\"x\">"
                + "<si><t>05-10-2026</t></si>"
                + "<si><t>UPI Tester One</t></si>"
                + "<si><t>250.00</t></si></sst>";
        String sheet = "<?xml version=\"1.0\"?><worksheet xmlns=\"x\"><sheetData>"
                + "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c>"
                + "<c t=\"s\"><v>2</v></c></row></sheetData></worksheet>";
        zos.putNextEntry(new java.util.zip.ZipEntry("xl/sharedStrings.xml"));
        zos.write(shared.getBytes("UTF-8"));
        zos.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
        zos.write(sheet.getBytes("UTF-8"));
        zos.close();
        StatementParser.Result r =
                StatementParser.parse("stmt.xlsx", bos.toByteArray());
        return r.errorCode == null && r.rows.size() == 1
                && r.statementRows == 1 && "XLSX".equals(r.kind);
    }
}
