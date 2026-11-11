package com.vnkst.closing.service;

import com.vnkst.closing.utils.DatesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.vnkst.closing.constants.CommonConstants.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class CustomDate {

    /**
     * Start date = today - days
     */
    public String getEncodedMorning(int days) {
            String start = DatesUtil.getStartDate(days) + " 09:15:00";
            return URLEncoder.encode(start, StandardCharsets.UTF_8);
    }

    /**
     * End date = today
     */
    public String getEncodedEvening() {
        int day = 0;
        LocalDateTime now = LocalDateTime.now();
        String end = null;
        if(now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = 1;
        } else if(now.getDayOfWeek() == DayOfWeek.SATURDAY) {
            day = 2;
        } else if ((TIME.isAfter(MID_NIGHT) && TIME.isBefore(FIVE_AM)) && DATE.getDayOfWeek() == DayOfWeek.MONDAY){
            day = 3;
        } else if((TIME.isAfter(MID_NIGHT) && TIME.isBefore(FIVE_AM))){
            end = LocalDate.now().minusDays(1) + " 15:35:00";
            return URLEncoder.encode(end, StandardCharsets.UTF_8);
        }
        end = DatesUtil.getStartDate(day) + " 15:35:00";
        return URLEncoder.encode(end, StandardCharsets.UTF_8);
    }

}