package com.multiplier.reactive.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.multiplier.reactive.constants.CommonConstants.DATE;
import static com.multiplier.reactive.constants.CommonConstants.TIME;

@Slf4j
@Service
public class DatesUtil {

    public static final LocalDate TODAY_DATE = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    public static final LocalTime TODAY_TIME = LocalTime.now(ZoneId.of("Asia/Kolkata"));
    private static final LocalTime MARKET_START = LocalTime.of(9, 0);
    private static final LocalTime MID_NIGHT = LocalTime.MIDNIGHT;
    private static final LocalTime FIVE_AM = LocalTime.of(5, 0);

    public static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 1, 15), // Municipal Corporation Election - Maharashtra - Thursday
            LocalDate.of(2026, 1, 26), // Republic Day - Monday

            LocalDate.of(2026, 3, 3), // Holi - Tuesday
            LocalDate.of(2026, 3, 26), // Shri Ram Navami - Thursday
            LocalDate.of(2026, 3, 31), // Shri Mahavir Jayanti- Tuesday

            LocalDate.of(2026, 4, 3), // Good Friday - Friday
            LocalDate.of(2026, 4, 14), // Dr. Baba Saheb Ambedkar Jayanti - Tuesday

            LocalDate.of(2026, 5, 1), // Maharashtra Day - Friday
            LocalDate.of(2026, 5, 28), // Bakri Id - Thursday

            LocalDate.of(2026, 6, 26), // Muharram - Friday

            LocalDate.of(2026, 9, 14), // Ganesh Chaturthi - Monday

            LocalDate.of(2026, 10, 2), // Mahatma Gandhi Jayanti - Friday
            LocalDate.of(2026, 10, 20), // Dussehra - Tuesday

            LocalDate.of(2026, 11, 10), // Diwali-Balipratipada - Tuesday
            LocalDate.of(2026, 11, 24), // Prakash Gurpurb Sri Guru Nanak Dev -Tuesday

            LocalDate.of(2026, 12, 25) // Christmas - Friday
    );

    public List<LocalDate> getTradingDates(LocalDate today, int tradingDays) {
        List<LocalDate> dates = new ArrayList<>(tradingDays);
        for (int i = 0; dates.size() < tradingDays; i++) {
            LocalDate date = today.minusDays(i);
            if (isTradingDay(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    public static LocalDate getTodayDate() {
        LocalDate currentDate = null;
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime start = LocalTime.MIDNIGHT; // 12:00 AM
        LocalTime end = LocalTime.of(5, 0); // 5:00 AM
        if (!(!now.isBefore(start) && now.isBefore(end)) && !HOLIDAYS.contains(TODAY_DATE)
                && !TODAY_DATE.getDayOfWeek().equals(DayOfWeek.SATURDAY)
                && !TODAY_DATE.getDayOfWeek().equals(DayOfWeek.SUNDAY)) {
            currentDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        } else {
            currentDate = getWorkingPreviousDay();
        }
        log.info("CurrentDate : {}", currentDate.toString());
        return currentDate;
    }

    public static LocalDate getWorkingPreviousDay() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        do {
            date = date.minusDays(1);
        } while (HOLIDAYS.contains(date) || date.getDayOfWeek().equals(DayOfWeek.SATURDAY)
                || date.getDayOfWeek().equals(DayOfWeek.SUNDAY));
        return date;
    }

    public static LocalDate getWorkingPreviousDay(LocalDate baseDate) {
        LocalDate date = baseDate;
        do {
            date = date.minusDays(1);
        } while (HOLIDAYS.contains(date) || date.getDayOfWeek().equals(DayOfWeek.SATURDAY)
                || date.getDayOfWeek().equals(DayOfWeek.SUNDAY));
        return date;
    }

    public static LocalDate getStartDate(int tradingDays) {
        LocalDate date = LocalDate.now();
        if (tradingDays != 0) {
            for (int count = 1; count <= tradingDays;) {
                date = date.minusDays(1);
                // if (isTradingDay(date)) {
                count++;
                // }
            }
        } else if (tradingDays == -1) {
            return date.minusDays(1);
        }
        return date;
    }

    /**
     * Preserved original logic
     */
    public static int getDay() {
        if ((TIME.isAfter(MID_NIGHT) && TIME.isBefore(FIVE_AM)) || DATE.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return 1;
        } else if (DATE.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return 2;
        } else if (DATE.getDayOfWeek() == DayOfWeek.MONDAY
                && TIME.isBefore(MARKET_START)) {
            return 3;
        }
        return 0;
    }

    /**
     * Preserved original logic
     */
    public static LocalDate getTodayDay() {
        if ((TIME.isAfter(MID_NIGHT) && TIME.isBefore(FIVE_AM)) || DATE.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return DATE.minusDays(1);
        } else if (DATE.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return DATE.minusDays(2);
        } else if (DATE.getDayOfWeek() == DayOfWeek.MONDAY
                && TIME.isBefore(MARKET_START)) {
            return DATE.minusDays(3);
        }
        return DATE;
    }

    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY && !HOLIDAYS.contains(date);
    }

    // public static List<LocalDate> getDates(LocalDate today, int day) {
    // List<LocalDate> dates = new ArrayList<>();
    // for (int i = 0; dates.size() < day; i++) {
    // LocalDate date = today.minusDays(i);
    // if (isWeekday(date)) {
    // dates.add(date);
    // }
    // }
    // return dates;
    // }
    //
    public static boolean isWeekday(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY && !HOLIDAYS.contains(date);
    }

    public static List<LocalDate> getDatesNotIncludeToday(LocalDate today, int day) {
        LocalDate countFromPreviousDay = null;
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime start = LocalTime.MIDNIGHT; // 12:00 AM
        LocalTime end = LocalTime.of(15, 0); // 3:00 PM

        if (!now.isBefore(start) && now.isBefore(end)) {
            System.out.println("Now is between 12 AM and 3 PM.");
            countFromPreviousDay = today.minusDays(1);
        } else {
            System.out.println("Now is NOT between 12 AM and 3 PM.");
            countFromPreviousDay = today;
        }
        List<LocalDate> dates = new ArrayList<>();
        // condition is not tided with i (no of loop) because if saturday , sunday &
        // holidays are excluded no of count i may increase.
        // it runs until the list has desired value (int day).
        for (int i = 0; dates.size() < day; i++) {
            LocalDate date = countFromPreviousDay.minusDays(i);
            if (isWeekday(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

}
