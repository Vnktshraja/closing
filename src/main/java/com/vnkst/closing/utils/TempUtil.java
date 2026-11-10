package com.vnkst.closing.utils;

import lombok.extern.slf4j.Slf4j;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static com.vnkst.closing.utils.DatesUtil.isWeekday;

@Slf4j
public class TempUtil {

    public static boolean isItMarketTime() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        return isItMarketTime(now);
    }

    public static boolean isMarketHours() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return isWeekday(today) && isItMarketTime();
    }

    public static boolean isItMarketTime(LocalTime startTime) {
        LocalTime eveningMarketTime = LocalTime.of(15, 35, 0);
        LocalTime morningMarketTime = LocalTime.of(9, 10, 0);
        return !startTime.isAfter(eveningMarketTime) && !morningMarketTime.isAfter(startTime);
    }

    public static boolean isFno(String symbol){
        return symbol.chars().anyMatch(Character::isDigit);
    }

    public static double roundOff(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static void setPivotValues(DailyRecord result ) {

        double h = result.getHigh();
        double l = result.getLow();
        double c = result.getLtp();

//        The main pivot point (P) is the average of the previous period’s High (H), Low (L), and Close (C) prices:
//        P = (H + L + C)/3
        double p = (h + l + c) / 3 ;

        result.setPivot(roundOff(p));
//        Support and Resistance Levels
//        Once you have the pivot point, calculate support and resistance levels:
//	•	First Resistance (R1)
//                R1 = (2 * P) - L
        double r1 = (2 * p ) - l;
        result.setResistance1(roundOff(r1));

//	•	First Support (S1)
//                S1 = (2 * P) - H
        double s1 = (2 * p) - h;
        result.setSupport1(roundOff(s1));

//	•	Second Resistance (R2)
//                R2 = P + (H - L)
        double r2 = p + (h - l);
        result.setResistance2(roundOff(r2));

//	•	Second Support (S2)
//                S2 = P - (H - L)
        double s2 = p - (h - l);
        result.setSupport2(roundOff(s2));

//	•	Third Resistance (R3)
//        R3 = P * 2 + (High - (2 * Low))
        double r3 = p * 2 + (h - (2 * l));
        result.setResistance3(roundOff(r3));

//	•	Third Support (S3)
//        S3 = P * 2 - (2 * High - Low)
        double s3 = p * 2 - (2 * h - l);
        result.setSupport3(roundOff(s3));

//	•	Third Resistance (R4)
//        R4 = P * 3 + (High - (3 * Low))
//        double r4 = p * 3 + (h - (3 * l));
//        result.setResistance4(roundOff(r4));

//	•	Third Support (S4)
//        S4 = P * 3 - (3 * High - Low)
//        double s4 = p * 3 - (3 * h - l);
//        result.setSupport4(roundOff(s4));
    }

}
