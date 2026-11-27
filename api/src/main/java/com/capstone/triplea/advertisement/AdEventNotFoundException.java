package com.capstone.triplea.advertisement;

// 광고 이벤트가 없음 (404)
public class AdEventNotFoundException extends RuntimeException {

    public AdEventNotFoundException(Long eventId) {
        super("광고 이벤트 없음: eventId=" + eventId);
    }
}
