package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.SysRole;
import com.aiops.repository.SysRoleRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/role")
public class RoleController {

    private final SysRoleRepository roleRepository;

    public RoleController(SysRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @GetMapping("/list")
    public Result<List<SysRole>> list() {
        return Result.success(roleRepository.findAll());
    }
}