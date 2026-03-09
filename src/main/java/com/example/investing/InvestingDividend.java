package com.example.investing;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class InvestingDividend implements AutoCloseable {

    private static final String TARGET_URL =
            "https://www.investing.com/equities/apple-computer-inc-dividends";
    private static final Path OUTPUT_CSV = Path.of("dividends.csv");
    private static final Duration PAGE_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHORT_WAIT_TIMEOUT = Duration.ofSeconds(2);

    private static final int MAX_SCROLL_ATTEMPTS = 10;
    private static final int SCROLL_STEP_PX = 550;
    private static final int MAX_STAGNANT_SCROLLS = 3;

    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "exdividenddate", "dividend", "type", "paymentdate", "yield"
    );

    private static final List<By> COOKIE_BUTTON_SELECTORS = List.of(
            By.id("onetrust-accept-btn-handler"),
            By.cssSelector("button[id*='accept']"),
            By.cssSelector("button[class*='accept']"),
            By.xpath("//button[contains(translate(normalize-space(.), " +
                    "'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'), 'accept')]")
    );

    private final WebDriver driver;
    private final WebDriverWait pageWait;

    public InvestingDividend(boolean headless) {
        this.driver = buildChromiumDriver(headless);
        this.pageWait = new WebDriverWait(driver, PAGE_READY_TIMEOUT);
    }

    public static void main(String[] args) throws IOException {
        Document initialDoc = Jsoup.connect(TARGET_URL)
                .userAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .get();

        try (InvestingDividend crawler = new InvestingDividend(true)) {
            Element table = crawler.scrollUntilTableFound(initialDoc);
            if (table == null) {
                throw new IllegalStateException("Dividend table was not found.");
            }
            crawler.saveTableAsCsv(table, OUTPUT_CSV);
            System.out.println("Saved CSV to " + OUTPUT_CSV.toAbsolutePath());
        }
    }

    private WebDriver buildChromiumDriver(boolean headless) {
        configureChromeDriverPath();

        ChromeOptions options = new ChromeOptions();
        options.setBinary(resolveChromiumBinary());
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--window-size=1920,1200",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--lang=en-US"
        );
        options.addArguments(
                "--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        );
        return new ChromeDriver(options);
    }

    private void configureChromeDriverPath() {
        String chromeDriverPath = System.getenv("CHROMEDRIVER_PATH");
        if (chromeDriverPath != null && !chromeDriverPath.isBlank()) {
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);
        }
    }

    private String resolveChromiumBinary() {
        String configuredBinary = firstNonBlank(
                System.getenv("CHROMIUM_BINARY"),
                System.getenv("CHROME_BINARY")
        );
        if (configuredBinary != null) {
            return configuredBinary;
        }

        for (String candidate : List.of(
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium",
                "/usr/bin/google-chrome",
                "/usr/bin/google-chrome-stable"
        )) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }

        throw new IllegalStateException(
                "No Chromium/Chrome binary found. Set CHROMIUM_BINARY or CHROME_BINARY on the CentOS host."
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // Drop-in replacement for: Element table = findDividentTable(doc);
    public Element scrollUntilTableFound(Document initialDoc) {
        Element table = findDividentTable(initialDoc);
        if (hasDataRows(table)) {
            return table;
        }

        driver.get(TARGET_URL);
        waitUntilPageReady();
        acceptCookiesIfPresent();

        long lastOffset = -1L;
        int stagnantScrolls = 0;

        for (int i = 0; i < MAX_SCROLL_ATTEMPTS; i++) {
            Document renderedDoc = Jsoup.parse(driver.getPageSource());
            table = findDividentTable(renderedDoc); // calls your old finder
            if (hasDataRows(table)) {
                return table;
            }

            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollBy({top: arguments[0], left: 0, behavior: 'smooth'});",
                    SCROLL_STEP_PX
            );

            try {
                Thread.sleep(350); // minimal fallback pause
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during scroll loop", e);
            }

            acceptCookiesIfPresent();

            long currentOffset = ((Number) ((JavascriptExecutor) driver)
                    .executeScript("return window.pageYOffset;")).longValue();

            stagnantScrolls = (currentOffset == lastOffset) ? stagnantScrolls + 1 : 0;
            lastOffset = currentOffset;

            if (stagnantScrolls >= MAX_STAGNANT_SCROLLS) {
                break;
            }
        }

        throw new IllegalStateException(
                "Dividend table was not found after scrolling. Page structure or anti-bot behavior may have changed."
        );
    }

    private void waitUntilPageReady() {
        pageWait.until(d -> "complete".equals(
                ((JavascriptExecutor) d).executeScript("return document.readyState")
        ));
    }

    private void acceptCookiesIfPresent() {
        for (By selector : COOKIE_BUTTON_SELECTORS) {
            try {
                WebElement button = new WebDriverWait(driver, SHORT_WAIT_TIMEOUT)
                        .until(ExpectedConditions.elementToBeClickable(selector));
                button.click();
                return;
            } catch (WebDriverException ignored) {
                // try next selector
            }
        }
    }

    // Keep your old name/signature for minimal refactor.
    private Element findDividentTable(Document doc) {
        if (doc == null) {
            return null;
        }

        // keep your existing selectors here
        Element table = doc.selectFirst("div.mt-6 table");
        if (table != null) return table;

        table = doc.selectFirst("table.freeze-column-w-1");
        if (table != null) return table;

        table = doc.selectFirst(
                "table:has(th:matchesOwn((?i)Ex-Dividend Date))" +
                ":has(th:matchesOwn((?i)Payment Date))" +
                ":has(th:matchesOwn((?i)Yield))"
        );
        if (table != null) return table;

        for (Element candidate : doc.select("table")) {
            if (hasRequiredHeaders(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasRequiredHeaders(Element table) {
        if (table == null) return false;
        Set<String> headers = table.select("th").stream()
                .map(Element::text)
                .map(this::normalize)
                .map(this::canonicalHeader)
                .collect(Collectors.toSet());
        return headers.containsAll(REQUIRED_HEADERS);
    }

    private boolean hasDataRows(Element table) {
        if (table == null) return false;
        return !table.select("tbody tr").isEmpty() || table.select("tr:has(td)").size() > 0;
    }

    private void saveTableAsCsv(Element table, Path outputPath) throws IOException {
        List<List<String>> rows = extractTableRows(table);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No table data found to write to CSV.");
        }

        List<String> csvLines = new ArrayList<>();
        for (List<String> row : rows) {
            csvLines.add(row.stream()
                    .map(this::escapeCsv)
                    .collect(Collectors.joining(",")));
        }

        Files.write(outputPath, csvLines, StandardCharsets.UTF_8);
    }

    private List<List<String>> extractTableRows(Element table) {
        List<List<String>> rows = new ArrayList<>();

        List<String> headers = table.select("thead tr th").stream()
                .map(Element::text)
                .map(this::normalize)
                .filter(text -> !text.isEmpty())
                .toList();
        if (!headers.isEmpty()) {
            rows.add(headers);
        }

        for (Element row : table.select("tbody tr")) {
            List<String> cells = row.select("th, td").stream()
                    .map(Element::text)
                    .map(this::normalize)
                    .filter(text -> !text.isEmpty())
                    .toList();
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty()) {
            for (Element row : table.select("tr")) {
                List<String> cells = row.select("th, td").stream()
                        .map(Element::text)
                        .map(this::normalize)
                        .filter(text -> !text.isEmpty())
                        .toList();
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
        }

        return rows;
    }

    private String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String canonicalHeader(String value) {
        return normalize(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @Override
    public void close() {
        driver.quit();
    }
}
