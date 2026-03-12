package com.example.investing;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class InvestingDividendApiDownloader {

    private static final String ENDPOINT_TEMPLATE =
            "https://endpoints.investing.com/dividends/v1/instruments/%s/dividends?limit=%d";
    private static final String DEFAULT_INSTRUMENT_ID = "6408";
    private static final Path OUTPUT_CSV = Path.of("dividends-api.csv");
    private static final List<String> CSV_HEADERS = List.of(
            "Ex-Dividend Date", "Dividend", "Type", "Payment Date", "Yield"
    );
    private static final DateTimeFormatter INPUT_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_DATE = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US);
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);

    private InvestingDividendApiDownloader() {
    }

    public static void main(String[] args) throws IOException {
        LocalDate startDate = LocalDate.of(2025, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 3, 12);

        Element table = download(DEFAULT_INSTRUMENT_ID, startDate, endDate);
        saveTableAsCsv(table, OUTPUT_CSV);

        System.out.println("Saved CSV to " + OUTPUT_CSV.toAbsolutePath());
        System.out.println("URL: " + buildUrl(DEFAULT_INSTRUMENT_ID, calculateQuarterLimit(startDate, endDate)));
    }

    public static Element download(String instrumentId, LocalDate startDate, LocalDate endDate) throws IOException {
        validateInputs(instrumentId, startDate, endDate);

        int calculatedLimit = calculateQuarterLimit(startDate, endDate);
        String url = buildUrl(instrumentId, calculatedLimit);
        String responseBody = Jsoup.connect(url)
                .ignoreContentType(true)
                .header("Accept", "application/json")
                .timeout(30_000)
                .execute()
                .body();

        List<DividendRecord> records = parseDividendRecords(responseBody).stream()
                .filter(record -> !record.dividendDate().isBefore(startDate) && !record.dividendDate().isAfter(endDate))
                .toList();

        return buildTable(records);
    }

    private static void validateInputs(String instrumentId, LocalDate startDate, LocalDate endDate) {
        if (instrumentId == null || instrumentId.isBlank()) {
            throw new IllegalArgumentException("instrumentId must not be blank.");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must be on or before endDate.");
        }
    }

    private static int calculateQuarterLimit(LocalDate startDate, LocalDate endDate) {
        int startQuarter = quarterOf(startDate);
        int endQuarter = quarterOf(endDate);
        return ((endDate.getYear() - startDate.getYear()) * 4) + (endQuarter - startQuarter) + 1;
    }

    private static int quarterOf(LocalDate date) {
        return ((date.getMonthValue() - 1) / 3) + 1;
    }

    private static String buildUrl(String instrumentId, int calculatedLimit) {
        return ENDPOINT_TEMPLATE.formatted(instrumentId, calculatedLimit);
    }

    private static List<DividendRecord> parseDividendRecords(String responseBody) {
        String dataArray = extractDataArray(responseBody);
        List<DividendRecord> records = new ArrayList<>();

        Matcher matcher = OBJECT_PATTERN.matcher(dataArray);
        while (matcher.find()) {
            String objectBlock = matcher.group();
            LocalDate dividendDate = parseApiDate(extractJsonString(objectBlock, "div_date"));
            LocalDate paymentDate = parseApiDate(extractJsonString(objectBlock, "pay_date"));
            String dividendAmount = extractJsonNumber(objectBlock, "div_amount");
            String paymentType = normalizePaymentType(extractJsonString(objectBlock, "div_payment_type"));
            String yield = formatYield(extractJsonNumber(objectBlock, "yield"));

            records.add(new DividendRecord(dividendDate, dividendAmount, paymentType, paymentDate, yield));
        }

        return records;
    }

    private static String extractDataArray(String responseBody) {
        int dataKeyIndex = responseBody.indexOf("\"data\"");
        if (dataKeyIndex < 0) {
            throw new IllegalStateException("Response does not contain a data array.");
        }

        int arrayStart = responseBody.indexOf('[', dataKeyIndex);
        int arrayEnd = responseBody.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < arrayStart) {
            throw new IllegalStateException("Response data array is malformed.");
        }

        return responseBody.substring(arrayStart + 1, arrayEnd);
    }

    private static String extractJsonString(String objectBlock, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(objectBlock);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing JSON string field: " + fieldName);
        }
        return matcher.group(1);
    }

    private static String extractJsonNumber(String objectBlock, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*([-0-9.]+)")
                .matcher(objectBlock);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing JSON numeric field: " + fieldName);
        }
        return matcher.group(1);
    }

    private static LocalDate parseApiDate(String rawValue) {
        return OffsetDateTime.parse(rawValue, INPUT_DATE_TIME).toLocalDate();
    }

    private static String normalizePaymentType(String paymentType) {
        return switch (paymentType.toLowerCase(Locale.ROOT)) {
            case "quarterly" -> "3M";
            case "semi-annual", "semiannual" -> "6M";
            case "annual" -> "12M";
            default -> paymentType;
        };
    }

    private static String formatYield(String rawYield) {
        return String.format(Locale.US, "%.2f%%", Double.parseDouble(rawYield));
    }

    private static Element buildTable(List<DividendRecord> records) {
        Document document = Jsoup.parse("<table><thead><tr></tr></thead><tbody></tbody></table>");
        Element table = document.selectFirst("table");
        Element headerRow = table.selectFirst("thead tr");
        Element body = table.selectFirst("tbody");

        for (String header : CSV_HEADERS) {
            headerRow.appendElement("th").text(header);
        }

        for (DividendRecord record : records) {
            Element row = body.appendElement("tr");
            row.appendElement("td").text(record.dividendDate().format(OUTPUT_DATE));
            row.appendElement("td").text(record.dividendAmount());
            row.appendElement("td").text(record.paymentType());
            row.appendElement("td").text(record.paymentDate().format(OUTPUT_DATE));
            row.appendElement("td").text(record.yield());
        }

        return table;
    }

    private static void saveTableAsCsv(Element table, Path outputPath) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        rows.add(CSV_HEADERS);

        for (Element row : table.select("tbody tr")) {
            List<String> values = row.select("td").stream()
                    .limit(CSV_HEADERS.size())
                    .map(Element::text)
                    .toList();
            if (!values.isEmpty()) {
                rows.add(values);
            }
        }

        if (rows.size() <= 1) {
            throw new IllegalStateException("No dividend rows matched the requested date range.");
        }

        List<String> csvLines = rows.stream()
                .map(row -> row.stream()
                        .map(InvestingDividendApiDownloader::escapeCsv)
                        .collect(Collectors.joining(",")))
                .toList();

        Files.write(outputPath, csvLines, StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private record DividendRecord(
            LocalDate dividendDate,
            String dividendAmount,
            String paymentType,
            LocalDate paymentDate,
            String yield
    ) {
    }
}
