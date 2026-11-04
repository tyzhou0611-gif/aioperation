package com.aiops.service;

import com.aiops.common.ServiceException;
import com.aiops.entity.FtpServer;
import com.aiops.repository.FtpServerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** FTP/SFTP 资产 CRUD（密码 AES 加密，接口不回显明文） */
@Service
public class FtpServerService {

    private static final Logger log = LoggerFactory.getLogger(FtpServerService.class);

    private final FtpServerRepository repository;
    private final PasswordEncryptor encryptor;

    public FtpServerService(FtpServerRepository repository, PasswordEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    public List<FtpServer> list() {
        List<FtpServer> list = repository.findAllByOrderByIdAsc();
        for (FtpServer s : list) decorate(s);
        return list;
    }

    public FtpServer get(Long id) {
        FtpServer s = repository.findById(id).orElseThrow(() -> new ServiceException(404, "FTP 服务器不存在: " + id));
        decorate(s);
        return s;
    }

    public FtpServer raw(Long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceException(404, "FTP 服务器不存在: " + id));
    }

    @Transactional
    public FtpServer create(FtpServer input) {
        validate(input);
        FtpServer s = new FtpServer();
        apply(s, input, true);
        FtpServer saved = repository.save(s);
        decorate(saved);
        log.info("FTP 资产已创建: {} {}://{}:{}", saved.getName(), saved.getProtocol(), saved.getHost(), saved.getPort());
        return saved;
    }

    @Transactional
    public FtpServer update(Long id, FtpServer input) {
        FtpServer s = raw(id);
        validate(input);
        apply(s, input, false);
        FtpServer saved = repository.save(s);
        decorate(saved);
        log.info("FTP 资产已更新: {} {}://{}:{}", saved.getName(), saved.getProtocol(), saved.getHost(), saved.getPort());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        FtpServer s = raw(id);
        repository.delete(s);
        log.info("FTP 资产已删除: {}", s.getName());
    }

    private void apply(FtpServer s, FtpServer input, boolean create) {
        s.setName(trim(input.getName()));
        String protocol = trim(input.getProtocol()).toUpperCase();
        if (!"SFTP".equals(protocol) && !"FTP".equals(protocol)) protocol = "FTP";
        s.setProtocol(protocol);
        s.setHost(trim(input.getHost()));
        int port = input.getPort() == null || input.getPort() <= 0 ? ("SFTP".equals(protocol) ? 22 : 21) : input.getPort();
        s.setPort(port);
        s.setUsername(trim(input.getUsername()));
        s.setBasePath(trim(input.getBasePath()).isEmpty() ? "/" : trim(input.getBasePath()));
        s.setPassiveMode(input.getPassiveMode() == null ? 1 : input.getPassiveMode());
        s.setEnabled(input.getEnabled() == null ? 1 : input.getEnabled());
        s.setDescription(trim(input.getDescription()));
        String pwd = input.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            s.setPasswordEncrypted(encryptor.encrypt(pwd));
        } else if (create) {
            s.setPasswordEncrypted(null);
        }
    }

    private void validate(FtpServer s) {
        if (s == null) throw new ServiceException(400, "FTP 资产不能为空");
        if (trim(s.getName()).isEmpty()) throw new ServiceException(400, "名称不能为空");
        if (trim(s.getHost()).isEmpty()) throw new ServiceException(400, "主机地址不能为空");
        String protocol = trim(s.getProtocol()).toUpperCase();
        if (!protocol.isEmpty() && !"FTP".equals(protocol) && !"SFTP".equals(protocol)) {
            throw new ServiceException(400, "仅支持 FTP / SFTP");
        }
        if (s.getPort() != null && (s.getPort() < 1 || s.getPort() > 65535)) {
            throw new ServiceException(400, "端口必须在 1-65535 之间");
        }
    }

    private void decorate(FtpServer s) {
        s.setPasswordSet(s.getPasswordEncrypted() != null && !s.getPasswordEncrypted().isEmpty());
        s.setPassword(null);
    }

    private String trim(String s) { return s == null ? "" : s.trim(); }
}