package com.example.investing.api;

import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

public final class InvestingDividendApiMain {

    private static final String INSTRUMENT_ID = "6408";
    private static final LocalDate START_DATE = LocalDate.of(2025, 1, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 3, 12);
    private static final Path OUTPUT_CSV = Path.of("dividends-api.csv");

    private InvestingDividendApiMain() {
    }

    public static void main(String[] args) throws IOException {
        Element table = InvestingDividendApiDownloader.download(INSTRUMENT_ID, START_DATE, END_DATE);
        InvestingDividendApiDownloader.saveTableAsCsv(table, OUTPUT_CSV);

        int calculatedLimit = InvestingDividendApiDownloader.calculateQuarterLimit(START_DATE, END_DATE);
        System.out.println("Saved CSV to " + OUTPUT_CSV.toAbsolutePath());
        System.out.println("URL: " + InvestingDividendApiDownloader.buildUrl(INSTRUMENT_ID, calculatedLimit));
    }
}
