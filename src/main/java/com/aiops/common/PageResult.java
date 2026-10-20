package com.aiops.common;

import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 通用分页结果
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int page;
    private int pageSize;
    private long pages;

    public static <T> PageResult<T> of(Page<T> pageResult) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(pageResult.getContent());
        result.setTotal(pageResult.getTotalElements());
        result.setPage(pageResult.getNumber() + 1);
        result.setPageSize(pageResult.getSize());
        result.setPages(pageResult.getTotalPages());
        return result;
    }
}