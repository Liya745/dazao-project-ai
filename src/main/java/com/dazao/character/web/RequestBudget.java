package com.dazao.character.web;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 单实例本地请求额度，公网计费应使用持久化配额。 */
@Component
public class RequestBudget {

    private final Map<String, Bucket> buckets = new HashMap<>();

    public synchronized void consume(String user) {
        long window = Instant.now().getEpochSecond() / 3600;
        buckets.entrySet().removeIf(e -> e.getValue().window() != window);
        Bucket bucket = buckets.getOrDefault(user, new Bucket(window, 0));
        if (bucket.count() >= 60) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "本小时的 60 次模型请求额度已用完");
        }
        buckets.put(user, new Bucket(window, bucket.count() + 1));
    }

    private record Bucket(long window, int count) {
    }
}
