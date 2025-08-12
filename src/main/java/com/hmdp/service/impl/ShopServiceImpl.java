package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.val;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Object queryById(Long id) {
        // 方案一：防止缓存穿透
//        Shop shop = cacheClient.queryByIdwithPassThrough(
//                CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //方案二：防止缓存击穿，互斥锁
//        Shop shop = queryByIdwithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
        //方案三：防止缓存击穿，逻辑过期
        Shop shop = cacheClient.queryByIdwithLogicalExpire(
                CACHE_SHOP_KEY,LOCK_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //4.数据库中存在，保存在redis里，并返回给前端
        return Result.ok(shop);
    }

//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryByIdwithLogicalExpire(Long id) {
//        //1.查询redis缓存是否存在商户
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        if (StrUtil.isBlank(shopJson)) {
//            //2.如果没有，则不存在该商品
//            return null;
//        }
//        //3.缓存击中，要把json格式的转换为shop对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        //4.判断是否过期,没过期，直接返回信息，
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            return shop;
//        }
//        //5.过期了，尝试获取互斥锁
//        boolean flag = tryLock(LOCK_SHOP_KEY + id);
//        //6.获取成功，开启新线程，从数据库拿数据并保存缓存
//        if (flag) {
//            //再次查看缓存是否过期
//            String shopJson1 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//            if (StrUtil.isNotBlank(shopJson1)) {
//                RedisData redisData1 = JSONUtil.toBean(shopJson1, RedisData.class);
//                Shop shop1 = JSONUtil.toBean((JSONObject) redisData1.getData(), Shop.class);
//                LocalDateTime expireTime1 = redisData1.getExpireTime();
//                //4.判断是否过期,没过期，直接返回信息，
//                if (expireTime1.isAfter(LocalDateTime.now())) {
//                    return shop1;
//                }
//            }
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    this.saveData2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unLock(LOCK_SHOP_KEY + id);
//                }
//            });
//        }
//        //7获取失败，返回旧的缓存信息
//        return shop;
//    }

//    public Shop queryByIdwithPassThrough(Long id) {
//        //1.查询redis缓存是否存在商户
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        if (StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if (shopJson != null) {
//            return null;
//        }
//        //2.不存在，查询数据库
//        Shop shop = getById(id);
//        if (shop == null) {
//            //3.数据库中不存在，缓存空字符串避免缓存穿透
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"");
//            stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop));
//        stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

//    public Shop queryByIdwithMutex(Long id) {
//        //1.查询redis缓存是否存在商户
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        if (StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        if (shopJson != null) {
//            return null;
//        }
//        Shop shop = null;
//        try {
//            //2.不存在，获取互斥锁
//            boolean isLock = tryLock("lock:shop:" + id);
//            //3.互斥锁存在，等待再次执行
//            if (!isLock) {
//                Thread.sleep(50);
//                return queryByIdwithMutex(id);
//            }
//
//            //4.互斥锁不存在，查询数据库，并保存到Redis里，返回值
//            shop = getById(id);
//            if (shop == null) {
//                //3.数据库中不存在，缓存空字符串避免缓存穿透
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //5.释放锁
//            unLock("lock:shop:" + id);
//        }
//        return shop;
//    }

//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }
//
//    //可用于提前写入缓存和逻辑过期后写入缓存。
//    public void saveData2Redis(Long id,Long expireSeconds) {
//        //1.查询店铺
//        Shop shop = getById(id);
//        //2.封装逻辑过期时间
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        //3.写入Redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
//    }




    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除redis的缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y ==null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
            // 返回数据
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y), new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
