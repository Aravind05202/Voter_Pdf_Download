package TestCaptcha;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.io.FileHandler;

import io.github.bonigarcia.wdm.WebDriverManager;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;

public class TestCaptcha_tesract {

    public static void main(String[] args) {

        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver();

        try {
            driver.manage().window().maximize();
            driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);

            driver.get("https://www.irctc.co.in/nget/train-search");

            driver.findElement(By.xpath("//button[text()='OK']")).click();
            driver.findElement(By.xpath("//a[normalize-space()='LOGIN']")).click();

            // Capture CAPTCHA image
            WebElement imageelement = driver.findElement(
                    By.xpath("(//*[@id='nlpImgContainer']//following::img)[2]"));

            File src = imageelement.getScreenshotAs(OutputType.FILE);

            String path = "C:\\Users\\Aravind\\captchaimages\\captcha.png";

            // Ensure folder exists
            new File("C:\\Users\\Aravind\\captchaimages").mkdirs();

            FileHandler.copy(src, new File(path));

            Thread.sleep(2000);

            // OCR using Tesseract
            ITesseract image = new Tesseract();

            // 👉 Set tessdata path
            image.setDatapath("C:\\Users\\Aravind\\eclipse-workspace\\voter-pdf-downloader.zip_expanded\\tessdata");

            String str = image.doOCR(new File(path));

            System.out.println("OCR Result: " + str);

            // Clean captcha text
            String captcha = str.replaceAll("[^a-zA-Z0-9]", "");

            driver.findElement(By.id("nlpAnswer")).sendKeys(captcha);

        } catch (Exception e) {
            e.printStackTrace(); // better debugging
        }

        // driver.quit();
    }
}