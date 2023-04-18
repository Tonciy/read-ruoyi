package com.ruoyi.framework.aspectj;

import java.util.ArrayList;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.security.context.PermissionContextHolder;

/**
 * 数据过滤处理
 *
 * @author ruoyi
 */
@Aspect
@Component
public class DataScopeAspect
{
    /**
     * 全部数据权限
     */
    public static final String DATA_SCOPE_ALL = "1";

    /**
     * 自定数据权限
     */
    public static final String DATA_SCOPE_CUSTOM = "2";

    /**
     * 部门数据权限
     */
    public static final String DATA_SCOPE_DEPT = "3";

    /**
     * 部门及以下数据权限
     */
    public static final String DATA_SCOPE_DEPT_AND_CHILD = "4";

    /**
     * 仅本人数据权限
     */
    public static final String DATA_SCOPE_SELF = "5";

    /**
     * 数据权限过滤关键字
     */
    public static final String DATA_SCOPE = "dataScope";

    @Before("@annotation(controllerDataScope)")
    public void doBefore(JoinPoint point, DataScope controllerDataScope) throws Throwable
    {
        clearDataScope(point);
        handleDataScope(point, controllerDataScope);
    }

    protected void handleDataScope(final JoinPoint joinPoint, DataScope controllerDataScope)
    {
        // 获取当前的用户
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNotNull(loginUser))
        {
            SysUser currentUser = loginUser.getUser();
            // 如果是超级管理员，则不过滤数据
            if (StringUtils.isNotNull(currentUser) && !currentUser.isAdmin())
            {
                //TODO: 暂时还不知道哪里用到这个，默认为“”
                String permission = StringUtils.defaultIfEmpty(controllerDataScope.permission(), PermissionContextHolder.getContext());
                dataScopeFilter(joinPoint, currentUser, controllerDataScope.deptAlias(),
                        controllerDataScope.userAlias(), permission);
            }
        }
    }

    /**
     * 数据范围过滤
     *
     * @param joinPoint 切点
     * @param user 用户
     * @param deptAlias 部门别名
     * @param userAlias 用户别名
     * @param permission 权限字符
     */
    public static void dataScopeFilter(JoinPoint joinPoint, SysUser user, String deptAlias, String userAlias, String permission)
    {
        StringBuilder sqlString = new StringBuilder();
        // conditions 主要是用来记录这个用户所具有的数据权限值
        List<String> conditions = new ArrayList<String>();

        // 一个用户可以具有多个角色，而一个角色对应一种数据权限，也就是说某个用户可能具有多种数据权限，需要把所有权限都要找出来
        for (SysRole role : user.getRoles())
        {
            String dataScope = role.getDataScope();

            // 若依把数据权限分成了5种，自定义权限2是比较特殊的一种，因为它可以动态选择可以操作的部门
            // 也就是说如果一个角色具有A,B两种角色，并且这两个角色都是自定义数据权限的话，那么A可以操作的部门和B可以操作的部门很大可能是不一致的
            //        但是如果这两个角色的数据权限都不是自定义数据权限并且一致的话，那么在遍历A时拼接好了对应SQL，而遍历B时就没有这个继续拼接SQL的必要了
            //        因为此时它们构成的SQL都是一样的
            // 下面这个if判断就是达到这样的效果，当当前角色的数据权限不是自定义权限时，并且在之前已经遍历过此数据权限（也就构造好了对应SQL），此时可以跳过了
            if (!DATA_SCOPE_CUSTOM.equals(dataScope) && conditions.contains(dataScope))
            {
                continue;
            }
            // TODO：下面这个if逻辑暂时没看懂
            if (StringUtils.isNotEmpty(permission) && StringUtils.isNotEmpty(role.getPermissions())
                    && !StringUtils.containsAny(role.getPermissions(), Convert.toStrArray(permission)))
            {
                continue;
            }
            // 具有数据权限，
            if (DATA_SCOPE_ALL.equals(dataScope))
            {
                sqlString = new StringBuilder();
                break;
            }
            // 自定义数据权限
            else if (DATA_SCOPE_CUSTOM.equals(dataScope))
            {
                sqlString.append(StringUtils.format(
                        " OR {}.dept_id IN ( SELECT dept_id FROM sys_role_dept WHERE role_id = {} ) ", deptAlias,
                        role.getRoleId()));
            }
            // 部门数据权限
            else if (DATA_SCOPE_DEPT.equals(dataScope))
            {
                sqlString.append(StringUtils.format(" OR {}.dept_id = {} ", deptAlias, user.getDeptId()));
            }
            // 部门及以下数据权限
            else if (DATA_SCOPE_DEPT_AND_CHILD.equals(dataScope))
            {
                //find_in set()类似就是说查询下级部门
                sqlString.append(StringUtils.format(
                        " OR {}.dept_id IN ( SELECT dept_id FROM sys_dept WHERE dept_id = {} or find_in_set( {} , ancestors ) )",
                        deptAlias, user.getDeptId(), user.getDeptId()));
            }
            // 仅本人数据权限
            else if (DATA_SCOPE_SELF.equals(dataScope))
            {
                // 主要是查用户信息时用的
                if (StringUtils.isNotBlank(userAlias))
                {
                    sqlString.append(StringUtils.format(" OR {}.user_id = {} ", userAlias, user.getUserId()));
                }
                else
                {
                    // 这里主要是给查部门，角色信息用的（结合前端页面效果来看）
                    //   查部门时不用user_id={}来限定，是因为如果使用user_id的话，相当于把此用户的部门信息查出来，此用户就有了操作此部门的权限，这与此用户具有的仅本人数据权限相违背
                    //   查角色信息时同理
                    //   而查用户时可以把用户当前信息查出来，自己可以改自己，刚好对应仅本人数据权限



                    // 实际上不存在dept_id=0的部门信息的
                    // 之所以要这样记录，因为数据权限为本人时，那么默认情况下也就查不到其他任何情况
                    // 用到@DataScope，也即AOP代理的有三个
                    // * SysDept
                    //   对于部门信息，数据权限为本人的话，通过d.dept_id = 0拼接SQL后，也即不会查到任何部门信息出来，这也符合我们的逻辑
                    //   因为当前人肯定知道自己的部门信息，其他部门无权知道
                    // * SysRole
                    //   同理，不会显示任何角色信息
                    // * SysUser
                    sqlString.append(StringUtils.format(" OR {}.dept_id = 0 ", deptAlias));
                }
            }
            conditions.add(dataScope);
        }

        if (StringUtils.isNotBlank(sqlString.toString()))
        {
            Object params = joinPoint.getArgs()[0];
            if (StringUtils.isNotNull(params) && params instanceof BaseEntity)
            {
                BaseEntity baseEntity = (BaseEntity) params;
                baseEntity.getParams().put(DATA_SCOPE, " AND (" + sqlString.substring(4) + ")");
            }
        }
    }

    /**
     * 拼接权限sql前先清空params.dataScope参数防止注入
     */
    private void clearDataScope(final JoinPoint joinPoint)
    {
        // 这里是为了防止他人故意另外传个dataScope字段以及对应值进来，在后续拼接SQL时，造成SQL注入问题
        Object params = joinPoint.getArgs()[0];
        if (StringUtils.isNotNull(params) && params instanceof BaseEntity)
        {
            BaseEntity baseEntity = (BaseEntity) params;
            baseEntity.getParams().put(DATA_SCOPE, "");
        }
    }
}
