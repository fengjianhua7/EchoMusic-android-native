package cc.echomusic.android;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Calendar;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** API 19 account storage using a device RSA key and authenticated AES encryption. */
final class LegacyAccountCipher {
    private static final String ALIAS = "echomusic.account.kitkat";

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    static String encrypt(Context context, String text) throws Exception {
        KeyStore store = store();
        if (!store.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = (Calendar) start.clone();
            end.add(Calendar.YEAR, 25);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyPairGeneratorSpec.Builder(context)
                .setAlias(ALIAS).setSubject(new X500Principal("CN=EchoMusic"))
                .setSerialNumber(BigInteger.ONE).setStartDate(start.getTime())
                .setEndDate(end.getTime()).build());
            generator.generateKeyPair();
            store = store();
        }

        byte[] keys = new byte[48];
        byte[] iv = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(keys);
        random.nextBytes(iv);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).getPublicKey());
        byte[] wrapped = rsa.doFinal(keys);
        Cipher aes = aes(Cipher.ENCRYPT_MODE, keys, iv);
        byte[] ciphertext = aes.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return encode(wrapped) + ":" + encode(iv) + ":" + encode(ciphertext)
            + ":" + encode(tag(keys, iv, ciphertext));
    }

    static String decrypt(String record) throws Exception {
        String[] parts = record.split(":", -1);
        if (parts.length != 4) throw new GeneralSecurityException("Invalid account record");
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, store().getKey(ALIAS, null));
        byte[] keys = Base64.decode(parts[0], Base64.NO_WRAP);
        keys = rsa.doFinal(keys);
        byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(parts[2], Base64.NO_WRAP);
        byte[] expected = Base64.decode(parts[3], Base64.NO_WRAP);
        if (keys.length != 48 || iv.length != 16
                || !MessageDigest.isEqual(expected, tag(keys, iv, ciphertext))) {
            throw new GeneralSecurityException("Invalid account authentication");
        }
        return new String(aes(Cipher.DECRYPT_MODE, keys, iv).doFinal(ciphertext),
            StandardCharsets.UTF_8);
    }

    private static Cipher aes(int mode, byte[] keys, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(keys, 0, 16, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    private static byte[] tag(byte[] keys, byte[] iv, byte[] ciphertext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(keys, 16, 48), "HmacSHA256"));
        mac.update(iv);
        return mac.doFinal(ciphertext);
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }
}
