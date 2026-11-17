package io.github.zeeqy.ledger;

import org.springframework.boot.SpringApplication;

public class TestPaymentLedgerApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(PaymentLedgerApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
