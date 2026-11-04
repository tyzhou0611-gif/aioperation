package com.aiops.controller;

import com.aiops.common.Result;
import com.aiops.entity.SysMenu;
import com.aiops.repository.SysMenuRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final SysMenuRepository menuRepository;

    public MenuController(SysMenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    @GetMapping("/list")
    public Result<List<SysMenu>> list() {
        return Result.success(menuRepository.findAllByOrderBySortAsc());
    }
}