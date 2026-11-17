package io.github.zeeqy.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentLedgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentLedgerApiApplication.class, args);
    }
}
