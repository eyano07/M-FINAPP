package com.mbsc.hotellerie;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** M-FINAPP Hôtellerie : PMS (hôtel, motel, appartements) relié à M-FINAPP par une clé d'appairage. */
@SpringBootApplication
@EnableScheduling
public class HotellerieApplication {

    public static void main(String[] args) {
        SpringApplication.run(HotellerieApplication.class, args);
    }
}
