package com.kyle.rag.repository;

import com.kyle.rag.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
/**
 * 用户仓库:登录名查询(登录用);其余走 JpaRepository 内置方法。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按登录名查用户(登录校验入口) */
    Optional<User> findByUsername(String username);
}
