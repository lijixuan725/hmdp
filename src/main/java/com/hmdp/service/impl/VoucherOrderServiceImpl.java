package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ILock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private ExecutorService seckillOrderExecutor;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private String queueName = "stream.orders";

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private volatile boolean running = true;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //初始化完毕就开始运行
    @PostConstruct
    private void init() {
        seckillOrderExecutor.submit(() -> {
            while (running) {
                handlePendingList();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // 可以选择退出循环或打印日志，不要直接抛异常中断线程
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    break; // 或者用return跳出循环
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        running = false;
        SECKILL_ORDER_EXECUTOR.shutdown();
    }

    //异步创建线程任务，取出订单信息，修改数据库下单
    private class VoucherOrderHandler implements Runnable, com.hmdp.service.impl.VoucherOrderHandler {

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    handlePendingList();
                    log.error("处理订单异常", e);
                }
            }
        }
    }




    private void handlePendingList() {
        try {
            // 一次最多读10条，避免一次太多，压力太大
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(10),  // 一批最多读10条
                    StreamOffset.create(queueName, ReadOffset.from("0"))
            );
            if (list == null || list.isEmpty()) {
                // 没有pending消息，直接返回，不循环
                return;
            }
            for (MapRecord<String, Object, Object> record : list) {
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                // 确认消息已消费
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            }
        } catch (Exception e) {
            log.error("处理pendingList异常", e);
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //事务提交之后再释放锁
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.查看是否在抢购时间内
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("抢购未开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("抢购已结束");
//        }
//        //3.查看是否有库存
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("没有库存了~");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //事务提交之后再释放锁
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("只能买一张哈");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
            Long userId = UserHolder.getUser().getId();
            long orderId = redisIdWorker.nextId("order");
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString(),String.valueOf(orderId));
            int r = result.intValue();
            if (r != 0) {
                return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
            }


            proxy = (IVoucherOrderService) AopContext.currentProxy();
            return Result.ok(orderId);
    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //根据用户id和优惠券id查询是否有订单（规定一人一单）
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //没有订单，就开始创建订单，但是要加同步锁
        if (count > 0) {
            log.error("用户已经购买过了");
            return;
        }
        //扣减库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)
                .update();
        if (!flag) {
            log.error("扣减库存失败！");
            return;
        }
        save(voucherOrder);

    }
}
