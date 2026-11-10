package hwbsample;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.CryptoException;
import javacard.security.KeyBuilder;
import javacard.security.Signature;
import javacardx.crypto.Cipher;


public class HWBAesMacApplet extends Applet {

    // constants
    private static final byte CLA_OUR   = (byte) 0x80;
    private static final byte INS_ENC   = (byte) 0x42; // Encrypt-then-MAC
    private static final byte INS_DEC   = (byte) 0x44; // Verify MAC then Decrypt

    
    private static final short BLOCK_LEN      = (short)16;
    private static final short MAX_PLAIN_LEN  = (short)64; // for INS 0x42
    private static final short MAC_LEN        = (short)16; // AES CBC-MAC
    private static final short MAX_IN_DEC_LEN = (short)80; // for INS 0x44 (<=64 cipher + 16 mac)

    
    private final AESKey    keyEnc;
    private final AESKey    keyMac;
    private final Cipher    aesEnc; 
    private final Cipher    aesDec;
    private final Signature aesCbcMac; 
    private final byte[]    ivZeros;  

    
    private static final byte[] ENC_KEY_BYTES = new byte[] {
        
        (byte)0x60,(byte)0x61,(byte)0x62,(byte)0x63,(byte)0x64,(byte)0x65,(byte)0x66,(byte)0x67,
        (byte)0x68,(byte)0x69,(byte)0x6A,(byte)0x6B,(byte)0x6C,(byte)0x6D,(byte)0x6E,(byte)0x6F
    };
    private static final byte[] MAC_KEY_BYTES = new byte[] {
        
        (byte)0xA0,(byte)0xA1,(byte)0xA2,(byte)0xA3,(byte)0xA4,(byte)0xA5,(byte)0xA6,(byte)0xA7,
        (byte)0xA8,(byte)0xA9,(byte)0xAA,(byte)0xAB,(byte)0xAC,(byte)0xAD,(byte)0xAE,(byte)0xAF
    };

   
    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new HWBAesMacApplet();
    }

    protected HWBAesMacApplet() {
        
        keyEnc = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        keyMac = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        keyEnc.setKey(ENC_KEY_BYTES, (short)0);
        keyMac.setKey(MAC_KEY_BYTES, (short)0);

        
        aesEnc = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
        aesDec = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);

       
        aesCbcMac = Signature.getInstance(Signature.ALG_AES_MAC_128_NOPAD, false);


        
        ivZeros = new byte[BLOCK_LEN];
        Util.arrayFillNonAtomic(ivZeros, (short)0, BLOCK_LEN, (byte)0x00);

        register(); 
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return; 
        }

        byte[] buf = apdu.getBuffer();

        
        if (buf[ISO7816.OFFSET_CLA] != CLA_OUR) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buf[ISO7816.OFFSET_INS]) {
            case INS_ENC:
                handleEncryptThenMac(apdu);
                return;

            case INS_DEC:
                handleVerifyThenDecrypt(apdu);
                return;

            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

   
    private short receiveAll(APDU apdu) {
        short got = apdu.setIncomingAndReceive();
        short total = got;
        short off = (short)(ISO7816.OFFSET_CDATA + got);
        while (got > 0) {
            got = apdu.receiveBytes(off);
            off += got;
            total += got;
        }
        return total;
    }

    
    private void checkBlockMultiple(short len, short max) {
        if (len <= 0 || (short)(len % BLOCK_LEN) != 0 || len > max) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH); // 0x6700
        }
    }

    

    private void handleEncryptThenMac(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short inLen = receiveAll(apdu); 

        
        checkBlockMultiple(inLen, MAX_PLAIN_LEN);

        short inOff  = ISO7816.OFFSET_CDATA;
        short outOff = ISO7816.OFFSET_CDATA;

        short encLen;
        try {
            
            aesEnc.init(keyEnc, Cipher.MODE_ENCRYPT, ivZeros, (short)0, BLOCK_LEN);
            encLen = aesEnc.doFinal(buf, inOff, inLen, buf, outOff); 
        } catch (CryptoException e) {
            
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            return; 
        }

       
        short macOff = (short)(outOff + encLen);
        short macLen;
        try {
            aesCbcMac.init(keyMac, Signature.MODE_SIGN);
            macLen = aesCbcMac.sign(buf, outOff, encLen, buf, macOff);
        } catch (CryptoException e) {
            
            ISOException.throwIt(ISO7816.SW_UNKNOWN); 
            return;
        }

        
        short totalOut = (short)(encLen + macLen);
        apdu.setOutgoing();
        apdu.setOutgoingLength(totalOut);
        apdu.sendBytesLong(buf, outOff, totalOut);
    }

 

    private void handleVerifyThenDecrypt(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short totalIn = receiveAll(apdu); 

        
        if (totalIn < (short)(BLOCK_LEN + MAC_LEN) || totalIn > MAX_IN_DEC_LEN) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        short inOff      = ISO7816.OFFSET_CDATA;
        short cipherLen  = (short)(totalIn - MAC_LEN);
        short macOff     = (short)(inOff + cipherLen);

        
        checkBlockMultiple(cipherLen, MAX_PLAIN_LEN);

        
        boolean ok;
        try {
            aesCbcMac.init(keyMac, Signature.MODE_VERIFY);
            ok = aesCbcMac.verify(buf, inOff, cipherLen, buf, macOff, MAC_LEN);
        } catch (CryptoException e) {
            ok = false;
        }
        if (!ok) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA); 
        }

        
        short decLen;
        try {
            aesDec.init(keyEnc, Cipher.MODE_DECRYPT, ivZeros, (short)0, BLOCK_LEN);
            decLen = aesDec.doFinal(buf, inOff, cipherLen, buf, inOff); 
        } catch (CryptoException e) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            return;
        }

        apdu.setOutgoing();
        apdu.setOutgoingLength(decLen);
        apdu.sendBytesLong(buf, inOff, decLen);
    }
}
