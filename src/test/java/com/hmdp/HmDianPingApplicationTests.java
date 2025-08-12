package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest

class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void testSaveShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + "1",shop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    @Test
    void testRedis() {
        System.out.println(stringRedisTemplate.getConnectionFactory().getConnection().ping());
    }
    @Test
    void testRedisIdWorker() throws UnknownHostException {
        System.out.println(InetAddress.getLocalHost().getHostAddress());
    }
    @Test
    void loadShopData() {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));

            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }


    }


}
