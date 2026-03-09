package com.example.investing;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class InvestingDividend implements AutoCloseable {

    private static final String TARGET_URL =
            "https://www.investing.com/equities/apple-computer-inc-dividends";
    private static final Path DEFAULT_OUTPUT_CSV = Path.of("dividends.csv");
    private static final Duration PAGE_READY_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration SHORT_WAIT_TIMEOUT = Duration.ofSeconds(2);

    private static final int MAX_SCROLL_ATTEMPTS = 30;
    private static final int SCROLL_STEP_PX = 550;
    private static final int MAX_STAGNANT_SCROLLS = 3;

    private static final List<String> CSV_HEADERS = List.of(
            "Ex-Dividend Date", "Dividend", "Type", "Payment Date", "Yield"
    );
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

    private final ChromeDriver driver;
    private final WebDriverWait pageWait;

    public InvestingDividend(boolean headless) {
        this.driver = buildChromiumDriver(headless);
        this.pageWait = new WebDriverWait(driver, PAGE_READY_TIMEOUT);
    }

    public static void main(String[] args) throws IOException {
        boolean headless = resolveHeadless(args);
        Path outputPath = resolveOutputPath(args);

        try (InvestingDividend crawler = new InvestingDividend(headless)) {
            Document initialDoc = crawler.fetchInitialDocument();
            Element initialTable = crawler.findDividentTable(initialDoc);

            if (crawler.hasDataRows(initialTable)) {
                crawler.saveTableAsCsv(initialTable, outputPath);
                System.out.println("Saved CSV to " + outputPath.toAbsolutePath());
                return;
            }

            WebElement liveTable = crawler.scrollUntilTableFound(initialDoc);
            crawler.saveTableAsCsv(liveTable, outputPath);
            System.out.println("Saved CSV to " + outputPath.toAbsolutePath());
        }
    }

    private static boolean resolveHeadless(String[] args) {
        for (String arg : args) {
            if ("--headless".equals(arg)) {
                return true;
            }
            if ("--no-headless".equals(arg)) {
                return false;
            }
        }

        String configured = System.getenv("HEADLESS");
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }

        return System.getenv("DISPLAY") == null || System.getenv("DISPLAY").isBlank();
    }

    private static Path resolveOutputPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            }
            if (args[i].startsWith("--output=")) {
                return Path.of(args[i].substring("--output=".length()));
            }
        }
        return DEFAULT_OUTPUT_CSV;
    }

    private Document fetchInitialDocument() throws IOException {
        try {
            return Jsoup.connect(TARGET_URL)
                    .userAgent(chromeUserAgent())
                    .referrer("https://www.google.com/")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .timeout((int) PAGE_READY_TIMEOUT.toMillis())
                    .get();
        } catch (HttpStatusException e) {
            if (e.getStatusCode() == 403) {
                System.err.println("Direct Jsoup request was blocked with HTTP 403. Falling back to Selenium.");
                return null;
            }
            throw e;
        }
    }

    private ChromeDriver buildChromiumDriver(boolean headless) {
        configureChromeDriverPath();

        ChromeOptions options = new ChromeOptions();
        options.setBinary(resolveChromiumBinary());
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--window-size=1920,1200",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
                "--lang=en-US"
        );
        options.addArguments("--user-agent=" + chromeUserAgent());
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        ChromeDriver chromeDriver = new ChromeDriver(options);
        try {
            chromeDriver.executeCdpCommand(
                    "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source",
                            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});")
            );
        } catch (WebDriverException ignored) {
            // Non-fatal; keep the browser session if CDP tweaking is unavailable.
        }
        return chromeDriver;
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

    public WebElement scrollUntilTableFound(Document initialDoc) {
        driver.get(TARGET_URL);
        waitUntilPageReady();
        acceptCookiesIfPresent();

        long lastOffset = -1L;
        int stagnantScrolls = 0;

        for (int i = 0; i < MAX_SCROLL_ATTEMPTS; i++) {
            WebElement table = findDividentTable();
            if (hasDataRows(table)) {
                return table;
            }

            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollBy({top: arguments[0], left: 0, behavior: 'smooth'});",
                    SCROLL_STEP_PX
            );

            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during scroll loop", e);
            }

            try {
                new WebDriverWait(driver, SHORT_WAIT_TIMEOUT)
                        .until(d -> hasDataRows(findDividentTable()));
                table = findDividentTable();
                if (hasDataRows(table)) {
                    return table;
                }
            } catch (TimeoutException ignored) {
                // Continue the controlled scroll loop.
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
                Thread.sleep(250);
                return;
            } catch (WebDriverException ignored) {
                // Try the next selector.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private WebElement findDividentTable() {
        for (WebElement table : driver.findElements(By.tagName("table"))) {
            if (hasRequiredHeaders(table)) {
                return table;
            }
        }
        return null;
    }

    private Element findDividentTable(Document doc) {
        if (doc == null) {
            return null;
        }

        for (Element candidate : doc.select("table")) {
            if (hasRequiredHeaders(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasRequiredHeaders(WebElement table) {
        if (table == null) {
            return false;
        }

        Set<String> headers = table.findElements(By.cssSelector("th")).stream()
                .map(WebElement::getText)
                .map(this::normalize)
                .map(this::canonicalHeader)
                .collect(Collectors.toSet());
        return headers.containsAll(REQUIRED_HEADERS);
    }

    private boolean hasRequiredHeaders(Element table) {
        if (table == null) {
            return false;
        }

        Set<String> headers = table.select("th").stream()
                .map(Element::text)
                .map(this::normalize)
                .map(this::canonicalHeader)
                .collect(Collectors.toSet());
        return headers.containsAll(REQUIRED_HEADERS);
    }

    private boolean hasDataRows(WebElement table) {
        if (table == null) {
            return false;
        }

        return !table.findElements(By.cssSelector("tbody tr")).isEmpty()
                || !table.findElements(By.cssSelector("tr td")).isEmpty();
    }

    private boolean hasDataRows(Element table) {
        if (table == null) {
            return false;
        }

        return !table.select("tbody tr").isEmpty() || table.select("tr:has(td)").size() > 0;
    }

    private void saveTableAsCsv(WebElement table, Path outputPath) throws IOException {
        saveRowsAsCsv(extractTableRows(table), outputPath);
    }

    private void saveTableAsCsv(Element table, Path outputPath) throws IOException {
        saveRowsAsCsv(extractTableRows(table), outputPath);
    }

    private void saveRowsAsCsv(List<List<String>> rows, Path outputPath) throws IOException {
        if (rows.size() <= 1) {
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

    private List<List<String>> extractTableRows(WebElement table) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(CSV_HEADERS);

        List<WebElement> rowElements = table.findElements(By.cssSelector("tbody tr"));
        if (rowElements.isEmpty()) {
            rowElements = table.findElements(By.cssSelector("tr"));
        }

        for (WebElement row : rowElements) {
            List<WebElement> cells = row.findElements(By.cssSelector("td"));
            if (cells.size() < CSV_HEADERS.size()) {
                continue;
            }

            List<String> values = cells.stream()
                    .limit(CSV_HEADERS.size())
                    .map(WebElement::getText)
                    .map(this::normalize)
                    .toList();

            if (values.get(0).isEmpty() || canonicalHeader(values.get(0)).equals(canonicalHeader(CSV_HEADERS.get(0)))) {
                continue;
            }

            rows.add(values);
        }

        return rows;
    }

    private List<List<String>> extractTableRows(Element table) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(CSV_HEADERS);

        List<Element> rowElements = table.select("tbody tr");
        if (rowElements.isEmpty()) {
            rowElements = table.select("tr");
        }

        for (Element row : rowElements) {
            List<Element> cells = row.select("td");
            if (cells.size() < CSV_HEADERS.size()) {
                continue;
            }

            List<String> values = cells.stream()
                    .limit(CSV_HEADERS.size())
                    .map(Element::text)
                    .map(this::normalize)
                    .toList();

            if (values.get(0).isEmpty() || canonicalHeader(values.get(0)).equals(canonicalHeader(CSV_HEADERS.get(0)))) {
                continue;
            }

            rows.add(values);
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

    private String chromeUserAgent() {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
    }

    @Override
    public void close() {
        driver.quit();
    }
}
