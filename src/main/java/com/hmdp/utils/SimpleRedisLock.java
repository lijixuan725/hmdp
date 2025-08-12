package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;

    private StringRedisTemplate stringRedisTemplate;
    public static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unLock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeOutSec) {
        long threadId = Thread.currentThread().getId();
        boolean sucess = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name,ID_PREFIX + threadId,timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(sucess);
    }

//    @Override
//    public void unlock() {
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        String id = ID_PREFIX + Thread.currentThread().getId();
//        if (lockId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),ID_PREFIX + Thread.currentThread().getId());
    }
}
