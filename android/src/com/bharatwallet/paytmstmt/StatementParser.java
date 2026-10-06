package com.bharatwallet.paytmstmt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Local-only statement parser for files the tester exports himself from the
 * Paytm app (Balance &amp; History &rarr; Download &rarr; Excel/CSV) and hands
 * to this app through the system file picker.
 *
 * <ul>
 *   <li>No network. No Paytm contact. No login. No OTP. No UPI PIN.</li>
 *   <li>Pure {@code java.util} string/zip ops only, so the exact same bytecode
 *       runs on the JVM contract tests and on the device.</li>
 *   <li>PDF is refused honestly ({@code PDF_NOT_PARSED}): without a PDF
 *       library on this offline toolchain we cannot extract its rows, and we
 *       will not pretend otherwise. Excel/CSV parse fully.</li>
 * </ul>
 */
public final class StatementParser {

    public static final int MAX_ROWS = 400;
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    static final Pattern AMOUNT =
            Pattern.compile("(?:\u20B9|Rs\\.?|INR)?\\s?\\d[\\d,]*\\.\\d{2}");
    static final Pattern DATE =
            Pattern.compile("\\b\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}\\b");
    static final Pattern UTR = Pattern.compile("\\b\\d{12}\\b");

    public static final class Result {
        public String kind = "UNKNOWN";
        public String fileName = "";
        public long fileBytes;
        public final List<String> rows = new ArrayList<>();
        public int statementRows;
        /** UTR presence only — the value itself is never stored. */
        public boolean utrPresent;
        /** Null on success; otherwise a stable machine-readable code. */
        public String errorCode = null;
    }

    /** A row counts as a statement row only with BOTH a date and an amount. */
    public static boolean isStatementRow(String line) {
        return line != null
                && AMOUNT.matcher(line).find()
                && DATE.matcher(line).find();
    }

    public static Result parse(String fileName, byte[] data) {
        Result r = new Result();
        r.fileName = fileName == null ? "" : fileName;
        r.fileBytes = data == null ? 0 : data.length;
        if (data == null || data.length == 0) {
            r.errorCode = "FILE_EMPTY";
            return r;
        }
        if (data.length > MAX_BYTES) {
            r.errorCode = "FILE_TOO_LARGE";
            return r;
        }
        String lower = r.fileName.toLowerCase(Locale.US);
        boolean pdfMagic = data.length > 4
                && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';
        boolean zipMagic = data.length > 4
                && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        if (pdfMagic || lower.endsWith(".pdf")) {
            r.kind = "PDF";
            r.errorCode = "PDF_NOT_PARSED";
            return r;
        }
        if (zipMagic || lower.endsWith(".xlsx")) {
            r.kind = "XLSX";
            try {
                parseXlsx(data, r);
            } catch (Exception e) {
                r.errorCode = "XLSX_UNREADABLE";
                return r;
            }
            if (r.rows.isEmpty()) r.errorCode = "NO_ROWS_FOUND";
            return r;
        }
        r.kind = lower.endsWith(".csv") ? "CSV" : "TEXT";
        String text = new String(data, StandardCharsets.UTF_8);
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (r.rows.size() >= MAX_ROWS) break;
            r.rows.add(t);
        }
        if (r.rows.isEmpty()) {
            r.errorCode = "NO_ROWS_FOUND";
            return r;
        }
        finish(r);
        return r;
    }

    static void finish(Result r) {
        int n = 0;
        boolean utr = false;
        for (String row : r.rows) {
            if (isStatementRow(row)) n++;
            if (!utr && UTR.matcher(row).find()) utr = true;
        }
        r.statementRows = n;
        r.utrPresent = utr;
    }

    // --- minimal xlsx: shared strings + first worksheet, string ops only ---

    static void parseXlsx(byte[] data, Result r) throws IOException {
        Map<Integer, String> shared = new HashMap<>();
        String sheet = null;
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) != -1) bos.write(buf, 0, n);
                String name = e.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL)
                            .matcher(bos.toString("UTF-8"));
                    int idx = 0;
                    while (si.find()) shared.put(idx++, textOf(si.group(1)));
                } else if (sheet == null
                        && name.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    sheet = bos.toString("UTF-8");
                }
            }
        } finally {
            try { zin.close(); } catch (IOException ignored) { }
        }
        if (sheet == null) throw new IOException("no worksheet");
        Matcher row = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL)
                .matcher(sheet);
        while (row.find() && r.rows.size() < MAX_ROWS) {
            Matcher cell = Pattern.compile("<c(\\s[^>]*)?>(.*?)</c>", Pattern.DOTALL)
                    .matcher(row.group(1));
            List<String> cells = new ArrayList<>();
            while (cell.find()) {
                String attrs = cell.group(1) == null ? "" : cell.group(1);
                String inner = cell.group(2);
                String val = "";
                if (attrs.contains("t=\"s\"")) {
                    Matcher v = Pattern.compile("<v>(\\d+)</v>").matcher(inner);
                    if (v.find()) {
                        try {
                            String s = shared.get(Integer.parseInt(v.group(1)));
                            val = s == null ? "" : s;
                        } catch (NumberFormatException ex) {
                            val = "";
                        }
                    }
                } else if (inner.contains("<is>")) {
                    val = textOf(inner);
                } else {
                    Matcher v = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL)
                            .matcher(inner);
                    val = v.find() ? v.group(1).trim() : "";
                }
                cells.add(val);
            }
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) joined.append(" | ");
                joined.append(cells.get(i));
            }
            String line = joined.toString().trim();
            if (!line.replace("|", "").trim().isEmpty()) r.rows.add(line);
        }
        finish(r);
    }

    static String textOf(String xml) {
        Matcher t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (t.find()) sb.append(unescape(t.group(1)));
        return sb.toString();
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
