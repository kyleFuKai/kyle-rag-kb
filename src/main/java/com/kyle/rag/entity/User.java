package com.kyle.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
/**
 * 用户实体,对应表 users(演示版用户表:生产应对接企业 SSO/LDAP)。
 * id 与 kb_permission.USER 授权、文档上传者等处的 userId 对应;
 * passwordHash 为 BCrypt 哈希,登录校验用 matches,明文永不落库。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/11/10
 */
@Entity
@Table(name = "users")
@Data
public class User {

    /** 主键,数据库自增;kb_permission.USER 授权与各业务表的 userId 均对应此值 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录名,唯一 */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 密码哈希 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 所属部门 ID */
    @Column(nullable = false, length = 50)
    private String departmentId;

    /** 角色:ADMIN / MEMBER */
    @Column(nullable = false, length = 20)
    private String role;

    /** 创建时间,入库时自动填充 */
    @CreationTimestamp
    private LocalDateTime createdAt;
}
