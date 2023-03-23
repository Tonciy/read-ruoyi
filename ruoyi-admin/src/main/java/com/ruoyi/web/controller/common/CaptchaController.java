package com.ruoyi.web.controller.common;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.google.code.kaptcha.Producer;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.sign.Base64;
import com.ruoyi.common.utils.uuid.IdUtils;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 验证码操作处理
 * 
 * @author ruoyi
 */
@RestController
public class CaptchaController
{
    @Resource(name = "captchaProducer")
    private Producer captchaProducer;

    @Resource(name = "captchaProducerMath")
    private Producer captchaProducerMath;

    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private ISysConfigService configService;
    /**
     * 生成验证码
     */
    @GetMapping("/captchaImage")
    public AjaxResult getCode(HttpServletResponse response) throws IOException
    {
        AjaxResult ajax = AjaxResult.success();
        // 获取验证码开关   还能有关的？？菜狗落泪，暂时不知道这个配置值跟哪个功能点结合起来了
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        ajax.put("captchaEnabled", captchaEnabled);
        if (!captchaEnabled)
        {
            return ajax;
        }

        // 保存验证码信息
        String uuid = IdUtils.simpleUUID();
        // 拼接验证码存在Redis的key
        String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + uuid;
        // capStr是存储经过Base64处理后的图片
        // code是存储这个图片的实际值（算术表达式存的是对应结果，正常的字符串就是其本身）
        String capStr = null, code = null;
        BufferedImage image = null;

        // 获取验证码生成类型--支持多种验证码类型（数学运算，字符）
        String captchaType = RuoYiConfig.getCaptchaType();
        if ("math".equals(captchaType))
        {
            // 这里用到的是自定义数学算式验证码文本生成器（KaptchaTextCreator），得到的这个文本类似于 “5*8=？@40”
            String capText = captchaProducerMath.createText();
            // 取算式
            capStr = capText.substring(0, capText.lastIndexOf("@"));
            // 取结果
            code = capText.substring(capText.lastIndexOf("@") + 1);
            // 根据文本生成图片流
            image = captchaProducerMath.createImage(capStr);
        }
        else if ("char".equals(captchaType))
        {
            // 这里用到的是框架的验证码文本生成器
            capStr = code = captchaProducer.createText();
            image = captchaProducer.createImage(capStr);
        }
        // 带有缓存时间的存到Redis
        redisCache.setCacheObject(verifyKey, code, Constants.CAPTCHA_EXPIRATION, TimeUnit.MINUTES);
        // 转换流信息写出
        FastByteArrayOutputStream os = new FastByteArrayOutputStream();
        try
        {
            ImageIO.write(image, "jpg", os);
        }
        catch (IOException e)
        {
            return AjaxResult.error(e.getMessage());
        }

        ajax.put("uuid", uuid);
        //菜狗落泪，这个Base64工具类居然手写的。。。。。
        ajax.put("img", Base64.encode(os.toByteArray()));
        return ajax;
    }
}
