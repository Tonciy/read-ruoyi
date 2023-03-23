package com.ruoyi.framework.config;

import java.util.Random;
import com.google.code.kaptcha.text.impl.DefaultTextCreator;

/**
 * 验证码文本生成器
 *
 * @author ruoyi
 */
public class KaptchaTextCreator extends DefaultTextCreator
{
    private static final String[] CNUMBERS = "0,1,2,3,4,5,6,7,8,9,10".split(",");

    @Override
    public String getText()
    {
        Integer result = 0;
        Random random = new Random();
        // 这个x，y值不是实际值，而是在CNUMBERS中获取具体指的索引（不过由于刚好对应，也可以理解为实际值）
        int x = random.nextInt(10);
        int y = random.nextInt(10);
        StringBuilder suChinese = new StringBuilder();
        // randomoperands用来表示使用哪种运算符
        //  0--乘法
        //  1
        //   - x不为0能做除数，并且可以整除的，除法
        //   - 要不然就是加法
        //  2--减法（谁大谁排在前面）
        int randomoperands = random.nextInt(3);
        if (randomoperands == 0)
        {
            result = x * y;
            suChinese.append(CNUMBERS[x]);
            suChinese.append("*");
            suChinese.append(CNUMBERS[y]);
        }
        else if (randomoperands == 1)
        {
            // x的值作为除数，并且为了能得到结果，需判断取余的值为0（即可以整除）
            if ((x != 0) && y % x == 0)
            {
                result = y / x;
                suChinese.append(CNUMBERS[y]);
                suChinese.append("/");
                suChinese.append(CNUMBERS[x]);
            }
            else
            {
                result = x + y;
                suChinese.append(CNUMBERS[x]);
                suChinese.append("+");
                suChinese.append(CNUMBERS[y]);
            }
        }
        else
        {
            if (x >= y)
            {
                result = x - y;
                suChinese.append(CNUMBERS[x]);
                suChinese.append("-");
                suChinese.append(CNUMBERS[y]);
            }
            else
            {
                result = y - x;
                suChinese.append(CNUMBERS[y]);
                suChinese.append("-");
                suChinese.append(CNUMBERS[x]);
            }
        }
        // 这个@符号是各标识符，将算式与结果区分开来，方便取
        suChinese.append("=?@" + result);
        return suChinese.toString();
    }
}