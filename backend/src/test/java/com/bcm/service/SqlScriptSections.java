package com.bcm.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Historical upgrade scenarios execute the original stage from the canonical SQL file. */
final class SqlScriptSections {
    private SqlScriptSections() { }

    static String upgrade(String section) throws Exception {
        return (section.equals("court-numbers") ? "" : read("booking-ux-upgrade.sql", "court-numbers"))
                + read("booking-ux-upgrade.sql", section);
    }

    static String seed(String section) throws Exception {
        return read("branches-seed.sql", section);
    }

    static String read(String file, String section) throws Exception {
        String sql = Files.readString(Path.of("../db/" + file), StandardCharsets.UTF_8);
        String begin = "-- BEGIN SECTION: " + section + "\n";
        String end = "-- END SECTION: " + section;
        sql = sql.replace("\r\n", "\n");
        int start = sql.indexOf(begin);
        int finish = start < 0 ? -1 : sql.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || sql.indexOf(begin, start + begin.length()) >= 0) {
            throw new IllegalStateException("Missing or duplicate SQL section: " + file + " / " + section);
        }
        return sql.substring(start + begin.length(), finish);
    }
}
