package com.ruoyi.framework.aspectj;

import java.util.Collection;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.enums.BusinessStatus;
import com.ruoyi.common.enums.HttpMethod;
import com.ruoyi.common.filter.PropertyPreExcludeFilter;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.domain.SysOperLog;

/**
 * 操作日志记录处理
 *
 * 对Controller类中接口方法中加了 自定义@Log注解的，采用AOP方式，做操作日志记录（比如说统计完成请求时间）
 * 
 * @author ruoyi
 */
@Aspect
@Component
public class LogAspect
{
    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    /** 排除敏感属性字段 */
    public static final String[] EXCLUDE_PROPERTIES = { "password", "oldPassword", "newPassword", "confirmPassword" };

    /** 计算操作消耗时间 */
    private static final ThreadLocal<Long> TIME_THREADLOCAL = new NamedThreadLocal<Long>("Cost Time");

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void boBefore(JoinPoint joinPoint, Log controllerLog)
    {
        // 存储请求到接口方法时的开始时间
        TIME_THREADLOCAL.set(System.currentTimeMillis());
    }

    /**
     * 处理完请求后执行
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult)
    {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     * 
     * @param joinPoint 切点
     * @param e 异常
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e)
    {
        handleLog(joinPoint, controllerLog, e, null);
    }

    /**
     * 实际的日志处理逻辑（这里包含了正常完成请求，以及请求出异常的状况）
     * @param joinPoint 连接点
     * @param controllerLog 自定义注解
     * @param e 请求出现异常的异常对象
     * @param jsonResult 请求完成时的返回结果
     */
    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult)
    {
        try
        {
            // 获取当前的用户
            LoginUser loginUser = SecurityUtils.getLoginUser();

            // *========数据库日志=========*//
            SysOperLog operLog = new SysOperLog();
            // 这里ordinal()方法是获取对应枚举值在其枚举类的索引（从0开始）
            // 比如说BusinessStatus枚举类只有两个枚举值，SUCCESS,FAIL，那么SUCCESS的索引值为0，FAIL的索引值为1
            // 默认是成功状态，如果出现异常，后面代码逻辑会覆盖其值
            operLog.setStatus(BusinessStatus.SUCCESS.ordinal());
            // 请求的地址
            String ip = IpUtils.getIpAddr();
            operLog.setOperIp(ip);
            // 这里对获取到的URI截取，是由于在数据库对应表的oper_url字段，是用varchar（255）来存储的
            // 正常来说大多数URI其实都很短的，达不到255个字符，但是比如说对于批量操作，SysUserController下的删除用户的接口
            // 采用的是路径接受参数，且接受多个参数，那么也就意味着是有可能出现删除超多个用户时，此时URI的长度是大于255的
            // 所以为了匹配数据库表的字段设计，进行了截取
            operLog.setOperUrl(StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255));
            if (loginUser != null)
            {
                // 操作者的用户名
                operLog.setOperName(loginUser.getUsername());
            }

            if (e != null)
            {
                // 出现异常时覆盖请求状态
                operLog.setStatus(BusinessStatus.FAIL.ordinal());
                // 这里对错误信息进行截取同理，是为了匹配数据库表的字段设计
                operLog.setErrorMsg(StringUtils.substring(e.getMessage(), 0, 2000));
            }
            // 日志设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            // 日志设置请求方式
            operLog.setRequestMethod(ServletUtils.getRequest().getMethod());
            // 处理注解上的参数
            getControllerMethodDescription(joinPoint, controllerLog, operLog, jsonResult);
            // 计算设置消耗时间
            operLog.setCostTime(System.currentTimeMillis() - TIME_THREADLOCAL.get());
            // 通过异步将操作日志保存到数据库
            AsyncManager.me().execute(AsyncFactory.recordOper(operLog));
        }
        catch (Exception exp)
        {
            // 记录本地异常日志
            log.error("异常信息:{}", exp.getMessage());
            exp.printStackTrace();
        }
        finally
        {
            TIME_THREADLOCAL.remove();
        }
    }

    /**
     * 获取注解中对方法的描述信息 用于Controller层注解
     * 
     * @param log 日志
     * @param operLog 操作日志
     * @throws Exception
     */
    public void getControllerMethodDescription(JoinPoint joinPoint, Log log, SysOperLog operLog, Object jsonResult) throws Exception
    {
        // 日志设置业务类型
        operLog.setBusinessType(log.businessType().ordinal());
        // 日志设置模块值
        operLog.setTitle(log.title());
        // 设置操作类别（后台，移动端）
        operLog.setOperatorType(log.operatorType().ordinal());
        // 是否需要保存request参数
        if (log.isSaveRequestData())
        {
            // 设置参数的信息
            setRequestValue(joinPoint, operLog, log.excludeParamNames());
        }
        // 是否需要保存response值
        if (log.isSaveResponseData() && StringUtils.isNotNull(jsonResult))
        {
            operLog.setJsonResult(StringUtils.substring(JSON.toJSONString(jsonResult), 0, 2000));
        }
    }

    /**
     * 获取请求的参数，放到log中
     * 
     * @param operLog 操作日志
     * @throws Exception 异常
     */
    private void setRequestValue(JoinPoint joinPoint, SysOperLog operLog, String[] excludeParamNames) throws Exception
    {
        String requestMethod = operLog.getRequestMethod();
        // POST 和 PUT为同一类，在请求体中获取参数信息，
        // GET 和 DELETE 为同一类，在请求URL上获取参数信息
        if (HttpMethod.PUT.name().equals(requestMethod) || HttpMethod.POST.name().equals(requestMethod))
        {
            // 获取参数（内部实现要麻烦一点，除了排除敏感字段外，还要排除如HttpServletRequest，MultipartFile等对象）
            String params = argsArrayToString(joinPoint.getArgs(), excludeParamNames);
            // 截取同理，为了匹配数据库表的字段设计
            operLog.setOperParam(StringUtils.substring(params, 0, 2000));
        }
        else
        {
            //获取参数
            Map<?, ?> paramsMap = ServletUtils.getParamMap(ServletUtils.getRequest());
            // 排除敏感属性字段后，转化为String，截取同理，为了匹配数据库表的字段设计
            operLog.setOperParam(StringUtils.substring(JSON.toJSONString(paramsMap, excludePropertyPreFilter(excludeParamNames)), 0, 2000));
        }
    }

    /**
     * 参数拼装
     */
    private String argsArrayToString(Object[] paramsArray, String[] excludeParamNames)
    {
        // paramsArray是实际的请求参数
        // excludeParamNames是注解上标明要排除的字段
        String params = "";
        if (paramsArray != null && paramsArray.length > 0)
        {
            for (Object o : paramsArray)
            {
                // 这里得排除在接口方法中引用的，如HttpServletRequest，BindResult，MultipartFile等对象
                if (StringUtils.isNotNull(o) && !isFilterObject(o))
                {
                    try
                    {
                        // 排除过滤属性后进行转化后拼接
                        String jsonObj = JSON.toJSONString(o, excludePropertyPreFilter(excludeParamNames));
                        // 多个json字符串之间用“ ”隔开
                        params += jsonObj.toString() + " ";
                    }
                    catch (Exception e)
                    {
                    }
                }
            }
        }
        return params.trim();
    }

    /**
     * 忽略敏感属性
     */
    public PropertyPreExcludeFilter excludePropertyPreFilter(String[] excludeParamNames)
    {
        return new PropertyPreExcludeFilter().addExcludes(ArrayUtils.addAll(EXCLUDE_PROPERTIES, excludeParamNames));
    }

    /**
     * 判断是否需要过滤的对象。
     * 
     * @param o 对象信息。
     * @return 如果是需要过滤的对象，则返回true；否则返回false。
     */
    @SuppressWarnings("rawtypes")
    public boolean isFilterObject(final Object o)
    {
        Class<?> clazz = o.getClass();
        if (clazz.isArray())
        {
            // clazz.getComponentType()返回的是数组的组件类型，比如说Integer[],返回的是Integer
            // a.isAssignableFrom（b）判断a是否是b的父类或接口
            // 如果此数组的组件类型为MultipartFile或其子类，得排除
            // 比如说：接口方法中可能接受多个文件，使用MultipartFile[]来接受的话
            return clazz.getComponentType().isAssignableFrom(MultipartFile.class);
        }
        // 与上述同理，就不过换成了用集合接受
        else if (Collection.class.isAssignableFrom(clazz))
        {

            Collection collection = (Collection) o;
            // 这里得逐个遍历，因为很可对使用List<Object>来接受，也就意味着其中的元素有可能是MultipartFile类型的
            for (Object value : collection)
            {
                return value instanceof MultipartFile;
            }
        }
        // 与上述同理，只不过换成了用Map接受
        else if (Map.class.isAssignableFrom(clazz))
        {
            Map map = (Map) o;
            for (Object value : map.entrySet())
            {
                Map.Entry entry = (Map.Entry) value;
                // 这里是判断value的类型
                return entry.getValue() instanceof MultipartFile;
            }
        }
        return o instanceof MultipartFile || o instanceof HttpServletRequest || o instanceof HttpServletResponse
                || o instanceof BindingResult;
    }
}
