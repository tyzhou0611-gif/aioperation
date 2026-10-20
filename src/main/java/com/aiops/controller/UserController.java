package com.aiops.controller;

import java.util.stream.Collectors;
import com.aiops.common.PageResult;
import com.aiops.common.Result;
import com.aiops.common.ServiceException;
import com.aiops.entity.SysUser;
import com.aiops.repository.SysUserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(SysUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/page")
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        org.springframework.data.jpa.domain.Specification<SysUser> spec = (root, q, cb) -> {
            if (keyword == null || keyword.trim().isEmpty()) return cb.conjunction();
            String p = "%" + keyword.trim() + "%";
            return cb.or(cb.like(root.get("username"), p), cb.like(root.get("nickname"), p), cb.like(root.get("email"), p));
        };
        org.springframework.data.domain.Page<SysUser> result = userRepository.findAll(spec,
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.ASC, "id")));
        List<Map<String, Object>> records = result.getContent().stream().map(this::toSafe).collect(Collectors.toList());
        PageResult<Map<String, Object>> pr = new PageResult<>();
        pr.setRecords(records);
        pr.setTotal(result.getTotalElements());
        pr.setPage(page);
        pr.setPageSize(pageSize);
        pr.setPages(result.getTotalPages());
        return Result.success(pr);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody SysUser user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new ServiceException("用户名已存在: " + user.getUsername());
        }
        user.setId(null);
        user.setPassword(passwordEncoder.encode(
                user.getPassword() != null && !user.getPassword().trim().isEmpty() ? user.getPassword() : "123456"));
        if (user.getStatus() == null) user.setStatus(0);
        SysUser saved = userRepository.save(user);
        return Result.success("新增成功", toSafe(saved));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        if (id == 1L) throw new ServiceException("不能删除内置管理员账户");
        userRepository.deleteById(id);
        return Result.success("删除成功", null);
    }

    private Map<String, Object> toSafe(SysUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("email", u.getEmail());
        m.put("status", u.getStatus());
        m.put("createTime", u.getCreateTime());
        m.put("roles", u.getRoles().stream().map(r -> r.getName()).collect(Collectors.toList()));
        return m;
    }
}