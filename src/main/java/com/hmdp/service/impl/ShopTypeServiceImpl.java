package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;
    @Override
    public Object queryByRedis() {
        //1.查询redis中是否有缓存
        String shop = stringRedisTemplate.opsForValue().get("cache:shopType");

        if (StrUtil.isNotBlank(shop)) {
            //如果存在，将json格式转换为List返回给前端
            try {
                List<ShopType> typeList = objectMapper.readValue(shop, new TypeReference<List<ShopType>>() {});
                return Result.ok(typeList);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

        }
        //2.如果没有，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        try {
            //将List转为json
            String shopList = objectMapper.writeValueAsString(typeList);
            //保存到redis
            stringRedisTemplate.opsForValue().set("cache:shopType", shopList);
            stringRedisTemplate.expire("cache:shopType",CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(typeList);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }
}
