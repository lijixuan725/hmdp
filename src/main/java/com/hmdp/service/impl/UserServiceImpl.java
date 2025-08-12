package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号是否合规
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入正确的手机号！");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码在Redis里,并设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4.发送验证码
        log.debug("成功发送短信验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("请输入正确的手机号！");
        }
        //2.根据手机号，从Redis获取验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String usercode = loginForm.getCode();
        //验证验证码
        if (code == null || !code.toString().equals(usercode)) {
            return Result.fail("验证码错误！");
        }
        //3.根据手机号查询用户，从MySQL里查询
        User user = query().eq("phone",phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //4.保存用户到Redis中，此时Redis和session有差不多的功能，都是为了记录用户。
        //4.1随机生成token
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
        //4.2将user对象转换为Hash的Map存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String,Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //4.3 存储tokenkey，并且设置有效期
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,30,TimeUnit.MINUTES);
        //5.返回token
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));
        //保存用户到数据库
        save(user);
        return user;
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix =now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId.toString() + ":" + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取本月到今天的所有签到记录
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix =now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = USER_SIGN_KEY + userId.toString() + ":" + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
         return Result.ok(0);
        }
        //遍历循环
        //与1做与运算，得到数字的最后一个bit位

        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else{
               count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
