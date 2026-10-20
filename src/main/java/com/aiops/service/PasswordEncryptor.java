package com.aiops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * SSH 密码 AES-256 加解密工具（可逆）。
 * 密钥来自配置 app.security.aes-key（生产用环境变量覆盖），经 SHA-256 派生 32 字节。
 * 密文格式：base64( IV(16) + AES/CBC/PKCS5(明文) )。
 */
@Component
public class PasswordEncryptor {

    private static final Logger log = LoggerFactory.getLogger(PasswordEncryptor.class);
    private static final String ALGO = "AES/CBC/PKCS5Padding";

    private final SecretKeySpec keySpec;

    public PasswordEncryptor(@Value("${app.security.aes-key:aiops-default-aes-key-change-me}") String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            this.keySpec = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 AES 密钥失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        if (plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[16];
            new Random().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            log.error("密码加密失败", e);
            throw new RuntimeException("密码加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return cipherText;
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length < 17) throw new IllegalArgumentException("密文长度异常");
            byte[] iv = Arrays.copyOfRange(all, 0, 16);
            byte[] enc = Arrays.copyOfRange(all, 16, all.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("服务器密码解密失败，请重新设置（密钥可能已变更或数据损坏）", e);
        }
    }
}