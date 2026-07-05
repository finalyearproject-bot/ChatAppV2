package com.example.chatapp.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoEngine {

    static {
        // Register BouncyCastle
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    public static final int NONCE_LEN = 12;
    public static final int KEY_LEN = 32;

    // Generate X25519 KeyPair
    public static KeyPair generateX25519KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519", "BC");
        return kpg.generateKeyPair();
    }

    // ECDH (Diffie-Hellman)
    public static byte[] dh(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519", "BC");
        ka.init(privateKey);
        ka.doPhase(publicKey, true);
        return ka.generateSecret();
    }

    // HKDF-SHA256 (for Root Key and Chain Key derivations)
    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(ikm, salt, info));
        byte[] okm = new byte[length];
        hkdf.generateBytes(okm, 0, length);
        return okm;
    }

    // HMAC-SHA256 (for Symmetric Ratchet)
    public static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256", "BC");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // AES-GCM Encryption
    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(plaintext);
    }

    // AES-GCM Decryption
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", "BC");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(ciphertext);
    }
}