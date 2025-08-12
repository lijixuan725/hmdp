package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    //将任意java对象存入redis，并设置过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //将任意java对象存入redis，并设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //根据key查询缓存，利用缓存空值解决缓存穿透问题
    public <R,ID> R queryByIdwithPassThrough(
            String keyprefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyprefix + id;
        //1.查询redis缓存是否存在商户
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if (Json != null) {
            return null;
        }
        //2.不存在，查询数据库
        R r = dbFallback.apply(id);
        if (r == null) {
            //3.数据库中不存在，缓存空字符串避免缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time, unit);

        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public <R,ID> R queryByIdwithLogicalExpire(
            String keyprefix,String lockKeyprefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyprefix + id;
        String lockKey = lockKeyprefix + id;
        //1.查询redis缓存是否存在商户
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(Json)) {
            //2.如果没有，则不存在该商品
            return null;
        }
        //3.缓存击中，要把json格式的转换为shop对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期,没过期，直接返回信息，
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //5.过期了，尝试获取互斥锁
        boolean flag = tryLock(lockKey);
        //6.获取成功，开启新线程，从数据库拿数据并保存缓存
        if (flag) {
            //再次查看缓存是否过期
            String Json1 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(Json1)) {
                RedisData redisData1 = JSONUtil.toBean(Json1, RedisData.class);
                R r1 = JSONUtil.toBean((JSONObject) redisData1.getData(), type);
                LocalDateTime expireTime1 = redisData1.getExpireTime();
                //4.判断是否过期,没过期，直接返回信息，
                if (expireTime1.isAfter(LocalDateTime.now())) {
                    return r1;
                }
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //7获取失败，返回旧的缓存信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",LOCK_SHOP_TTL,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }




}
