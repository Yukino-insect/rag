
package com.jh.han.rag.user.service;

import com.jh.han.rag.user.controller.request.LoginRequest;
import com.jh.han.rag.user.controller.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginRequest requestParam);

    void logout();
}
