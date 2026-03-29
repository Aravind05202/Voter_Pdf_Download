import io.github.bonigarcia.wdm.WebDriverManager;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.io.FileHandler;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import javax.imageio.ImageIO;

public class VoterPdfDownloader {

    // -----------------------------------------------------------------------
    // CONFIGURATION — adjust these paths to match your machine
    // -----------------------------------------------------------------------
    private static final String DOWNLOAD_DIR      = "C:\\VoterPDFs";
    private static final String CAPTCHA_IMAGE_DIR = "C:\\VoterPDFs\\captcha";
    private static final String CAPTCHA_IMAGE_PATH = CAPTCHA_IMAGE_DIR + "\\captcha.png";

    /**
     * Path to the folder that contains eng.traineddata (and other .traineddata files).
     * Copy the tessdata folder from your eclipse-workspace or Tesseract installation here.
     * Example: "C:\\Program Files\\Tesseract-OCR\\tessdata"
     */
    private static final String TESSDATA_PATH =
            "C:\\Users\\Aravind\\eclipse-workspace\\voter-pdf-downloader.zip_expanded\\tessdata";

    // -----------------------------------------------------------------------
    // WebDriver fields
    // -----------------------------------------------------------------------
    private static WebDriver     driver;
    private static WebDriverWait wait;

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void waitAndSelectByVisibleText(String name, String text) {
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(By.name(name)));
        wait.until(d -> new Select(el).getOptions().size() > 1);
        new Select(el).selectByVisibleText(text);
        sleep(800);
    }

    private static void waitAndSelectByIndex(String name, int index) {
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(By.name(name)));
        wait.until(d -> new Select(el).getOptions().size() > 1);
        new Select(el).selectByIndex(index);
        sleep(800);
    }

    private static void selectFirstAc() {
        By containerBy = By.cssSelector(".css-13cymwt-control");
        wait.until(ExpectedConditions.elementToBeClickable(containerBy));
        driver.findElement(containerBy).click();
        sleep(600);

        WebElement input = driver.findElement(By.id("react-select-2-input"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].focus();", input);
        sleep(300);

        By optionsBy = By.cssSelector("[id^='react-select-2-option']");
        wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(optionsBy));
        sleep(300);

        List<WebElement> options = driver.findElements(optionsBy);
        if (!options.isEmpty()) {
            WebElement first = options.get(0);
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", first);
            sleep(150);
            first.click();
        } else {
            input.sendKeys(Keys.ENTER);
        }
        sleep(800);
    }

    private static int selectAllCheckboxes() {
        // Get the count first, then re-fetch by index each time to avoid StaleElementReferenceException
        // (the React page re-renders after each checkbox click, invalidating old references)
        int count = 0;
        int maxAttempts = 50; // safety cap
        for (int i = 0; i < maxAttempts; i++) {
            List<WebElement> checkboxes = driver.findElements(
                    By.cssSelector("input[type='checkbox']"));
            if (checkboxes.isEmpty()) break;

            // Find the first unchecked checkbox in the freshly-fetched list
            WebElement toClick = null;
            for (WebElement cb : checkboxes) {
                try {
                    if (cb.isDisplayed() && cb.isEnabled() && !cb.isSelected()) {
                        toClick = cb;
                        break;
                    }
                } catch (StaleElementReferenceException ignored) {
                    // list is already stale — break inner loop and re-fetch
                    break;
                }
            }

            if (toClick == null) break; // all checked

            try {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'});", toClick);
                sleep(120);
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", toClick);
                count++;
            } catch (StaleElementReferenceException e) {
                // element went stale before we could click — re-fetch on next iteration
                System.out.println("  Stale on click, retrying fetch...");
            } catch (Exception e) {
                System.out.println("  Warning: could not click a checkbox: " + e.getMessage());
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // CAPTCHA helpers
    // -----------------------------------------------------------------------

    /**
     * Locate the captcha <img> on the ECI download-eroll page, take a screenshot
     * of that element, save it to disk, run Tesseract OCR on it, clean the result,
     * type it into the captcha input field, and return the cleaned string.
     *
     * Adjust the XPath / input field selector below if the ECI page structure differs.
     *
     * @param retryCount how many OCR attempts have already been made (used for logging)
     * @return the cleaned OCR text that was typed, or "" if something went wrong
     */
    /**
     * Applies known OCR correction rules for the ECI captcha font.
     * Tesseract frequently misreads certain characters on slanted green captchas.
     * Rules are based on observed confusion patterns:
     *   - Italic 'Z' often read as '2', italic 'z' as '2'
     *   - Italic 'V' often read as 'U' or 'v'
     *   - '0' (zero) often read as 'O' (letter O) and vice versa
     *   - '1' (one) often read as 'l' (lowercase L) or 'I'
     *   - 'e' sometimes read as 'c' or 'o'
     * Since the ECI captcha is CASE-SENSITIVE we preserve original case from OCR
     * and only do unambiguous fixes (digit↔letter swaps that cannot be both valid).
     */
    private static String applyOcrCorrections(String input) {
        if (input == null || input.isEmpty()) return input;
        // Character-by-character correction
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                // These are unambiguous: Tesseract maps italic '2' → could be 'Z' or '2'
                // We cannot auto-fix Z↔2 without knowing context, so leave as-is.
                // However: fix common single-character errors that are always wrong:
                case 'l': sb.append('1'); break; // lowercase L → digit 1 (l never appears alone as digit in ECI captcha)
                case 'I': sb.append('1'); break; // uppercase I → digit 1
                case 'O': sb.append('0'); break; // letter O → digit 0  (ECI uses digit 0, not letter O)
                case 'o': sb.append('0'); break; // lowercase o → digit 0
                case 'Q': sb.append('0'); break; // Q misread from 0
                default:  sb.append(c);  break;
            }
        }
        return sb.toString();
    }

    /**
     * Preprocesses the raw captcha screenshot:
     *  1. Scale up 4x (more pixels = better OCR on small captchas)
     *  2. Isolate dark text: suppress green/teal background AND the strikethrough line
     *     by keeping only pixels that are DARK (low brightness) in the original
     *  3. Binarize to pure black-on-white
     *  4. Add padding
     *
     * Key insight for this ECI captcha: the TEXT pixels are dark (low RGB values)
     * while the background + strikethrough line are bright green/teal. So we simply
     * threshold on luminance — dark stays, bright goes white.
     */
    private static File preprocessCaptcha(File rawFile) throws Exception {
        BufferedImage src = ImageIO.read(rawFile);

        // Step 1: Scale up 4x with bicubic interpolation
        int W = src.getWidth()  * 4;
        int H = src.getHeight() * 4;
        BufferedImage scaled = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                           RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                           RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.drawImage(src, 0, 0, W, H, null);
        g.dispose();

        // Step 2 & 3: For each pixel — if luminance < threshold keep as black, else white.
        // This removes the green background AND the green strikethrough line in one pass,
        // because both are bright/green while the text characters are dark.
        // Threshold tuned for the ECI captcha (dark italic text on light green background).
        int LUMA_THRESHOLD = 160; // pixels darker than this become black text
        BufferedImage binary = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb   = scaled.getRGB(x, y);
                int r     = (rgb >> 16) & 0xFF;
                int green = (rgb >>  8) & 0xFF;
                int b     =  rgb        & 0xFF;
                // Standard luminance formula
                int luma  = (int)(0.299 * r + 0.587 * green + 0.114 * b);
                // Additionally: purely green-dominant pixels (strikethrough line) → white
                boolean greenLine = (green > r + 15) && (green > b + 15);
                int pixel = (luma < LUMA_THRESHOLD && !greenLine) ? 0x000000 : 0xFFFFFF;
                binary.setRGB(x, y, pixel);
            }
        }

        // Step 4: Dilate slightly — connect broken character strokes after binarization
        // Simple 1-pixel erosion of white (= dilation of black text)
        BufferedImage dilated = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // If any neighbour is black, this pixel becomes black
                boolean hasBlackNeighbour = false;
                for (int dy = -1; dy <= 1 && !hasBlackNeighbour; dy++) {
                    for (int dx = -1; dx <= 1 && !hasBlackNeighbour; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < W && ny >= 0 && ny < H) {
                            if ((binary.getRGB(nx, ny) & 0xFF) < 128) {
                                hasBlackNeighbour = true;
                            }
                        }
                    }
                }
                dilated.setRGB(x, y, hasBlackNeighbour ? 0x000000 : 0xFFFFFF);
            }
        }

        // Step 5: Add 15px white border padding
        int pad = 15;
        BufferedImage padded = new BufferedImage(W + pad * 2, H + pad * 2,
                                                 BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = padded.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        g2.drawImage(dilated, pad, pad, null);
        g2.dispose();

        File out = new File(CAPTCHA_IMAGE_DIR + "\\captcha_processed.png");
        ImageIO.write(padded, "png", out);
        System.out.println("  Preprocessed captcha saved: " + out.getAbsolutePath());
        return out;
    }

    /**
     * Types text into the captcha contenteditable <div> entirely via JavaScript.
     * Strategy:
     *   1. Set innerText via JS (clears + sets value atomically)
     *   2. Dispatch an 'input' event so the React/Angular model updates
     * Falls back to scrolling the element into view and using Actions if JS alone
     * does not register (some frameworks listen only to real keyboard events).
     */
    private static void typeIntoCaptchaField(String text) {
        By captchaBy = By.xpath(
            "/html/body/div/div/div[1]/div[1]/div[2]/div[2]/div[2]/div[2]/div/div/div[2]/div");
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(captchaBy));

        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Scroll into view and set value via JS
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
        sleep(200);

        // Set the text and fire input + change events so the page framework picks it up
        js.executeScript(
            "var el = arguments[0];" +
            "el.focus();" +
            "el.innerText = arguments[1];" +
            "el.dispatchEvent(new Event('input',  {bubbles:true}));" +
            "el.dispatchEvent(new Event('change', {bubbles:true}));",
            el, text);
        sleep(200);

        // Verify the value was set
        String actual = (String) js.executeScript("return arguments[0].innerText;", el);
        System.out.println("  Captcha field value after JS set: [" + (actual == null ? "" : actual.trim()) + "]");

        // If JS alone didn't work, try Actions (simulates real keyboard)
        if (actual == null || actual.trim().isEmpty()) {
            System.out.println("  JS set failed — trying Actions keyboard input...");
            try {
                new org.openqa.selenium.interactions.Actions(driver)
                    .click(el)
                    .keyDown(org.openqa.selenium.Keys.CONTROL).sendKeys("a").keyUp(org.openqa.selenium.Keys.CONTROL)
                    .sendKeys(text)
                    .perform();
                sleep(200);
            } catch (Exception ae) {
                System.out.println("  Actions input also failed: " + ae.getMessage());
            }
        }
    }

    private static String solveCaptchaWithOcr(int retryCount) {
        try {
            // 1. Locate the captcha image using the exact XPath confirmed via DevTools.
            WebElement captchaImg;
            try {
                captchaImg = wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.xpath("//*[@id='textContent']/div[1]/div[1]/div[2]/div[2]/div[2]/div[2]/div/div/div[2]/img[1]")));
                System.out.println("  Captcha image found via exact XPath.");
            } catch (Exception xe) {
                System.out.println("  Exact XPath failed, trying attribute fallback: " + xe.getMessage());
                captchaImg = wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.xpath("//img[contains(@src,'captcha') or contains(@id,'captcha')" +
                                 " or ancestor::*[contains(@class,'captcha') or contains(@id,'captcha')]]")));
            }

            // 2. Screenshot the captcha element
            new File(CAPTCHA_IMAGE_DIR).mkdirs();
            File rawFile = new File(CAPTCHA_IMAGE_PATH);
            FileHandler.copy(captchaImg.getScreenshotAs(OutputType.FILE), rawFile);
            sleep(300);

            // 3. Preprocess: scale up, remove green line, binarize, pad
            File processedFile = preprocessCaptcha(rawFile);

            // 4. Run Tesseract OCR — try PSM 7, 8, 13; pick longest clean result.
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(TESSDATA_PATH);

            String bestResult = "";
            int[] psmModes = {7, 8, 13};
            for (int psm : psmModes) {
                try {
                    tesseract.setPageSegMode(psm);
                    String raw = tesseract.doOCR(processedFile);
                    String cleaned = raw.replaceAll("[^a-zA-Z0-9]", "").trim();
                    System.out.println("  [OCR PSM" + psm + "] raw=\"" + raw.trim()
                                       + "\" cleaned=\"" + cleaned + "\"");
                    if (cleaned.length() > bestResult.length()) {
                        bestResult = cleaned;
                    }
                } catch (Exception psmEx) {
                    System.out.println("  PSM" + psm + " failed: " + psmEx.getMessage());
                }
            }

            // Also try on the RAW (unprocessed) image — sometimes preprocessing hurts more than helps
            try {
                tesseract.setPageSegMode(7);
                String rawOcr = tesseract.doOCR(rawFile);
                String rawCleaned = rawOcr.replaceAll("[^a-zA-Z0-9]", "").trim();
                System.out.println("  [OCR RAW-PSM7] raw=\"" + rawOcr.trim()
                                   + "\" cleaned=\"" + rawCleaned + "\"");
                if (rawCleaned.length() > bestResult.length()) {
                    bestResult = rawCleaned;
                }
            } catch (Exception ignored) {}

            // Post-OCR character correction map for this ECI captcha font.
            // Tesseract commonly confuses these pairs on slanted green captchas:
            String corrected = applyOcrCorrections(bestResult);
            System.out.println("  [OCR attempt " + retryCount + "] best=\"" + bestResult
                               + "\" corrected=\"" + corrected + "\"");
            String captchaText = corrected;

            if (captchaText.isEmpty()) {
                System.out.println("  OCR returned empty — falling back to manual entry.");
                return "";
            }

            // 5. Type into captcha input field fully via JavaScript.
            // The element is a contenteditable <div>; sendKeys fails with
            // "element not interactable" when it is off-screen or not focusable.
            // JS dispatch gives us reliable input regardless of visibility.
            typeIntoCaptchaField(captchaText);
            sleep(400);

            return captchaText;

        } catch (Exception e) {
            System.out.println("  OCR captcha error: " + e.getMessage());
            return "";
        }
    }

    /**
     * Try OCR first; if OCR returns an empty string, ask the user to type it manually.
     * After typing, click the Download button and check whether an error message
     * appears (wrong captcha). If wrong, refresh the captcha and retry up to maxRetries times.
     *
     * @param pageNumber     current pagination page (for logging)
     * @param sc             Scanner for fallback manual input
     */
    private static void handleCaptchaAndDownload(int pageNumber, Scanner sc) {
        final int MAX_OCR_RETRIES = 3;

        for (int attempt = 1; attempt <= MAX_OCR_RETRIES; attempt++) {
            System.out.println("\n  --- Captcha attempt " + attempt + " / " + MAX_OCR_RETRIES
                               + " (page " + pageNumber + ") ---");

            // Try automatic OCR
            String solved = solveCaptchaWithOcr(attempt);

            // If OCR failed, ask user
            if (solved.isEmpty()) {
                System.out.print("  OCR could not read captcha. Please type it manually and press ENTER: ");
                String manual = sc.nextLine().trim();
                try {
                    typeIntoCaptchaField(manual);
                    sleep(300);
                } catch (Exception e) {
                    System.out.println("  Could not type captcha manually: " + e.getMessage());
                }
            }

            // Click Download Selected PDFs
            clickDownloadSelected();
            sleep(2000);

            // Check for a captcha-error message on the page
            if (isCaptchaError()) {
                System.out.println("  Captcha was wrong. Refreshing captcha and retrying...");
                refreshCaptcha();
                sleep(1000);
                // Re-select all checkboxes (they may have been unchecked after the failed attempt)
                selectAllCheckboxes();
            } else {
                System.out.println("  Captcha accepted (or no error detected). Download triggered.");
                return; // success
            }
        }

        // All retries exhausted — ask user one last time
        System.out.print("\n  All OCR retries failed. Please solve the captcha manually in the browser,"
                         + " then press ENTER to attempt download: ");
        sc.nextLine();
        clickDownloadSelected();
        sleep(1500);
    }

    /**
     * Returns true if the page currently shows a captcha-wrong/error indicator.
     * Adjust the XPath to match the actual error element on the ECI site.
     */
    private static boolean isCaptchaError() {
        try {
            List<WebElement> errors = driver.findElements(
                    By.xpath("//*[contains(text(),'Invalid Captcha') or contains(text(),'Wrong Captcha')" +
                             " or contains(text(),'captcha is incorrect') or contains(@class,'captcha-error')]"));
            return !errors.isEmpty() && errors.stream().anyMatch(WebElement::isDisplayed);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Click the captcha image / refresh icon to load a new captcha image.
     * Adjust the XPath to match the actual refresh element on the ECI site.
     */
    private static void refreshCaptcha() {
        // Exact XPath for the refresh icon (img[2] next to the captcha img[1])
        try {
            WebElement refresh = driver.findElement(
                    By.xpath("//*[@id='textContent']/div[1]/div[1]/div[2]/div[2]/div[2]/div[2]/div/div/div[2]/img[2]"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", refresh);
            sleep(150);
            refresh.click();
            System.out.println("  Captcha refreshed via exact XPath.");
            sleep(800);
        } catch (Exception e) {
            System.out.println("  Could not click captcha refresh icon: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Download button
    // -----------------------------------------------------------------------

    private static void clickDownloadSelected() {
        try {
            WebElement downloadBtn = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[contains(normalize-space(.),'Download Selected PDFs')]")));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", downloadBtn);
            sleep(200);
            downloadBtn.click();
            System.out.println("  Clicked 'Download Selected PDFs'.");
        } catch (Exception e) {
            System.out.println("  Error clicking 'Download Selected PDFs': " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Pagination
    // -----------------------------------------------------------------------

    private static boolean clickNextPageArrowIfPresent() {
        try {
            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollTo(0, document.body.scrollHeight);");
            sleep(700);

            int currentPage = -1;
            try {
                WebElement pageNumStrong = driver.findElement(
                        By.cssSelector("span.control-btn2 strong"));
                String txt = pageNumStrong.getText().trim();
                if (!txt.isEmpty()) currentPage = Integer.parseInt(txt);
            } catch (Exception ignored) {}

            List<WebElement> controlBtns = driver.findElements(
                    By.cssSelector("div.pagination .control-btn"));
            WebElement nextBtn = null;
            for (WebElement btn : controlBtns) {
                if (btn.getText().replaceAll("\\s+", "").equals(">")) {
                    nextBtn = btn;
                    break;
                }
            }

            if (nextBtn == null || !nextBtn.isDisplayed() || !nextBtn.isEnabled()) {
                System.out.println("  Next page arrow not found or not clickable.");
                return false;
            }

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});", nextBtn);
            sleep(150);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", nextBtn);
            System.out.println("  Clicked next page arrow '>'.");

            // Wait for page number to increment
            final int capturedPage = currentPage;
            long timeout = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < timeout) {
                sleep(300);
                try {
                    WebElement pageNumStrong = driver.findElement(
                            By.cssSelector("span.control-btn2 strong"));
                    String txt = pageNumStrong.getText().trim();
                    if (!txt.isEmpty()) {
                        int newPage = Integer.parseInt(txt);
                        if (capturedPage == -1 || newPage != capturedPage) return true;
                    }
                } catch (Exception ignored) {}
            }

            System.out.println("  Page number did not change — assuming last page.");
            return false;

        } catch (Exception e) {
            System.out.println("  Pagination error: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Filter-selection helpers with auto-discovery
    // -----------------------------------------------------------------------

    /** Prints every <select> element's name + id so we can identify unknown dropdowns. */
    private static void printAllSelectNames() {
        List<WebElement> selects = driver.findElements(By.tagName("select"));
        if (selects.isEmpty()) {
            System.out.println("  (no <select> elements found on page)");
            return;
        }
        for (WebElement s : selects) {
            String name = s.getAttribute("name");
            String id   = s.getAttribute("id");
            String opts = "";
            try {
                List<WebElement> options = new Select(s).getOptions();
                if (options.size() <= 5) {
                    StringBuilder sb = new StringBuilder();
                    for (WebElement o : options) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append("[").append(o.getText().trim()).append("]");
                    }
                    opts = sb.toString();
                } else {
                    opts = options.size() + " options";
                }
            } catch (Exception ignored) {}
            System.out.println("  <select> name=[" + name + "] id=[" + id + "]  options: " + opts);
        }
    }

    /**
     * Waits for a roll-type / eroll-type dropdown to appear after state+year are chosen.
     * Tries "roleType" first; if not found within 5 s, falls back to selecting index 1
     * on the first unknown dropdown (not stateCode, not revyear).
     */
    private static void waitAndSelectRollType() {
        // First try the original name
        try {
            WebElement el = new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(ExpectedConditions.presenceOfElementLocated(By.name("roleType")));
            new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(d -> new Select(el).getOptions().size() > 1);
            new Select(el).selectByIndex(1);
            System.out.println("  Roll type selected via name='roleType'.");
            sleep(800);
            return;
        } catch (Exception e) {
            System.out.println("  'roleType' not found, scanning for roll-type dropdown...");
        }

        // Fallback: find a <select> that is NOT stateCode or revyear and has >1 option
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<WebElement> selects = driver.findElements(By.tagName("select"));
            for (WebElement s : selects) {
                String name = s.getAttribute("name");
                if ("stateCode".equals(name) || "revyear".equals(name)) continue;
                try {
                    Select sel = new Select(s);
                    if (sel.getOptions().size() > 1) {
                        System.out.println("  Selecting roll type on dropdown name='" + name + "' index 1");
                        sel.selectByIndex(1);
                        sleep(800);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            sleep(500);
        }
        System.out.println("  WARNING: Could not find roll-type dropdown — continuing anyway.");
    }

    /**
     * Selects the language dropdown. Tries "langCd" first; if absent, picks index 1
     * on the last unknown <select> (not stateCode, revyear, district).
     */
    private static void waitAndSelectLangCd() {
        try {
            WebElement el = new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(ExpectedConditions.presenceOfElementLocated(By.name("langCd")));
            new WebDriverWait(driver, Duration.ofSeconds(8))
                    .until(d -> new Select(el).getOptions().size() > 1);
            new Select(el).selectByIndex(1);
            System.out.println("  Language selected via name='langCd'.");
            sleep(800);
            return;
        } catch (Exception e) {
            System.out.println("  'langCd' not found, scanning for language dropdown...");
        }

        // Fallback: last unknown dropdown with >1 option
        List<WebElement> selects = driver.findElements(By.tagName("select"));
        java.util.Set<String> known = new java.util.HashSet<>(
                java.util.Arrays.asList("stateCode", "revyear", "district"));
        for (int i = selects.size() - 1; i >= 0; i--) {
            WebElement s = selects.get(i);
            String name = s.getAttribute("name");
            if (known.contains(name)) continue;
            try {
                Select sel = new Select(s);
                if (sel.getOptions().size() > 1) {
                    System.out.println("  Selecting language on dropdown name='" + name + "' index 1");
                    sel.selectByIndex(1);
                    sleep(800);
                    return;
                }
            } catch (Exception ignored) {}
        }
        System.out.println("  WARNING: Could not find language dropdown — continuing anyway.");
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", DOWNLOAD_DIR);
        prefs.put("download.prompt_for_download", false);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        driver = new ChromeDriver(options);
        wait   = new WebDriverWait(driver, Duration.ofSeconds(25));
        driver.manage().window().maximize();
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(1));

        Scanner sc = new Scanner(System.in);

        try {
            driver.get("https://voters.eci.gov.in/download-eroll");
            sleep(2000);

            System.out.println("=== Selecting filters ===");
            waitAndSelectByVisibleText("stateCode", "Tamil Nadu");
            sleep(1000);
            waitAndSelectByVisibleText("revyear", "2026");
            sleep(1000);

            // DEBUG: print all <select> names currently on the page so we can
            // identify the correct name for the roll-type dropdown.
            System.out.println("--- Dropdowns visible after state+year ---");
            printAllSelectNames();

            // After state+year are chosen, the roll-type dropdown should appear.
            // We wait up to 15 s for ANY new <select> beyond stateCode and revyear.
            waitAndSelectRollType();

            waitAndSelectByVisibleText("district", "Kanniyakumari");
            sleep(1000);
            selectFirstAc();
            sleep(1000);

            // DEBUG: print dropdowns again before language selection
            System.out.println("--- Dropdowns visible before language ---");
            printAllSelectNames();

            waitAndSelectLangCd();
            sleep(1500);

            int page = 1;

            // First page
            System.out.println("\n=== PAGE " + page + " ===");
            int sel = selectAllCheckboxes();
            System.out.println("  Selected " + sel + " checkboxes.");

            handleCaptchaAndDownload(page, sc);
            sleep(2000);

            // Subsequent pages
            while (true) {
                System.out.println("\nAttempting to move to next page...");
                boolean moved = clickNextPageArrowIfPresent();
                if (!moved) {
                    System.out.println("No further pages. Done.");
                    break;
                }

                page++;
                sleep(1000);

                System.out.println("\n=== PAGE " + page + " ===");
                int selectedThis = selectAllCheckboxes();
                System.out.println("  Selected " + selectedThis + " checkboxes.");

                if (selectedThis > 0) {
                    handleCaptchaAndDownload(page, sc);
                    sleep(2000);
                } else {
                    System.out.println("  No checkboxes found on this page — skipping download.");
                }
            }

            System.out.println("\n=== All pages processed. PDFs saved to: " + DOWNLOAD_DIR + " ===");

        } finally {
            sleep(1500);
            driver.quit();
            sc.close();
        }
    }
}