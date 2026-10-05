
package com.jh.han.rag.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.jh.han.rag.user.controller.request.ChangePasswordRequest;
import com.jh.han.rag.user.controller.request.UserCreateRequest;
import com.jh.han.rag.user.controller.request.UserPageRequest;
import com.jh.han.rag.user.controller.request.UserUpdateRequest;
import com.jh.han.rag.user.controller.vo.UserVO;

public interface UserService {

    /**
     * 分页查询用户列表
     */
    IPage<UserVO> pageQuery(UserPageRequest requestParam);

    /**
     * 创建用户
     */
    String create(UserCreateRequest requestParam);

    /**
     * 更新用户
     */
    void update(String id, UserUpdateRequest requestParam);

    /**
     * 删除用户
     */
    void delete(String id);

    /**
     * 修改当前用户密码
     */
    void changePassword(ChangePasswordRequest requestParam);
}
