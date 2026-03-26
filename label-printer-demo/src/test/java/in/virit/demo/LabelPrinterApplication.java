package in.virit.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "in.virit")
public class LabelPrinterApplication {

    public static void main(String... args) {
        SpringApplication.run(LabelPrinterApplication.class, args);
    }
}
