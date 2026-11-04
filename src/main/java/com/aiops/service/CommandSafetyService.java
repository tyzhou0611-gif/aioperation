package com.aiops.service;

import com.aiops.entity.ServerAsset;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 危险命令护栏：
 * - BLOCK：直接拦截（rm -rf /、dd 写磁盘、mkfs、覆盖 passwd 等）
 * - HIGH：需要二次确认；生产环境必须先 dry-run 预览，并用预览令牌执行
 */
@Service
public class CommandSafetyService {

    public enum Level { SAFE, CONFIRM, BLOCKED }

    public static class Risk {
        private Level level = Level.SAFE;
        private String riskLevel = "LOW";
        private String message = "未发现高危特征";
        private boolean blocked = false;
        private boolean requiresConfirm = false;
        private boolean production = false;
        private boolean dryRunRequired = false;
        private List<String> matchedRules = new ArrayList<String>();

        public Level getLevel() { return level; }
        public String getRiskLevel() { return riskLevel; }
        public String getMessage() { return message; }
        public boolean isBlocked() { return blocked; }
        public boolean isRequiresConfirm() { return requiresConfirm; }
        public boolean isProduction() { return production; }
        public boolean isDryRunRequired() { return dryRunRequired; }
        public List<String> getMatchedRules() { return matchedRules; }
    }

    private static class Rule {
        final String name;
        final Pattern pattern;
        final boolean block;
        Rule(String name, String regex, boolean block) {
            this.name = name;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            this.block = block;
        }
    }

    private static final List<Rule> RULES = Arrays.asList(
            // 直接拦截
            new Rule("删除根目录", "\\brm\\s+-[a-z]*(rf|fr)[a-z]*\\s+/\\s*$", true),
            new Rule("删除根目录", "\\brm\\s+-[a-z]*(rf|fr)[a-z]*\\s+/\\*", true),
            new Rule("磁盘裸写", "\\bdd\\b[^\\n]*\\bof=/dev/(sd|nvme|hd|vd|mmcblk)", true),
            new Rule("格式化文件系统", "\\bmkfs(\\.\\w+)?\\b", true),
            new Rule("覆盖系统账号文件", ">\\s*/etc/(passwd|shadow|sudoers)", true),
            new Rule("Fork 炸弹", ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:", true),
            new Rule("移动根目录内容", "\\bmv\\s+/\\*", true),
            new Rule("全盘 777", "\\bchmod\\s+-R\\s+777\\s+/\\s*$", true),

            // 需要确认
            new Rule("递归强制删除", "\\brm\\s+-[a-z]*(rf|fr)[a-z]*\\b", false),
            new Rule("关机/重启", "\\b(shutdown|reboot|poweroff|halt)\\b", false),
            new Rule("切换运行级别", "\\binit\\s+[06]\\b", false),
            new Rule("杀死 1 号进程", "\\bkill\\s+-9\\s+1\\b", false),
            new Rule("裸写块设备", ">\\s*/dev/(sd|nvme|hd|vd)", false),
            new Rule("清空防火墙规则", "\\biptables\\s+-F\\b", false),
            new Rule("清空文件", "\\btruncate\\s+-s\\s*0\\b", false),
            new Rule("删除数据库对象", "\\bdrop\\s+(table|database)\\b", false),
            new Rule("停止关键服务", "\\bsystemctl\\s+(stop|disable)\\s+(sshd|network|firewalld|docker)\\b", false)
    );

    private final Map<String, Preview> previews = new ConcurrentHashMap<String, Preview>();

    public Risk evaluate(String command, List<ServerAsset> servers) {
        Risk risk = new Risk();
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            risk.level = Level.BLOCKED; risk.blocked = true; risk.riskLevel = "BLOCKED";
            risk.message = "命令不能为空";
            return risk;
        }
        for (Rule r : RULES) {
            if (r.pattern.matcher(cmd).find()) {
                risk.matchedRules.add(r.name);
                if (r.block) {
                    risk.level = Level.BLOCKED; risk.blocked = true; risk.riskLevel = "BLOCKED";
                } else if (risk.level != Level.BLOCKED) {
                    risk.level = Level.CONFIRM; risk.requiresConfirm = true; risk.riskLevel = "HIGH";
                }
            }
        }
        boolean production = false;
        if (servers != null) {
            for (ServerAsset s : servers) {
                String env = s.getEnvironment() == null ? "" : s.getEnvironment().toLowerCase(Locale.ROOT);
                if (env.contains("prod")) { production = true; break; }
            }
        }
        risk.production = production;
        risk.dryRunRequired = risk.level == Level.CONFIRM && production;
        if (risk.level == Level.BLOCKED) {
            risk.message = "命中危险命令黑名单（" + join(risk.matchedRules) + "），已直接拦截";
        } else if (risk.level == Level.CONFIRM) {
            risk.message = "命中高危命令（" + join(risk.matchedRules) + "），需要二次确认"
                    + (production ? "；生产环境必须先 dry-run 预览" : "");
        }
        return risk;
    }

    /** 生成 dry-run 预览令牌（生产高危命令执行时需校验） */
    public String issuePreviewToken(String command, List<Long> serverIds) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Preview p = new Preview();
        p.command = normalize(command);
        p.serverIds = new HashSet<Long>(serverIds == null ? new ArrayList<Long>() : serverIds);
        p.expiresAt = System.currentTimeMillis() + 10 * 60 * 1000;
        previews.put(token, p);
        cleanup();
        return token;
    }

    public boolean validPreviewToken(String token, String command, List<Long> serverIds) {
        if (token == null || token.trim().isEmpty()) return false;
        Preview p = previews.get(token.trim());
        if (p == null || p.expiresAt < System.currentTimeMillis()) return false;
        if (!p.command.equals(normalize(command))) return false;
        if (serverIds != null) {
            for (Long id : serverIds) if (id != null && !p.serverIds.contains(id)) return false;
        }
        return true;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        previews.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    private String normalize(String cmd) {
        return cmd == null ? "" : cmd.trim().replaceAll("\\s+", " ");
    }

    private String join(List<String> list) {
        Map<String, Boolean> unique = new LinkedHashMap<String, Boolean>();
        for (String s : list) unique.put(s, true);
        return String.join("、", unique.keySet());
    }

    private static class Preview {
        String command;
        Set<Long> serverIds;
        long expiresAt;
    }
}